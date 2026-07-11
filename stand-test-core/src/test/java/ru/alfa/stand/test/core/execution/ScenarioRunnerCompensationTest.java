package ru.alfa.stand.test.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.compensation.CleanupPolicy;
import ru.alfa.stand.test.core.compensation.CompensationOutcome;
import ru.alfa.stand.test.core.compensation.CompensationStatus;
import ru.alfa.stand.test.core.compensation.Compensator;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.ScenarioPhase;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.validation.ScenarioValidator;
import ru.alfa.stand.test.core.validation.ValidationResult;

@DisplayName("DefaultScenarioRunner test-data compensation drain")
class ScenarioRunnerCompensationTest {

    private static DefaultScenarioRunner runner(List<String> recorder) {
        return runner(recorder, NoOpReportingEventPublisher.INSTANCE);
    }

    private static DefaultScenarioRunner runner(List<String> recorder, ReportingEventPublisher publisher) {
        return new DefaultScenarioRunner(
                List.of(new FakeExecutor(recorder)),
                new PermissiveValidator(),
                new InMemoryEnvironmentRegistry(Map.of()),
                publisher);
    }

    private static GenericStep writeStep(String stepId, String actionId, String target) {
        return new GenericStep(stepId, "fake.write", "", Map.of("actionId", actionId, "target", target));
    }

    private static GenericStep failingStep(String stepId) {
        return new GenericStep(stepId, "fake.fail", "", Map.of("fail", Boolean.TRUE));
    }

    @Test
    @DisplayName("ON_FAILURE (default): green run does NOT compensate")
    void onFailure_greenRun_noCompensation() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("green")
                .environment("ift")
                .step(writeStep("w1", "a1", "dsA"))
                .build();

        runner(compensated).run(scenario);

