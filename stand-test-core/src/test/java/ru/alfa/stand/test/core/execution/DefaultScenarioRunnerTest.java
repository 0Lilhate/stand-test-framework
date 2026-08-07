package ru.alfa.stand.test.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.FailureAttachments;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEvent;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.ScenarioPhase;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

class DefaultScenarioRunnerTest {

    private static Scenario scenario(ScenarioStep... steps) {
        Scenario.Builder builder = Scenario.builder("example-flow").environment("ift");
        for (ScenarioStep step : steps) {
            builder.step(step);
        }
        return builder.build();
    }

    private static DefaultScenarioRunner runner(StepExecutor... executors) {
        return new DefaultScenarioRunner(
                List.of(executors), new DefaultScenarioValidator(), iftRegistry(), NoOpReportingEventPublisher.INSTANCE);
    }

    private static EnvironmentRegistry iftRegistry() {
        return new InMemoryEnvironmentRegistry(
                Map.of("ift", new EnvironmentDefinition("ift", Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("a successful scenario returns a SUCCESS result with one step result")
    void run_successfulScenario_returnsSuccess() {
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok");

        ScenarioResult result = runner(executor).run(scenario(GenericStep.of("s1", "fake.ok")));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.stepResults()).hasSize(1);
        assertThat(executor.invocations()).isEqualTo(1);
    }

    @Test
    @DisplayName("a step that returns a FAILED status is raised as a StandTestAssertionError")
    void run_stepReturnsFailed_throwsAssertionError() {
        DefaultScenarioRunner runner = runner(FakeStepExecutor.failing("fake.fail", "status was PENDING"));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.fail"))))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("s1")
                .hasMessageContaining("status was PENDING");
    }

    @Test
    @DisplayName("a step that returns a BROKEN status is raised as a StandTestException (infrastructure, not assertion)")
    void run_stepReturnsBroken_throwsInfra() {
        DefaultScenarioRunner runner = runner(new FakeStepExecutor("fake.broken", (step, context) ->
                StepResult.broken(step.id(), step.type(), Instant.now(), Instant.now(), "broker unreachable")));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.broken"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("s1")
                .hasMessageContaining("broker unreachable");
    }

    @Test
    @DisplayName("a step that returns a TIMEOUT status is raised as a StandTestAssertionError (unmet expectation)")
    void run_stepReturnsTimeout_throwsAssertionError() {
        DefaultScenarioRunner runner = runner(new FakeStepExecutor("fake.timeout", (step, context) ->
                StepResult.timeout(step.id(), step.type(), Instant.now(), Instant.now(), "no matching message in 30s")));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.timeout"))))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("s1")
                .hasMessageContaining("no matching message in 30s");
    }

    @Test
    @DisplayName("an assertion error thrown by an executor is re-raised with the failing step's context and the original cause")
    void run_executorThrowsAssertionError_isAnnotatedWithStepContext() {
        StandTestAssertionError thrown = new StandTestAssertionError("boom");
        DefaultScenarioRunner runner = runner(new FakeStepExecutor("fake.throw", (step, context) -> {
            throw thrown;
        }));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.throw"))))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("s1")
                .hasMessageContaining("fake.throw")
                .hasMessageContaining("boom")
                .hasCause(thrown);
    }

    @Test
    @DisplayName("a StandTestException thrown by an executor propagates as infrastructure failure")
    void run_executorThrowsInfra_propagates() {
        DefaultScenarioRunner runner = runner(new FakeStepExecutor("fake.infra", (step, context) -> {
            throw new StandTestException("datasource unreachable");
        }));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.infra"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("datasource unreachable");
    }

    @Test
    @DisplayName("an unexpected runtime exception is wrapped as a StandTestException")
    void run_unexpectedException_isWrapped() {
        IllegalStateException cause = new IllegalStateException("weird");
        DefaultScenarioRunner runner = runner(new FakeStepExecutor("fake.weird", (step, context) -> {
            throw cause;
        }));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.weird"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("failed unexpectedly")
                .hasCause(cause);
    }

    @Test
    @DisplayName("a step type with no registered executor fails with a StandTestException")
    void run_noExecutor_throwsInfra() {
        DefaultScenarioRunner runner = runner(FakeStepExecutor.succeeding("fake.other"));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.missing"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("No step executor")
                .hasMessageContaining("fake.missing");
    }

    @Test
    @DisplayName("an invalid scenario fails validation before any step runs")
    void run_invalidScenario_throwsInfra() {
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok");
        Scenario empty = Scenario.builder("example-flow").environment("ift").build();

        assertThatThrownBy(() -> runner(executor).run(empty))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("validation failed");
        assertThat(executor.invocations()).isZero();
    }

    @Test
    @DisplayName("execution short-circuits: no step after the first failure runs")
    void run_shortCircuits_afterFirstFailure() {
        FakeStepExecutor failing = FakeStepExecutor.failing("fake.fail", "nope");
        FakeStepExecutor next = FakeStepExecutor.succeeding("fake.ok");

        assertThatThrownBy(() -> runner(failing, next)
                .run(scenario(GenericStep.of("s1", "fake.fail"), GenericStep.of("s2", "fake.ok"))))
                .isInstanceOf(StandTestAssertionError.class);
        assertThat(failing.invocations()).isEqualTo(1);
        assertThat(next.invocations()).isZero();
    }

    @Test
    @DisplayName("each run gets a fresh, isolated variable store")
    void run_isolatesVariableStorePerRun() {
        FakeStepExecutor isolating = new FakeStepExecutor("fake.iso", (step, context) -> {
            if (context.variableStore().contains("seen")) {
                return StepResult.failed(step.id(), step.type(), Instant.now(), Instant.now(), "store leaked");
            }
            context.variableStore().put("seen", true);
            return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
        });
        DefaultScenarioRunner runner = runner(isolating);

        assertThat(runner.run(scenario(GenericStep.of("s1", "fake.iso"))).isSuccessful()).isTrue();
        assertThat(runner.run(scenario(GenericStep.of("s1", "fake.iso"))).isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("scenario and step lifecycle events are published in order")
    void run_publishesLifecycleEvents_inOrder() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(FakeStepExecutor.succeeding("fake.ok")),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        runner.run(scenario(GenericStep.of("s1", "fake.ok")));

        List<ReportingEvent> events = recording.events();
        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOfSatisfying(ScenarioEvent.class,
                e -> assertThat(e.phase()).isEqualTo(ScenarioPhase.STARTED));
        assertThat(events.get(1)).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.STARTED);
            assertThat(e.status()).isNull();
        });
        assertThat(events.get(2)).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.status()).isEqualTo(StepStatus.SUCCESS);
        });
        assertThat(events.get(3)).isInstanceOfSatisfying(ScenarioEvent.class,
                e -> assertThat(e.phase()).isEqualTo(ScenarioPhase.FINISHED));
    }

    @Test
    @DisplayName("the scenario FINISHED event and the failed step event are still published on failure")
    void run_publishesFinishedEvents_onFailure() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(FakeStepExecutor.failing("fake.fail", "nope")),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.fail"))))
                .isInstanceOf(StandTestAssertionError.class);

        List<ReportingEvent> events = recording.events();
        assertThat(events.get(events.size() - 1)).isInstanceOfSatisfying(ScenarioEvent.class,
                e -> assertThat(e.phase()).isEqualTo(ScenarioPhase.FINISHED));
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.status()).isEqualTo(StepStatus.FAILED);
        }));
    }

    @Test
    @DisplayName("step result diagnostics flow into the finished step event")
    void run_stepDiagnostics_flowIntoFinishedEvent() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        FakeStepExecutor withDiagnostics = new FakeStepExecutor("fake.diag", (step, context) ->
                new StepResult(step.id(), step.type(), StepStatus.SUCCESS, Instant.now(), Instant.now(),
                        null, Map.of("attempts", 3)));
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(withDiagnostics),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        runner.run(scenario(GenericStep.of("s1", "fake.diag")));

        assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.diagnostics()).containsEntry("attempts", 3);
        }));
    }

    @Test
    @DisplayName("a null result from an executor is reported as an infrastructure failure")
    void run_nullExecutorResult_isWrapped() {
        DefaultScenarioRunner runner = runner(new FakeStepExecutor("fake.null", (step, context) -> null));

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.null"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("failed unexpectedly");
    }

    @Test
    @DisplayName("null scenario and null collaborators are rejected")
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> runner(FakeStepExecutor.succeeding("fake.ok")).run(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultScenarioRunner(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultScenarioRunner(
                List.of(), null, new InMemoryEnvironmentRegistry(Map.of()), NoOpReportingEventPublisher.INSTANCE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("all steps of a multi-step scenario run in order and produce ordered results")
    void run_multiStep_executesAllInOrder() {
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok");

        ScenarioResult result = runner(executor).run(scenario(
                GenericStep.of("s1", "fake.ok"),
                GenericStep.of("s2", "fake.ok"),
                GenericStep.of("s3", "fake.ok")));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).extracting(StepResult::stepId).containsExactly("s1", "s2", "s3");
        assertThat(executor.invocations()).isEqualTo(3);
    }

    @Test
    @DisplayName("the variable store is shared across steps within a single run")
    void run_sharesVariableStore_withinRun() {
        FakeStepExecutor executor = new FakeStepExecutor("fake.var", (step, context) -> {
            if ("s1".equals(step.id())) {
                context.variableStore().put("token", "abc");
                return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
            }
            boolean shared = "abc".equals(context.variableStore().getRequired("token"));
            return shared
                    ? StepResult.success(step.id(), step.type(), Instant.now(), Instant.now())
                    : StepResult.failed(step.id(), step.type(), Instant.now(), Instant.now(), "token mismatch");
        });

        ScenarioResult result = runner(executor)
                .run(scenario(GenericStep.of("s1", "fake.var"), GenericStep.of("s2", "fake.var")));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).hasSize(2);
    }

    @Test
    @DisplayName("a returned TIMEOUT status is raised as a failure but a SKIPPED status is not")
    void run_timeoutThrows_skippedDoesNot() {
        DefaultScenarioRunner timing = runner(new FakeStepExecutor("fake.timeout", (step, context) ->
                StepResult.timeout(step.id(), step.type(), Instant.now(), Instant.now(), "waited too long")));
        assertThatThrownBy(() -> timing.run(scenario(GenericStep.of("s1", "fake.timeout"))))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("TIMEOUT")
                .hasMessageContaining("waited too long");

        DefaultScenarioRunner skipping = runner(new FakeStepExecutor("fake.skip", (step, context) ->
                StepResult.skipped(step.id(), step.type(), Instant.now(), Instant.now())));
        assertThat(skipping.run(scenario(GenericStep.of("s1", "fake.skip"))).isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("the injected clock drives the run timestamps and event times")
    void run_usesInjectedClock() {
        Instant fixed = Instant.parse("2026-06-29T12:00:00Z");
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(FakeStepExecutor.succeeding("fake.ok")),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording,
                Clock.fixed(fixed, ZoneOffset.UTC));

        ScenarioResult result = runner.run(scenario(GenericStep.of("s1", "fake.ok")));

        assertThat(result.startedAt()).isEqualTo(fixed);
        assertThat(result.finishedAt()).isEqualTo(fixed);
        assertThat(result.duration()).isEqualTo(Duration.ZERO);
        assertThat(recording.events().get(0)).isInstanceOfSatisfying(ScenarioEvent.class,
                e -> assertThat(e.timestamp()).isEqualTo(fixed));
    }

    @Test
    @DisplayName("the runner maps scenario fields into the per-run ScenarioContext handed to executors")
    void run_propagatesScenarioContext() {
        AtomicReference<ScenarioContext> captured = new AtomicReference<>();
        FakeStepExecutor capturing = new FakeStepExecutor("fake.ctx", (step, context) -> {
            captured.set(context.scenarioContext());
            return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
        });
        Scenario scenario = Scenario.builder("example-flow").environment("ift").tag("smoke")
                .step(GenericStep.of("s1", "fake.ctx")).build();

        runner(capturing).run(scenario);

        ScenarioContext context = captured.get();
        assertThat(context.scenarioId().value()).isEqualTo("example-flow");
        assertThat(context.environment()).isEqualTo("ift");
        assertThat(context.tags()).containsExactly("smoke");
        assertThat(context.correlationId()).isNotNull();
        assertThat(context.testRunId()).isNotNull();
    }

    @Test
    @DisplayName("a throwing reporting publisher never changes the test outcome or duplicates a result")
    void run_throwingPublisher_doesNotAffectOutcome() {
        ReportingEventPublisher throwing = new ReportingEventPublisher() {
            @Override
            public void publish(ScenarioEvent event) {
                throw new IllegalStateException("reporting down");
            }

            @Override
            public void publish(StepEvent event) {
                throw new IllegalStateException("reporting down");
            }
        };
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(FakeStepExecutor.succeeding("fake.ok")),
                new DefaultScenarioValidator(),
                iftRegistry(),
                throwing);

        ScenarioResult result = runner.run(scenario(GenericStep.of("s1", "fake.ok")));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).hasSize(1);
    }

    @Test
    @DisplayName("a step with no registered executor still emits STARTED and FAILED step events")
    void run_noExecutor_emitsStepEvents() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(FakeStepExecutor.succeeding("fake.other")),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.missing"))))
                .isInstanceOf(StandTestException.class);

        List<ReportingEvent> events = recording.events();
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class,
                e -> assertThat(e.phase()).isEqualTo(StepPhase.STARTED)));
        assertThat(events).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.status()).isEqualTo(StepStatus.BROKEN);
        }));
    }

    @Test
    @DisplayName("an infrastructure failure is recorded as a BROKEN step event carrying the exception class")
    void run_infraFailure_emitsBrokenEventWithExceptionClass() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(new FakeStepExecutor("fake.infra", (step, context) -> {
                    throw new StandTestException("datasource unreachable");
                })),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.infra"))))
                .isInstanceOf(StandTestException.class);

        assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.status()).isEqualTo(StepStatus.BROKEN);
            assertThat(e.message()).isEqualTo("datasource unreachable");
            assertThat(e.diagnostics()).containsEntry("exception.class", StandTestException.class.getName());
        }));
    }

    @Test
    @DisplayName("an assertion failure thrown by an executor is recorded as a FAILED step event")
    void run_assertionFailure_emitsFailedEvent() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(new FakeStepExecutor("fake.throw", (step, context) -> {
                    throw new StandTestAssertionError("boom");
                })),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.throw"))))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.status()).isEqualTo(StepStatus.FAILED);
            assertThat(e.diagnostics()).containsEntry("exception.class", StandTestAssertionError.class.getName());
        }));
    }

    @Test
    @DisplayName("step result attachments flow into the finished step event")
    void run_stepAttachments_flowIntoFinishedEvent() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        FakeStepExecutor withAttachments = new FakeStepExecutor("fake.att", (step, context) ->
                new StepResult(step.id(), step.type(), StepStatus.SUCCESS, Instant.now(), Instant.now(),
                        null, Map.of(), List.of(new Attachment("request", "application/json", "{}"))));
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(withAttachments),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        runner.run(scenario(GenericStep.of("s1", "fake.att")));

        assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.attachments()).extracting(Attachment::name).containsExactly("request");
        }));
    }

    @Test
    @DisplayName("scenario events carry the environment and tags from the run context")
    void run_scenarioEvent_carriesEnvironmentAndTags() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(FakeStepExecutor.succeeding("fake.ok")),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);
        Scenario scenario = Scenario.builder("example-flow").environment("ift").tag("smoke")
                .step(GenericStep.of("s1", "fake.ok")).build();

        runner.run(scenario);

        assertThat(recording.events().get(0)).isInstanceOfSatisfying(ScenarioEvent.class, e -> {
            assertThat(e.environment()).isEqualTo("ift");
            assertThat(e.tags()).containsExactly("smoke");
        });
    }

    @Test
    @DisplayName("a prepare failure is classified as infrastructure, recorded as a BROKEN step with paired events, and no step executes")
    void run_prepareFailure_isClassifiedAndRecorded() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        IllegalStateException cause = new IllegalStateException("broker down");
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok")
                .onPrepare((step, context) -> {
                    throw cause;
                });
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(executor), new DefaultScenarioValidator(), iftRegistry(), recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.ok"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("failed to prepare")
                .hasCause(cause);

        assertThat(executor.invocations()).isZero();
        List<StepEvent> stepEvents = recording.events().stream()
                .filter(StepEvent.class::isInstance).map(StepEvent.class::cast).toList();
        assertThat(stepEvents).hasSize(2);
        assertThat(stepEvents.get(0).phase()).isEqualTo(StepPhase.STARTED);
        assertThat(stepEvents.get(1).phase()).isEqualTo(StepPhase.FINISHED);
        assertThat(stepEvents.get(1).status()).isEqualTo(StepStatus.BROKEN);
        assertThat(stepEvents.get(1).message()).contains("broker down");
    }

    @Test
    @DisplayName("a StandTestException thrown by prepare propagates unwrapped (already classified)")
    void run_prepareInfraFailure_propagatesUnwrapped() {
        StandTestException thrown = new StandTestException("Failed to arm Kafka consumer for topic 'events'");
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok")
                .onPrepare((step, context) -> {
                    throw thrown;
                });

        assertThatThrownBy(() -> runner(executor).run(scenario(GenericStep.of("s1", "fake.ok"))))
                .isSameAs(thrown);
        assertThat(executor.invocations()).isZero();
    }

    @Test
    @DisplayName("prepare runs for every step in declaration order before any step executes")
    void run_prepareRunsForAllSteps_beforeExecution() {
        List<String> events = new java.util.ArrayList<>();
        FakeStepExecutor executor = new FakeStepExecutor("fake.ok", (step, context) -> {
            events.add("execute:" + step.id());
            return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
        }).onPrepare((step, context) -> events.add("prepare:" + step.id()));

        runner(executor).run(scenario(GenericStep.of("s1", "fake.ok"), GenericStep.of("s2", "fake.ok")));

        assertThat(events).containsExactly("prepare:s1", "prepare:s2", "execute:s1", "execute:s2");
        assertThat(executor.prepareInvocations()).isEqualTo(2);
    }

    @Test
    @DisplayName("a resource registered during prepare is closed after a successful run")
    void run_closesResourcesRegisteredInPrepare_onSuccess() {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok")
                .onPrepare((step, context) -> context.resourceScope().register("res", () -> closed.set(true)));

        ScenarioResult result = runner(executor).run(scenario(GenericStep.of("s1", "fake.ok")));

        assertThat(result.isSuccessful()).isTrue();
        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("a resource registered during prepare is closed even when a step fails")
    void run_closesResourcesRegisteredInPrepare_onFailure() {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        FakeStepExecutor executor = FakeStepExecutor.failing("fake.fail", "nope")
                .onPrepare((step, context) -> context.resourceScope().register("res", () -> closed.set(true)));

        assertThatThrownBy(() -> runner(executor).run(scenario(GenericStep.of("s1", "fake.fail"))))
                .isInstanceOf(StandTestAssertionError.class);
        assertThat(closed).isTrue();
    }

    @Test
    @DisplayName("a failing resource close in the finally block does not change a passing outcome")
    void run_faultyResourceClose_doesNotAffectOutcome() {
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.ok")
                .onPrepare((step, context) -> context.resourceScope().register("res", () -> {
                    throw new IllegalStateException("close failed");
                }));

        ScenarioResult result = runner(executor).run(scenario(GenericStep.of("s1", "fake.ok")));

        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("a step type with no executor is skipped by prepare without failing the pre-phase")
    void run_prepareSkipsUnknownStepType() {
        FakeStepExecutor executor = FakeStepExecutor.succeeding("fake.other");

        assertThatThrownBy(() -> runner(executor).run(scenario(GenericStep.of("s1", "fake.missing"))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("No step executor");
        assertThat(executor.prepareInvocations()).isZero();
    }

    @Test
    @DisplayName("concurrent runs of one runner instance stay isolated")
    void run_concurrentRuns_areIsolated() {
        FakeStepExecutor isolating = new FakeStepExecutor("fake.iso", (step, context) -> {
            if (context.variableStore().contains("seen")) {
                return StepResult.failed(step.id(), step.type(), Instant.now(), Instant.now(), "store leaked");
            }
            context.variableStore().put("seen", true);
            return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
        });
        DefaultScenarioRunner runner = runner(isolating);

        assertThatCode(() -> IntStream.range(0, 64).parallel().forEach(index ->
                runner.run(scenario(GenericStep.of("s1", "fake.iso"))))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("concurrent runs of one runner each get a unique testRunId and correlationId")
    void run_concurrentRuns_haveUniqueIdentifiers() {
        Queue<String> testRunIds = new ConcurrentLinkedQueue<>();
        Queue<String> correlationIds = new ConcurrentLinkedQueue<>();
        FakeStepExecutor recording = new FakeStepExecutor("fake.rec", (step, context) -> {
            testRunIds.add(context.scenarioContext().testRunId().value());
            correlationIds.add(context.scenarioContext().correlationId().value());
            return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
        });
        DefaultScenarioRunner runner = runner(recording);

        int runs = 64;
        IntStream.range(0, runs).parallel().forEach(index -> runner.run(scenario(GenericStep.of("s1", "fake.rec"))));

        assertThat(testRunIds).hasSize(runs);
        assertThat(Set.copyOf(testRunIds)).as("every run's testRunId is unique").hasSize(runs);
        assertThat(Set.copyOf(correlationIds)).as("every run's correlationId is unique").hasSize(runs);
    }

    @Test
    @DisplayName("scenario and step identity are stamped into the MDC during a step and restored after the run")
    void run_stampsMdc_duringStep_andRestoresAfter() {
        Map<String, String> seen = new java.util.HashMap<>();
        FakeStepExecutor capturing = new FakeStepExecutor("fake.mdc", (step, context) -> {
            for (String key : List.of("scenarioId", "testRunId", "correlationId", "environment", "stepId", "stepType", "stepIndex")) {
                seen.put(key, MDC.get(key));
            }
            return StepResult.success(step.id(), step.type(), Instant.now(), Instant.now());
        });

        runner(capturing).run(scenario(GenericStep.of("s1", "fake.mdc")));

        assertThat(seen.get("scenarioId")).isEqualTo("example-flow");
        assertThat(seen.get("environment")).isEqualTo("ift");
        assertThat(seen.get("stepId")).isEqualTo("s1");
        assertThat(seen.get("stepType")).isEqualTo("fake.mdc");
        assertThat(seen.get("stepIndex")).isEqualTo("1");
        assertThat(seen.get("testRunId")).isNotBlank();
        assertThat(seen.get("correlationId")).isNotBlank();
        // The run must not leak MDC keys into the calling thread once it returns.
        assertThat(MDC.get("scenarioId")).isNull();
        assertThat(MDC.get("stepId")).isNull();
    }

    @Test
    @DisplayName("a failing step logs a WARN line that names the step and states the reason")
    void run_failingStep_logsStepContextAndReason() {
        Logger runnerLogger = (Logger) LoggerFactory.getLogger(DefaultScenarioRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        try {
            DefaultScenarioRunner runner = runner(FakeStepExecutor.failing("fake.fail", "status was PENDING"));

            assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.fail"))))
                    .isInstanceOf(StandTestAssertionError.class);

            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains("Step [1/1] 's1' (fake.fail)")
                        .contains("status was PENDING");
            });
        } finally {
            runnerLogger.detachAppender(appender);
        }
    }

    // FailureAttachments — the opt-in marker a thrown failure uses to bring its evidence into the report
    // (UITG-T006). Core had no failure implementing it at all, so both the marker's default method and the
    // runner's two branches that read it were unexercised here; the UI adapter covered them at its own end.

    @Test
    @DisplayName("a failure implementing only failureAttachments() carries its evidence and, by the default, adds not one diagnostic key")
    void run_failureCarriesAttachmentsOnly_defaultDiagnosticsAddNothing() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(new FakeStepExecutor("fake.evidence", (step, context) -> {
                    throw new OnlyAttachmentsFailure("element never appeared",
                            List.of(Attachment.of("screenshot-note", "text/plain", "the screen at the moment of failure")));
                })),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.evidence"))))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("element never appeared");

        assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.status()).isEqualTo(StepStatus.FAILED);
            assertThat(e.attachments()).extracting(Attachment::name).containsExactly("screenshot-note");
            // This is the promise the default body makes and nothing pinned: an adopter that only carries
            // attachments gains nothing it did not ask for. Exactly the one key the runner builds itself.
            assertThat(e.diagnostics()).containsOnlyKeys("exception.class");
        }));
    }

    @Test
    @DisplayName("diagnostics an opted-in failure supplies are merged into the runner's own, and its map is never mutated")
    void run_failureDiagnostics_areMergedIntoTheStepEvent() {
        Map<String, Object> supplied = Map.of("ui.masked.zones", 2);
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(new FakeStepExecutor("fake.evidence", (step, context) -> {
                    throw new SuppliedDiagnosticsFailure("element never appeared", supplied);
                })),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.evidence"))))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
            assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
            assertThat(e.diagnostics()).containsEntry("exception.class", SuppliedDiagnosticsFailure.class.getName());
            assertThat(e.diagnostics()).containsEntry("ui.masked.zones", 2);
        }));
        // An immutable map is the natural thing to return (Map.of is what the marker's own default returns),
        // so the runner must merge into its own map rather than add to the caller's.
        assertThat(supplied).containsOnlyKeys("ui.masked.zones");
    }

    @Test
    @DisplayName("a marker implementation that returns null never replaces the step's real failure with a failure of the reporting branch")
    void run_failureEvidenceIsNull_doesNotReplaceTheStepFailure() {
        RecordingReportingEventPublisher recording = new RecordingReportingEventPublisher();
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                List.of(new FakeStepExecutor("fake.evidence", (step, context) -> {
                    throw new NullEvidenceFailure("element never appeared");
                })),
                new DefaultScenarioValidator(),
                iftRegistry(),
                recording);

        Logger runnerLogger = (Logger) LoggerFactory.getLogger(DefaultScenarioRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
        try {
            // The contract of the marker says "empty, never null", but an implementation lives in another
            // team's module. Breaking it must cost that team its evidence — not the run its real reason for
            // failing. Before UITG-T006 this threw NullPointerException out of the reporting branch and the
            // assertion the test was actually about never reached the report at all.
            assertThatThrownBy(() -> runner.run(scenario(GenericStep.of("s1", "fake.evidence"))))
                    .isInstanceOf(StandTestAssertionError.class)
                    .hasMessageContaining("element never appeared")
                    .hasRootCauseInstanceOf(NullEvidenceFailure.class);

            assertThat(recording.events()).anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(StepEvent.class, e -> {
                assertThat(e.phase()).isEqualTo(StepPhase.FINISHED);
                assertThat(e.status()).isEqualTo(StepStatus.FAILED);
                assertThat(e.diagnostics()).containsOnlyKeys("exception.class");
                assertThat(e.attachments()).isEmpty();
            }));
            // Silently dropping the evidence would leave the adopter with no way to learn it broke the
            // contract, so both halves say so and both name the offending class.
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .contains(NullEvidenceFailure.class.getName())
                        .contains("failureDiagnostics()");
            });
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("failureAttachments()"));
        } finally {
            runnerLogger.detachAppender(appender);
        }
    }

    /**
     * The smallest implementation the marker allows: {@code failureAttachments()} and nothing else.
     * Overriding {@code failureDiagnostics()} here "for symmetry" would route around the default body and
     * make the test above vacuous — which is precisely the hole UITG-T006 was filed for.
     */
    private static final class OnlyAttachmentsFailure extends StandTestAssertionError implements FailureAttachments {

        private final List<Attachment> attachments;

        private OnlyAttachmentsFailure(String message, List<Attachment> attachments) {
            super(message);
            this.attachments = List.copyOf(attachments);
        }

        @Override
        public List<Attachment> failureAttachments() {
            return this.attachments;
        }
    }

    /** A failure that has something of its own to say about how it failed. */
    private static final class SuppliedDiagnosticsFailure extends StandTestAssertionError implements FailureAttachments {

        private final Map<String, Object> diagnostics;

        private SuppliedDiagnosticsFailure(String message, Map<String, Object> diagnostics) {
            super(message);
            this.diagnostics = Map.copyOf(diagnostics);
        }

        @Override
        public List<Attachment> failureAttachments() {
            return List.of();
        }

        @Override
        public Map<String, Object> failureDiagnostics() {
            return this.diagnostics;
        }
    }

    /** A misbehaving adopter: the marker says "empty, never null", and this one returns null from both. */
    private static final class NullEvidenceFailure extends StandTestAssertionError implements FailureAttachments {

        private NullEvidenceFailure(String message) {
            super(message);
        }

        @Override
        public List<Attachment> failureAttachments() {
            return null;
        }

        @Override
        public Map<String, Object> failureDiagnostics() {
            return null;
        }
    }
}
