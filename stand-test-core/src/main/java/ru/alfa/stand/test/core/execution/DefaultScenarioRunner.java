package ru.alfa.stand.test.core.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.context.ScenarioContext;
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
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.core.validation.ScenarioValidator;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Default {@link ScenarioRunner}: the generic, transport-agnostic execution engine.
 *
 * <p>It depends on no adapter and on no IO library — it validates the scenario, owns a fresh
 * {@link ScenarioContext} and {@link VariableStore} per run (so parallel runs are isolated, plan §8.2)
 * and dispatches each step to the first {@link StepExecutor} that {@link StepExecutor#supports(String)
 * supports} its type. Adapters provide the executors; the runner never performs IO.
 *
 * <p><strong>Failure semantics (plan §8.3).</strong> The runner follows a short-circuit policy: the
 * first failing step stops the run and a failure is thrown rather than hidden in the returned result.
 * An executor may signal a failure either by returning a {@link StepStatus#isFailure() failing}
 * {@link StepResult} or by throwing. A returned failing status is never silently kept — it is
 * converted into a thrown {@link StandTestAssertionError}. A thrown {@link AssertionError} propagates
 * as a test failure; a {@link StandTestException} propagates as an infrastructure error; any other
 * runtime exception is wrapped as a {@link StandTestException}. A successful run returns a
 * {@link StepStatus#SUCCESS SUCCESS} {@link ScenarioResult}.
 *
 * <p>Lifecycle reporting events ({@link ScenarioEvent}/{@link StepEvent}) are published throughout, so
 * a reporting adapter (Allure, later) sees the full run: every attempted step emits STARTED and
 * FINISHED events (including the no-executor and executor-thrown failure paths) with per-step
 * diagnostics. Publishing is best-effort — a throwing publisher is swallowed and never changes the test
 * outcome (a pre-run validation failure is rejected before the run starts and so emits no events).
 */
public final class DefaultScenarioRunner implements ScenarioRunner {

    private final List<StepExecutor> executors;
    private final ScenarioValidator validator;
    private final EnvironmentRegistry environmentRegistry;
    private final ReportingEventPublisher reportingEventPublisher;
    private final Clock clock;

    /**
     * Creates a runner with the given executors and structural defaults: a
     * {@link DefaultScenarioValidator}, an empty {@link InMemoryEnvironmentRegistry}, a
     * {@link NoOpReportingEventPublisher} and the system UTC clock.
     *
     * @param executors the step executors discovered for this run
     */
    public DefaultScenarioRunner(List<StepExecutor> executors) {
        this(
                executors,
                new DefaultScenarioValidator(),
                new InMemoryEnvironmentRegistry(Map.of()),
                NoOpReportingEventPublisher.INSTANCE,
                Clock.systemUTC());
    }

    /**
     * Creates a runner with explicit collaborators and the system UTC clock.
     *
     * @param executors the step executors
     * @param validator the scenario validator
     * @param environmentRegistry the environment registry
     * @param reportingEventPublisher the reporting sink
     */
    public DefaultScenarioRunner(
            List<StepExecutor> executors,
            ScenarioValidator validator,
            EnvironmentRegistry environmentRegistry,
            ReportingEventPublisher reportingEventPublisher) {
        this(executors, validator, environmentRegistry, reportingEventPublisher, Clock.systemUTC());
    }

    /**
     * Creates a runner with explicit collaborators and clock.
     *
     * @param executors the step executors
     * @param validator the scenario validator
     * @param environmentRegistry the environment registry
     * @param reportingEventPublisher the reporting sink
     * @param clock the clock used for run/step timestamps
     */
    public DefaultScenarioRunner(
            List<StepExecutor> executors,
            ScenarioValidator validator,
            EnvironmentRegistry environmentRegistry,
            ReportingEventPublisher reportingEventPublisher,
            Clock clock) {
        this.executors = List.copyOf(Objects.requireNonNull(executors, "executors must not be null"));
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry must not be null");
        this.reportingEventPublisher = Objects.requireNonNull(reportingEventPublisher, "reportingEventPublisher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ScenarioResult run(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario must not be null");
        validator.validate(scenario).throwIfInvalid();

        ScenarioContext context = ScenarioContext.start(scenario.id(), scenario.environment(), scenario.tags());
        StepExecutionContext executionContext = new StepExecutionContext(
                context, new VariableStore(), environmentRegistry, reportingEventPublisher);

        Instant startedAt = clock.instant();
        publishScenario(context, ScenarioPhase.STARTED);
        List<StepResult> stepResults = new ArrayList<>();
        try {
            for (ScenarioStep step : scenario.steps()) {
                StepResult result = executeStep(step, executionContext, context, stepResults);
                if (result.status().isFailure()) {
                    throw new StandTestAssertionError(failureMessage(step, result));
                }
            }
        } finally {
            publishScenario(context, ScenarioPhase.FINISHED);
        }
        return ScenarioResult.from(context, stepResults, startedAt, clock.instant());
    }

    private StepResult executeStep(
            ScenarioStep step,
            StepExecutionContext executionContext,
            ScenarioContext context,
            List<StepResult> stepResults) {
        publishStep(context, step, StepPhase.STARTED, null, null, Map.of());
        Instant start = clock.instant();
        StepResult result;
        try {
            StepExecutor executor = resolveExecutor(step);
            result = executor.execute(step, executionContext);
            Objects.requireNonNull(result, "step executor returned a null result for step '" + step.id() + "'");
        } catch (AssertionError assertionFailure) {
            recordFailure(step, start, context, stepResults, assertionFailure.getMessage());
            throw assertionFailure;
        } catch (StandTestException infraFailure) {
            recordFailure(step, start, context, stepResults, infraFailure.getMessage());
            throw infraFailure;
        } catch (RuntimeException unexpected) {
            recordFailure(step, start, context, stepResults, String.valueOf(unexpected));
            throw new StandTestException("Step '" + step.id() + "' (" + step.type() + ") failed unexpectedly", unexpected);
        }
        // The executor returned normally. Recording and the FINISHED event happen OUTSIDE the try above
        // so that a failure of the (best-effort) reporting publisher can never reclassify a passing step
        // as failed or add a duplicate StepResult (plan §17: reporting is a side-channel).
        stepResults.add(result);
        publishStep(context, step, StepPhase.FINISHED, result.status(), result.errorMessage(), result.diagnostics());
        return result;
    }

    private void recordFailure(
            ScenarioStep step,
            Instant start,
            ScenarioContext context,
            List<StepResult> stepResults,
            String message) {
        StepResult failed = StepResult.failed(step.id(), step.type(), start, clock.instant(), message);
        stepResults.add(failed);
        publishStep(context, step, StepPhase.FINISHED, failed.status(), failed.errorMessage(), Map.of());
    }

    private StepExecutor resolveExecutor(ScenarioStep step) {
        for (StepExecutor executor : executors) {
            if (executor.supports(step.type())) {
                return executor;
            }
        }
        throw new StandTestException(
                "No step executor registered for step type '" + step.type() + "' (stepId=" + step.id() + ")");
    }

    private void publishScenario(ScenarioContext context, ScenarioPhase phase) {
        publish(new ScenarioEvent(
                context.scenarioId(), context.testRunId(), context.correlationId(), phase, clock.instant()));
    }

    private void publishStep(
            ScenarioContext context,
            ScenarioStep step,
            StepPhase phase,
            StepStatus status,
            String message,
            Map<String, Object> diagnostics) {
        publish(new StepEvent(
                context.scenarioId(),
                context.testRunId(),
                context.correlationId(),
                step.id(),
                step.type(),
                phase,
                status,
                clock.instant(),
                message,
                diagnostics));
    }

    private void publish(ScenarioEvent event) {
        try {
            reportingEventPublisher.publish(event);
        } catch (RuntimeException reportingFailure) {
            // Reporting is a best-effort side-channel (plan §17): a publisher failure must never change
            // the test outcome. Swallowed here; becomes a WARN log once SLF4J is wired.
        }
    }

    private void publish(StepEvent event) {
        try {
            reportingEventPublisher.publish(event);
        } catch (RuntimeException reportingFailure) {
            // Reporting is a best-effort side-channel (plan §17): a publisher failure must never change
            // the test outcome. Swallowed here; becomes a WARN log once SLF4J is wired.
        }
    }

    private static String failureMessage(ScenarioStep step, StepResult result) {
        String base = "Step '" + step.id() + "' (" + step.type() + ") " + result.status();
        String message = result.errorMessage();
        return (message == null) ? base : base + ": " + message;
    }
}
