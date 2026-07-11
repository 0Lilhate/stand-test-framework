package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.junit.StandParallelSafe;

/**
 * Consumer-facing demonstration of parallel stand tests using ORDINARY JUnit — no manual thread pool. The
 * class is annotated {@link StandParallelSafe} (the SDK facade over {@code @Execution(CONCURRENT)}), so its
 * {@code @Test} methods and {@code @ParameterizedTest} cases become eligible to run CONCURRENTLY even though
 * the module default is {@code mode.default=same_thread}. This is exactly how a consuming team parallelises
 * stand tests: annotate the class and rely on the SDK's per-run isolation — no threading code of its own.
 *
 * <p>Each invocation is a full, self-contained DB scenario against the shared H2, isolated only by its unique
 * per-run {@code testRunId}: it seeds a {@code <label>-${testRunId}} row (a per-run-unique primary key, so
 * concurrent seeds never collide), reads it back scoped to that id, and cleans up its own
 * {@code testRunId}-tagged rows. Because every invocation is isolated this way, running them concurrently is
 * safe — a cross-run collision or leak would fail the scoped read, so all invocations passing under concurrent
 * execution IS the parallel-safety guarantee.
 *
 * <p>This test shows the <em>authoring pattern</em>; for a deterministic proof that runs actually overlap in
 * time (independent of the shared JUnit thread pool), see {@code ParallelFrameworkExecutionExampleTest}, which
 * drives many runs through one shared client on its own {@code ExecutorService}.
 */
@StandParallelSafe
class ParallelStandScenariosExampleTest {

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @ParameterizedTest(name = "case ''{0}'' runs concurrently and stays isolated by testRunId")
    @ValueSource(strings = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel"})
    void dataDrivenCasesRunInParallel(String label) {
        runIsolatedScenario(label);
    }

    @Test
    @DisplayName("a plain @Test method runs concurrently with the other methods/cases, isolated by its own testRunId")
    void firstFlowRunsConcurrently() {
        runIsolatedScenario("flow-one");
    }

    @Test
    @DisplayName("a second plain @Test method also runs concurrently, isolated by its own testRunId")
    void secondFlowRunsConcurrently() {
        runIsolatedScenario("flow-two");
    }

    /**
     * A self-contained, parallel-safe DB scenario: seed a {@code testRunId}-scoped row, read it back, clean it
     * up. Nothing here is shared between invocations except the SDK's per-run isolation.
     */
    private void runIsolatedScenario(String label) {
        StandClient stand = ExampleStand.stand(ExampleStand.dbRegistry());
        Scenario scenario = Scenario.builder("parallel-" + label)
                .environment(ExampleStand.ENVIRONMENT)
                .step(DbStep.seed(ExampleStand.DATASOURCE)
                        .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, :status, :testRunId)")
                        .taggedByTestRunId("test_run_id")
                        .param("id", label + "-${testRunId}")
                        .param("status", label)
                        .build())
                .step(DbStep.expectEventually(ExampleStand.DATASOURCE)
                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                        .param("id", label + "-${testRunId}")
                        .expectValue(label)
                        .withinSeconds(2)
                        .build())
                .step(DbStep.cleanup(ExampleStand.DATASOURCE)
                        .sql("DELETE FROM test_data.orders")
                        .whereTestRunId("test_run_id")
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);
        assertThat(result.isSuccessful()).isTrue();
    }
}
