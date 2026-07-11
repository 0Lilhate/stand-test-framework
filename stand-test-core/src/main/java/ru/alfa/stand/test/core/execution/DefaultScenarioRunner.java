package ru.alfa.stand.test.core.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.compensation.CleanupPolicy;
import ru.alfa.stand.test.core.compensation.CompensationOutcome;
import ru.alfa.stand.test.core.compensation.CompensationReport;
import ru.alfa.stand.test.core.compensation.Compensator;
import ru.alfa.stand.test.core.compensation.UndoLog;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.Attachment;
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
 * converted into a thrown failure with the same classification as the thrown path:
 * {@link StepStatus#FAILED}/{@link StepStatus#TIMEOUT} (an unmet expectation) become a
 * {@link StandTestAssertionError} and {@link StepStatus#BROKEN} (an infrastructure/configuration
 * problem) becomes a {@link StandTestException}. A thrown {@link AssertionError} propagates
 * as a test failure (recorded {@link StepStatus#FAILED}); a {@link StandTestException} propagates as an
 * infrastructure error and any other runtime exception is wrapped as a {@link StandTestException} (both
 * recorded {@link StepStatus#BROKEN}, so a reporting consumer can tell an unmet assertion from an
 * infrastructure problem). A successful run returns a {@link StepStatus#SUCCESS SUCCESS}
 * {@link ScenarioResult}.
 *
 * <p>Lifecycle reporting events ({@link ScenarioEvent}/{@link StepEvent}) are published throughout, so
 * a reporting adapter (Allure, later) sees the full run: every attempted step emits STARTED and
 * FINISHED events (including the no-executor and executor-thrown failure paths) with per-step
 * diagnostics. Publishing is best-effort — a throwing publisher is swallowed and never changes the test
 * outcome (a pre-run validation failure is rejected before the run starts and so emits no events).
 */
public final class DefaultScenarioRunner implements ScenarioRunner {

    /** Synthetic step type used for the reporting events emitted during the compensation drain. */
    private static final String COMPENSATION_STEP_TYPE = "db.compensate";

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
        validator.validate(scenario, environmentRegistry).throwIfInvalid();

        ScenarioContext context = ScenarioContext.start(scenario.id(), scenario.environment(), scenario.tags(), clock);
        ResourceScope resourceScope = new ResourceScope();
        UndoLog undoLog = new UndoLog();
        StepExecutionContext executionContext = new StepExecutionContext(
                context, new VariableStore(), environmentRegistry, reportingEventPublisher, resourceScope, undoLog);

        Instant startedAt = clock.instant();
        publishScenario(context, ScenarioPhase.STARTED);
        List<StepResult> stepResults = new ArrayList<>();
        // primary is a LOCAL — never an instance field: this runner is a shared singleton invoked
        // concurrently, so per-run failure state must stay thread-confined (parallel isolation, plan §8.2).
        Throwable primary = null;
        try {
            prepareSteps(scenario, executionContext, context, stepResults);
            for (ScenarioStep step : scenario.steps()) {
                StepResult result = executeStep(step, executionContext, context, stepResults);
                if (result.status() == StepStatus.BROKEN) {
                    throw new StandTestException(failureMessage(step, result));
                }
                if (result.status().isFailure()) {
                    throw new StandTestAssertionError(failureMessage(step, result));
                }
            }
        } catch (RuntimeException | Error failure) {
            // Capture the in-flight failure (the step loop throws StandTestException (RuntimeException) or
            // StandTestAssertionError (extends Error)) so the finally can gate ON_FAILURE compensation and
            // attach any cleanup failure as suppressed instead of masking it. Rethrown unchanged.
            primary = failure;
            throw failure;
        } finally {
            // Order is load-bearing: drain compensations while the run-scoped connection is still open,
            // THEN close resources and publish FINISHED, and only as the final act decide whether a
            // compensation failure fails a green run or is suppressed onto the in-flight failure. Never
            // throw before closeQuietly/publish — that would leak the connection and break the report.
            CompensationReport report = drainCompensations(undoLog, scenario.cleanupPolicy(), primary != null, context);
            closeQuietly(resourceScope);
            publishScenario(context, ScenarioPhase.FINISHED);
            if (report.hasFailures()) {
                StandTestException cleanupFailure = compensationFailure(report);
                if (primary == null) {
                    throw cleanupFailure;
                }
                primary.addSuppressed(cleanupFailure);
            }
        }
        return ScenarioResult.from(context, stepResults, startedAt, clock.instant());
    }

    /**
     * Drains the per-run {@link UndoLog} in reverse registration order, gated by the scenario's
     * {@link CleanupPolicy} and the run outcome. Each {@link Compensator#compensate()} is contracted not
     * to throw (it folds errors into a {@link CompensationOutcome}); a defensive {@code catch} converts an
     * escaping runtime exception into a FAILED outcome so one bad action never aborts the rest
     * (best-effort per action). A {@code compensate}/{@code finish} event pair is published for each
     * action. Returns an aggregate report; an empty report when the policy or an empty log skips the drain.
     */
    private CompensationReport drainCompensations(UndoLog undoLog, CleanupPolicy policy, boolean runFailed, ScenarioContext context) {
        if (undoLog.isEmpty() || !policy.shouldCompensate(runFailed)) {
            return CompensationReport.empty();
        }
        List<CompensationOutcome> outcomes = new ArrayList<>();
        for (Compensator compensator : undoLog.inReverseOrder()) {
            publishCompensationStep(context, compensator.actionId(), StepPhase.STARTED, null);
            CompensationOutcome outcome;
            try {
                outcome = compensator.compensate();
                if (outcome == null) {
                    outcome = CompensationOutcome.failed(compensator.actionId(), compensator.target(), "compensator returned a null outcome", null, Map.of());
                }
            } catch (Throwable unexpected) {
                // A Compensator must not throw (it folds errors into a FAILED outcome), but this defensive
                // net catches Throwable — including Error — so a contract-violating compensator or a JVM
                // Error can never escape the drain, skip the closeQuietly/publish(FINISHED) tail, or mask the
                // in-flight failure. The escape is recorded as a FAILED outcome and the drain continues.
                outcome = CompensationOutcome.failed(compensator.actionId(), compensator.target(), unexpected.getMessage(), unexpected, Map.of());
            }
            outcomes.add(outcome);
            publishCompensationStep(context, compensator.actionId(), StepPhase.FINISHED, outcome);
        }
        return new CompensationReport(outcomes);
    }

    /**
     * Aggregates a failed {@link CompensationReport} into one {@link StandTestException}: the first
     * failing outcome's cause is the exception cause, the remaining causes are attached as suppressed, and
     * the message summarises every failed action.
     */
    private static StandTestException compensationFailure(CompensationReport report) {
        List<CompensationOutcome> failures = report.failures();
        StringBuilder message = new StringBuilder("Test-data compensation failed for ").append(failures.size()).append(" action(s):");
        for (CompensationOutcome failure : failures) {
            message.append(" [").append(failure.target()).append('/').append(failure.actionId()).append(": ").append(failure.status());
            if (failure.message() != null) {
                message.append(" - ").append(failure.message());
            }
            message.append(']');
        }
        Throwable firstCause = null;
        for (CompensationOutcome failure : failures) {
            if (failure.cause() != null) {
                firstCause = failure.cause();
                break;
            }
        }
        StandTestException aggregate = (firstCause == null)
                ? new StandTestException(message.toString())
                : new StandTestException(message.toString(), firstCause);
        for (CompensationOutcome failure : failures) {
            if (failure.cause() != null && failure.cause() != firstCause) {
                aggregate.addSuppressed(failure.cause());
            }
        }
        return aggregate;
    }

    private void publishCompensationStep(ScenarioContext context, String actionId, StepPhase phase, CompensationOutcome outcome) {
        StepStatus status = (outcome == null) ? null : mapCompensationStatus(outcome);
        String message = (outcome == null) ? null : outcome.message();
        Map<String, Object> diagnostics;
        if (outcome == null) {
            diagnostics = Map.of();
        } else {
            diagnostics = new HashMap<>(outcome.diagnostics());
            diagnostics.put("compensation.status", outcome.status().name());
            diagnostics.put("compensation.target", outcome.target());
            if (outcome.affectedRows() >= 0) {
                diagnostics.put("compensation.affectedRows", outcome.affectedRows());
            }
            if (outcome.cause() != null) {
                diagnostics.put("exception.class", outcome.cause().getClass().getName());
            }
        }
        publish(new StepEvent(
                context.scenarioId(),
                context.testRunId(),
                context.correlationId(),
                actionId,
                COMPENSATION_STEP_TYPE,
                phase,
                status,
                clock.instant(),
                message,
                diagnostics,
                List.of()));
    }

    private static StepStatus mapCompensationStatus(CompensationOutcome outcome) {
        return switch (outcome.status()) {
            case APPLIED, SKIPPED -> StepStatus.SUCCESS;
            case CONFLICT -> StepStatus.FAILED;
            case FAILED -> StepStatus.BROKEN;
        };
    }

    /**
     * Pre-execution phase (plan §8.7): walks the steps in declaration order and invokes
     * {@link StepExecutor#prepare} on the executor that supports each step, so async-expect resources
     * (a {@code kafka.expect} consumer) are positioned before any step runs. A step whose type has no
     * registered executor is skipped here — the main loop reports it through {@link #resolveExecutor}
     * with full step events, preserving the existing failure path.
     *
     * <p>A prepare failure is an infrastructure/configuration problem (nothing has been asserted yet):
     * it is recorded as a {@link StepStatus#BROKEN} step result with a paired STARTED/FINISHED event
     * (events are emitted only on the failure path, so a successful prepare leaves the reporting
     * stream untouched and the eventual execute-phase STARTED/FINISHED pairing stays balanced) and
     * re-thrown as a {@link StandTestException} — an already-classified {@link StandTestException}
     * (an adapter's own arming failure) propagates unwrapped.
     */
    private void prepareSteps(
            Scenario scenario,
            StepExecutionContext executionContext,
            ScenarioContext context,
            List<StepResult> stepResults) {
        for (ScenarioStep step : scenario.steps()) {
            StepExecutor executor = findExecutor(step.type());
            if (executor == null) {
                continue;
            }
            Instant start = clock.instant();
            try {
                executor.prepare(step, executionContext);
            } catch (StandTestException alreadyClassified) {
                recordPrepareFailure(step, start, context, stepResults, alreadyClassified);
                throw alreadyClassified;
            } catch (RuntimeException unexpected) {
                recordPrepareFailure(step, start, context, stepResults, unexpected);
                throw new StandTestException("Step '" + step.id() + "' (" + step.type() + ") failed to prepare", unexpected);
            }
        }
    }

    private void recordPrepareFailure(
            ScenarioStep step,
            Instant start,
            ScenarioContext context,
            List<StepResult> stepResults,
            Throwable cause) {
        publishStep(context, step, StepPhase.STARTED, null, null, Map.of(), List.of());
        recordFailure(step, start, context, stepResults, StepStatus.BROKEN, cause);
    }

    private void closeQuietly(ResourceScope resourceScope) {
        try {
            resourceScope.closeAll();
        } catch (Throwable closeFailure) {
            // Closing run-scoped resources is best-effort in the finally block: a faulty close must never
            // mask the real test outcome (a thrown step failure), fail an otherwise-passing run, or skip the
            // FINISHED publish that follows. Throwable (not just RuntimeException) is swallowed so an Error
            // from a resource's close() cannot alter the outcome either. Becomes a WARN log once SLF4J is
            // wired (plan §17).
        }
    }

    private StepResult executeStep(
            ScenarioStep step,
            StepExecutionContext executionContext,
            ScenarioContext context,
            List<StepResult> stepResults) {
        publishStep(context, step, StepPhase.STARTED, null, null, Map.of(), List.of());
        Instant start = clock.instant();
        StepResult result;
        try {
            StepExecutor executor = resolveExecutor(step);
            result = executor.execute(step, executionContext);
            Objects.requireNonNull(result, "step executor returned a null result for step '" + step.id() + "'");
        } catch (AssertionError assertionFailure) {
            recordFailure(step, start, context, stepResults, StepStatus.FAILED, assertionFailure);
            throw assertionFailure;
        } catch (StandTestException infraFailure) {
            recordFailure(step, start, context, stepResults, StepStatus.BROKEN, infraFailure);
            throw infraFailure;
        } catch (RuntimeException unexpected) {
            recordFailure(step, start, context, stepResults, StepStatus.BROKEN, unexpected);
            throw new StandTestException("Step '" + step.id() + "' (" + step.type() + ") failed unexpectedly", unexpected);
        }
        // The executor returned normally. Recording and the FINISHED event happen OUTSIDE the try above
        // so that a failure of the (best-effort) reporting publisher can never reclassify a passing step
        // as failed or add a duplicate StepResult (plan §17: reporting is a side-channel).
        stepResults.add(result);
        publishStep(context, step, StepPhase.FINISHED, result.status(), result.errorMessage(), result.diagnostics(), result.attachments());
        return result;
    }

    /**
     * Records a thrown step failure: an {@link AssertionError} maps to {@link StepStatus#FAILED} and a
     * {@link StandTestException}/unexpected runtime exception to {@link StepStatus#BROKEN} (plan §8.3),
     * so a reporting consumer can tell an unmet assertion from an infrastructure problem. The failing
     * step's exception class is carried as a diagnostic for the report; the throw semantics are
     * unchanged — the caller re-throws.
     */
    private void recordFailure(
            ScenarioStep step,
            Instant start,
            ScenarioContext context,
            List<StepResult> stepResults,
            StepStatus status,
            Throwable cause) {
        String message = (cause.getMessage() == null) ? cause.toString() : cause.getMessage();
        Map<String, Object> diagnostics = Map.of("exception.class", cause.getClass().getName());
        StepResult failed = new StepResult(step.id(), step.type(), status, start, clock.instant(), message, diagnostics);
        stepResults.add(failed);
        publishStep(context, step, StepPhase.FINISHED, failed.status(), failed.errorMessage(), failed.diagnostics(), failed.attachments());
    }

    private StepExecutor resolveExecutor(ScenarioStep step) {
        StepExecutor executor = findExecutor(step.type());
        if (executor == null) {
            throw new StandTestException(
                    "No step executor registered for step type '" + step.type() + "' (stepId=" + step.id() + ")");
        }
        return executor;
    }

    private StepExecutor findExecutor(String stepType) {
        for (StepExecutor executor : executors) {
            if (executor.supports(stepType)) {
                return executor;
            }
        }
        return null;
    }

    private void publishScenario(ScenarioContext context, ScenarioPhase phase) {
        publish(new ScenarioEvent(
                context.scenarioId(),
                context.testRunId(),
                context.correlationId(),
                context.environment(),
                context.tags(),
                phase,
                clock.instant()));
    }

    private void publishStep(
            ScenarioContext context,
            ScenarioStep step,
            StepPhase phase,
            StepStatus status,
            String message,
            Map<String, Object> diagnostics,
            List<Attachment> attachments) {
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
                diagnostics,
                attachments));
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