        assertThat(compensated).isEmpty();
    }

    @Test
    @DisplayName("ON_FAILURE: a failed step compensates registered actions in reverse order")
    void onFailure_failedRun_reverseOrder() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("failing")
                .environment("ift")
                .step(writeStep("w1", "a1", "dsA"))
                .step(writeStep("w2", "a2", "dsB"))
                .step(failingStep("boom"))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(compensated).containsExactly("a2", "a1");
    }

    @Test
    @DisplayName("ALWAYS: green run compensates")
    void always_greenRun_compensates() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("green-always")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.ALWAYS)
                .step(writeStep("w1", "a1", "dsA"))
                .build();

        runner(compensated).run(scenario);

        assertThat(compensated).containsExactly("a1");
    }

    @Test
    @DisplayName("NEVER: a failed run does not compensate")
    void never_failedRun_noCompensation() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("never")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.NEVER)
                .step(writeStep("w1", "a1", "dsA"))
                .step(failingStep("boom"))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(compensated).isEmpty();
    }

    @Test
    @DisplayName("multi-datasource: both datasources are compensated on failure")
    void multiDatasource_bothCompensated() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("multi-ds")
                .environment("ift")
                .step(writeStep("seedA", "insA", "dsA"))
                .step(writeStep("seedB", "insB", "dsB"))
                .step(failingStep("api"))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(compensated).containsExactly("insB", "insA");
    }

    @Test
    @DisplayName("original failure preserved; cleanup failure attached as suppressed")
    void failedRun_cleanupAlsoFails_originalPreserved() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("both-fail")
                .environment("ift")
                .step(new GenericStep("w1", "fake.write", "", Map.of("actionId", "a1", "target", "dsA", "compResult", "FAILED")))
                .step(failingStep("boom"))
                .build();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> runner(compensated).run(scenario));

        assertThat(thrown).isInstanceOf(StandTestAssertionError.class);
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(thrown.getSuppressed()[0]).isInstanceOf(StandTestException.class);
        assertThat(thrown.getSuppressed()[0]).hasMessageContaining("compensation failed");
        assertThat(compensated).containsExactly("a1");
    }

    @Test
    @DisplayName("green run + cleanup failure fails the test (ALWAYS)")
    void greenRun_cleanupFails_failsTest() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("green-cleanup-fail")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.ALWAYS)
                .step(new GenericStep("w1", "fake.write", "", Map.of("actionId", "a1", "target", "dsA", "compResult", "FAILED")))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("compensation failed");

        assertThat(compensated).containsExactly("a1");
    }

    @Test
    @DisplayName("partial cleanup failure: every action is attempted and aggregated")
    void partialFailure_allAttemptedAndAggregated() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("partial")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.ALWAYS)
                .step(new GenericStep("w1", "fake.write", "", Map.of("actionId", "okA", "target", "dsA")))
                .step(new GenericStep("w2", "fake.write", "", Map.of("actionId", "failB", "target", "dsB", "compResult", "FAILED")))
                .step(new GenericStep("w3", "fake.write", "", Map.of("actionId", "okC", "target", "dsC")))
                .build();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> runner(compensated).run(scenario));

        assertThat(thrown).isInstanceOf(StandTestException.class);
        // reverse order, all three attempted even though the middle one failed
        assertThat(compensated).containsExactly("okC", "failB", "okA");
    }

    @Test
    @DisplayName("CONFLICT fails a green run (strict, no blind overwrite)")
    void conflict_failsGreenRun() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("conflict")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.ALWAYS)
                .step(new GenericStep("w1", "fake.write", "", Map.of("actionId", "a1", "target", "dsA", "compResult", "CONFLICT")))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario))
                .isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("parallel runs on the same runner keep undo-logs isolated (no cross-compensation)")
    void parallel_isolatedUndoLogs() throws Exception {
        List<String> compensated = new CopyOnWriteArrayList<>();
        DefaultScenarioRunner sharedRunner = runner(compensated);

        int runs = 8;
        List<Scenario> scenarios = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            String tag = "run" + i;
            scenarios.add(Scenario.builder(tag)
                    .environment("ift")
                    .step(writeStep(tag + "-w1", tag + ":a1", "dsA"))
                    .step(writeStep(tag + "-w2", tag + ":a2", "dsB"))
                    .step(failingStep(tag + "-boom"))
                    .build());
        }

        ExecutorService pool = Executors.newFixedThreadPool(runs);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Scenario scenario : scenarios) {
                futures.add(pool.submit(() -> {
                    assertThatThrownBy(() -> sharedRunner.run(scenario)).isInstanceOf(StandTestAssertionError.class);
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // every run compensated exactly its own two actions, tagged with its own run id
        assertThat(compensated).hasSize(runs * 2);
        for (int i = 0; i < runs; i++) {
            String tag = "run" + i;
            assertThat(compensated).contains(tag + ":a1", tag + ":a2");
            List<String> forRun = compensated.stream().filter(id -> id.startsWith(tag + ":")).toList();
            assertThat(forRun).containsExactlyInAnyOrder(tag + ":a1", tag + ":a2");
        }
    }

    @Test
    @DisplayName("empty undo-log on a failed run compensates nothing and preserves the failure")
    void emptyLog_failedRun_noop() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("empty")
                .environment("ift")
                .step(failingStep("boom"))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario)).isInstanceOf(StandTestAssertionError.class);
        assertThat(compensated).isEmpty();
    }

    @Test
    @DisplayName("compensation emits db.compensate STARTED/FINISHED reporting events with a mapped status")
    void compensation_emitsReportingEvents() {
        List<String> compensated = new ArrayList<>();
        CapturingPublisher publisher = new CapturingPublisher();
        Scenario scenario = Scenario.builder("reporting")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.ALWAYS)
                .step(writeStep("w1", "a1", "dsA"))
                .build();

        runner(compensated, publisher).run(scenario);

        List<StepEvent> compensationEvents = publisher.stepEvents.stream()
                .filter(event -> "db.compensate".equals(event.stepType()))
                .toList();
        assertThat(compensationEvents).extracting(StepEvent::phase).containsExactly(StepPhase.STARTED, StepPhase.FINISHED);
        StepEvent finished = compensationEvents.get(1);
        assertThat(finished.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(finished.diagnostics()).containsEntry("compensation.status", "APPLIED").containsEntry("compensation.target", "dsA");
        // the scenario still emits FINISHED (the tail runs)
        assertThat(publisher.scenarioPhases).contains(ScenarioPhase.FINISHED);
    }

    @Test
    @DisplayName("a Compensator that throws an Error is folded into a FAILED outcome; the primary failure stays primary and the tail runs")
    void compensator_throwsError_doesNotEscapeOrMask() {
        List<String> compensated = new ArrayList<>();
        CapturingPublisher publisher = new CapturingPublisher();
        Scenario scenario = Scenario.builder("error-compensator")
                .environment("ift")
                .step(new GenericStep("w1", "fake.write", "", Map.of("actionId", "a1", "target", "dsA", "compThrows", Boolean.TRUE)))
                .step(failingStep("boom"))
                .build();

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> runner(compensated, publisher).run(scenario));

        // The original assertion failure is still primary — the Error did NOT escape or replace it.
        assertThat(thrown).isInstanceOf(StandTestAssertionError.class);
        assertThat(thrown.getSuppressed()).hasSize(1);
        assertThat(thrown.getSuppressed()[0]).isInstanceOf(StandTestException.class);
        // The tail ran despite the Error: the scenario FINISHED event was published.
        assertThat(publisher.scenarioPhases).contains(ScenarioPhase.FINISHED);
        assertThat(compensated).containsExactly("a1");
    }

    @Test
    @DisplayName("a Compensator that throws an Error on a green ALWAYS run fails the test with a StandTestException, not the raw Error")
    void compensator_throwsError_greenRun_failsWithStandTestException() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("error-green")
                .environment("ift")
                .cleanupPolicy(CleanupPolicy.ALWAYS)
                .step(new GenericStep("w1", "fake.write", "", Map.of("actionId", "a1", "target", "dsA", "compThrows", Boolean.TRUE)))
                .build();

        assertThatThrownBy(() -> runner(compensated).run(scenario))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("compensation failed");
    }

    @Test
    @DisplayName("green run with no writes completes normally")
    void greenRun_noWrites_ok() {
        List<String> compensated = new ArrayList<>();
        Scenario scenario = Scenario.builder("noop")
                .environment("ift")
                .step(GenericStep.of("s1", "fake.noop"))
                .build();

        assertThatCode(() -> runner(compensated).run(scenario)).doesNotThrowAnyException();
        assertThat(compensated).isEmpty();
    }

    /** A permissive validator so the compensation behaviour can be tested without a whitelist. */
    private static final class PermissiveValidator implements ScenarioValidator {
        @Override
        public ValidationResult validate(Scenario scenario) {
            return ValidationResult.valid();
        }

        @Override
        public ValidationResult validate(Scenario scenario, EnvironmentRegistry registry) {
            return ValidationResult.valid();
        }
    }

    /**
     * A fake executor: a {@code fake.write} step registers a {@link RecordingCompensator}; a
     * {@code fake.fail} step throws a {@link StandTestAssertionError}; anything else is a no-op success.
     */
    private static final class FakeExecutor implements StepExecutor {

        private final List<String> recorder;

        private FakeExecutor(List<String> recorder) {
            this.recorder = recorder;
        }

        @Override
        public boolean supports(String stepType) {
            return stepType.startsWith("fake.");
        }

        @Override
        public StepResult execute(ScenarioStep step, StepExecutionContext context) {
            Map<String, Object> params = ((GenericStep) step).parameters();
            Object actionId = params.get("actionId");
            if (actionId != null) {
                String target = String.valueOf(params.getOrDefault("target", "ds"));
                CompensationStatus result = CompensationStatus.valueOf(String.valueOf(params.getOrDefault("compResult", "APPLIED")));
                boolean throwsError = Boolean.TRUE.equals(params.get("compThrows"));
                context.undoLog().register(new RecordingCompensator(String.valueOf(actionId), target, result, recorder, throwsError));
            }
            if (Boolean.TRUE.equals(params.get("fail"))) {
                throw new StandTestAssertionError("step '" + step.id() + "' failed on purpose");
            }
            return StepResult.success(step.id(), step.type(), Instant.EPOCH, Instant.EPOCH);
        }
    }

    /** Records its action id when compensated and returns a configurable outcome (or throws an Error). */
    private static final class RecordingCompensator implements Compensator {

        private final String actionId;
        private final String target;
        private final CompensationStatus result;
        private final List<String> recorder;
        private final boolean throwsError;

        private RecordingCompensator(String actionId, String target, CompensationStatus result, List<String> recorder, boolean throwsError) {
            this.actionId = actionId;
            this.target = target;
            this.result = result;
            this.recorder = recorder;
            this.throwsError = throwsError;
        }

        @Override
        public String actionId() {
            return actionId;
        }

        @Override
        public String target() {
            return target;
        }

        @Override
        public CompensationOutcome compensate() {
            recorder.add(actionId);
            if (throwsError) {
                // A contract violation: throw an Error (extends Throwable but not RuntimeException) to prove
                // the runner's drain net catches it instead of letting it escape the finally.
                throw new AssertionError("compensator threw an Error on purpose");
            }
            return switch (result) {
                case APPLIED -> CompensationOutcome.applied(actionId, target, 1L, Map.of());
                case SKIPPED -> CompensationOutcome.skipped(actionId, target, "already undone", Map.of());
                case CONFLICT -> CompensationOutcome.conflict(actionId, target, "row diverged", Map.of());
                case FAILED -> CompensationOutcome.failed(actionId, target, "compensation failed on purpose", new IllegalStateException("boom"), Map.of());
            };
        }
    }

    /** Collects published events so the compensation reporting path can be asserted. */
    private static final class CapturingPublisher implements ReportingEventPublisher {

        private final List<StepEvent> stepEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<ScenarioPhase> scenarioPhases = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(ScenarioEvent event) {
            scenarioPhases.add(event.phase());
        }

        @Override
        public void publish(StepEvent event) {
            stepEvents.add(event);
        }
    }
}
