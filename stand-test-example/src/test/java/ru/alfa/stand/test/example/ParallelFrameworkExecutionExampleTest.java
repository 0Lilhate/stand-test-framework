package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.db.DbStep;

/**
 * The parallel-execution proof (plan §15): many scenario runs driven concurrently through ONE shared
 * {@link StandClient} (the sanctioned "one cached client per JUnit engine" wiring) against the shared H2
 * double, asserting that the SDK's per-run isolation holds under real thread contention. It exercises the
 * same seam JUnit parallel execution uses — a single {@code DefaultScenarioRunner} and a single instance of
 * each {@link StepExecutor}/{@link ReportingEventPublisher} invoked from many threads at once — and verifies:
 *
 * <ul>
 *   <li>every run gets a UNIQUE {@code testRunId} and a UNIQUE {@code correlationId};</li>
 *   <li>each run's {@code VariableStore} is isolated (it captured only its OWN row's id);</li>
 *   <li>reporting events never cross runs (every event of one scenario carries that scenario's ids);</li>
 *   <li>no data leaks (each run seeds/reads/cleans a {@code testRunId}-scoped row keyed by its own
 *       {@code testRunId}, so no run can observe or delete another's data);</li>
 *   <li>all scenarios succeed.</li>
 * </ul>
 *
 * <p>Each row is keyed {@code p-${testRunId}} (a per-run-unique primary key, so concurrent seeds never
 * collide) and tagged with {@code :testRunId} (so the run's own testRunId-scoped cleanup reaps it). This is
 * the intra-test demonstration of thread-level parallelism; the module-level configuration
 * ({@code junit-platform.properties}) additionally runs the example CLASSES concurrently.
 */
class ParallelFrameworkExecutionExampleTest {

    private static final int RUNS = 24;

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("many scenarios run concurrently through one shared StandClient and stay fully isolated")
    void concurrentRunsAreIsolated() throws Exception {
        RecordingPublisher publisher = new RecordingPublisher();
        SnapshotProbe probe = new SnapshotProbe();
        // ONE client, ONE runner, ONE instance of each executor/publisher — shared across every thread.
        StandClient stand = ExampleStand.fullStand(ExampleStand.dbRegistry(), publisher, probe);

        int threads = Math.min(RUNS, Math.max(4, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ScenarioResult>> futures = new ArrayList<>();
            for (int i = 0; i < RUNS; i++) {
                Scenario scenario = scenario(i);
                futures.add(pool.submit(() -> stand.run(scenario)));
            }
            List<ScenarioResult> results = new ArrayList<>();
            for (Future<ScenarioResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }

            // All runs succeeded — a cross-run data leak would fail the per-run scoped read.
            assertThat(results).hasSize(RUNS).allSatisfy(result -> assertThat(result.isSuccessful()).isTrue());

            List<SnapshotProbe.Observation> observations = probe.observations();
            assertThat(observations).hasSize(RUNS);

            // Unique testRunId / correlationId per run.
            assertThat(observations.stream().map(SnapshotProbe.Observation::testRunId).distinct().count()).isEqualTo(RUNS);
            assertThat(observations.stream().map(SnapshotProbe.Observation::correlationId).distinct().count()).isEqualTo(RUNS);

            // VariableStore isolation: each run captured ITS OWN row id (p-<its testRunId>), never another's.
            assertThat(observations).allSatisfy(observation ->
                    assertThat(observation.variables()).containsEntry("ownId", "p-" + observation.testRunId()));

            // Reporting isolation: every event of a scenario carries that scenario's own ids; ids never mix.
            Map<String, List<RecordingPublisher.Event>> byScenario = publisher.events().stream()
                    .collect(Collectors.groupingBy(RecordingPublisher.Event::scenarioId));
            assertThat(byScenario).hasSize(RUNS);
            assertThat(byScenario.values()).allSatisfy(group -> {
                assertThat(group.stream().map(RecordingPublisher.Event::testRunId).distinct()).hasSize(1);
                assertThat(group.stream().map(RecordingPublisher.Event::correlationId).distinct()).hasSize(1);
            });

            // No data leakage: each run cleaned up its own testRunId-scoped rows, so none of this test's rows remain.
            assertThat(parallelRowCount()).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Scenario scenario(int index) {
        return Scenario.builder("parallel-" + index)
                .environment(ExampleStand.ENVIRONMENT)
                .step(DbStep.seed(ExampleStand.DATASOURCE)
                        .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'DONE', :testRunId)")
                        .taggedByTestRunId("test_run_id")
                        .param("id", "p-${testRunId}")
                        .build())
                .step(DbStep.query(ExampleStand.DATASOURCE)
                        .sql("SELECT id FROM test_data.orders WHERE id = :id")
                        .param("id", "p-${testRunId}")
                        .capture("ownId", "id")
                        .build())
                .step(GenericStep.of("snapshot-" + index, SnapshotProbe.STEP_TYPE))
                .step(DbStep.cleanup(ExampleStand.DATASOURCE)
                        .sql("DELETE FROM test_data.orders")
                        .whereTestRunId("test_run_id")
                        .build())
                .build();
    }

    private static long parallelRowCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                System.getenv("MAIN_DB_URL"), System.getenv("MAIN_DB_USER"), System.getenv("MAIN_DB_PASSWORD"));
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM test_data.orders WHERE id LIKE 'p-%'")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    /**
     * A thread-safe reporting sink: it records every scenario/step event so the test can prove that events
     * from concurrent runs never cross (each scenario's events all carry that scenario's ids).
     */
    private static final class RecordingPublisher implements ReportingEventPublisher {

        private final Queue<Event> events = new ConcurrentLinkedQueue<>();

        @Override
        public void publish(ScenarioEvent event) {
            this.events.add(new Event(event.scenarioId().value(), event.testRunId().value(), event.correlationId().value()));
        }

        @Override
        public void publish(StepEvent event) {
            this.events.add(new Event(event.scenarioId().value(), event.testRunId().value(), event.correlationId().value()));
        }

        List<Event> events() {
            return List.copyOf(this.events);
        }

        record Event(String scenarioId, String testRunId, String correlationId) {
        }
    }

    /**
     * A thread-safe {@link StepExecutor} test double: on its step type it records the run's ids and an
     * immutable snapshot of the per-run {@code VariableStore}, so the test can assert per-run isolation.
     */
    private static final class SnapshotProbe implements StepExecutor {

        static final String STEP_TYPE = "example.parallel.snapshot";

        private final Queue<Observation> observations = new ConcurrentLinkedQueue<>();

        @Override
        public boolean supports(String stepType) {
            return STEP_TYPE.equals(stepType);
        }

        @Override
        public StepResult execute(ScenarioStep step, StepExecutionContext context) {
            Instant now = Instant.now();
            this.observations.add(new Observation(
                    context.scenarioContext().scenarioId().value(),
                    context.scenarioContext().testRunId().value(),
                    context.scenarioContext().correlationId().value(),
                    context.variableStore().asMap()));
            return StepResult.success(step.id(), step.type(), now, now);
        }

        List<Observation> observations() {
            return List.copyOf(this.observations);
        }

        record Observation(String scenarioId, String testRunId, String correlationId, Map<String, Object> variables) {
        }
    }
}
