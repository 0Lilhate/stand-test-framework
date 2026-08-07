package ru.alfa.stand.test.core.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.compensation.CleanupPolicy;
import ru.alfa.stand.test.core.compensation.CompensationOutcome;
import ru.alfa.stand.test.core.compensation.CompensationReport;
import ru.alfa.stand.test.core.compensation.Compensator;
import ru.alfa.stand.test.core.compensation.UndoLog;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.FailureAttachments;
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
 * problem) becomes a {@link StandTestException}. A thrown {@link AssertionError} is re-raised as a
 * {@link StandTestAssertionError} (recorded {@link StepStatus#FAILED}); a thrown or unexpected runtime
 * failure is raised as a {@link StandTestException} (recorded {@link StepStatus#BROKEN}, so a reporting
 * consumer can tell an unmet assertion from an infrastructure problem). In every case the runner
 * annotates the failing step's context — {@code Step [index/total] 'id' (type)} — onto the thrown
 * message and, via SLF4J {@code MDC} + log lines, into the logs (plan §17), so "which step failed and
 * why" is visible without a reporting adapter; the original failure is kept as the cause. A successful
 * run returns a {@link StepStatus#SUCCESS SUCCESS} {@link ScenarioResult}. (One documented exception: a
 * {@link StandTestException} already thrown by a step's {@code prepare} phase — an adapter's own arming
 * failure — is propagated unwrapped so its precise classification survives, but is still logged with the
 * step context.)
 *
 * <p>Lifecycle reporting events ({@link ScenarioEvent}/{@link StepEvent}) are published throughout, so
 * a reporting adapter (Allure) sees the full run: every attempted step emits STARTED and
 * FINISHED events (including the no-executor and executor-thrown failure paths) with per-step
 * diagnostics. Publishing is best-effort — a throwing publisher is swallowed (logged at WARN) and never
 * changes the test outcome (a pre-run validation failure is rejected before the run starts and so emits
 * no events).
 *
 * <p><strong>Correlation in logs.</strong> Each run stamps {@code scenarioId}/{@code testRunId}/
 * {@code correlationId}/{@code environment} into the {@code MDC} for the whole run and
 * {@code stepId}/{@code stepType}/{@code stepIndex} for the duration of each step (see {@link MdcScope}),
 * so every log line emitted while a step runs carries the correlation context.
 */
public final class DefaultScenarioRunner implements ScenarioRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultScenarioRunner.class);

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

        List<ScenarioStep> steps = scenario.steps();
        int total = steps.size();
        Instant startedAt = clock.instant();
        List<StepResult> stepResults = new ArrayList<>();
        // primary is a LOCAL — never an instance field: this runner is a shared singleton invoked
        // concurrently, so per-run failure state must stay thread-confined (parallel isolation, plan §8.2).
        Throwable primary = null;
        // The whole run is wrapped in an MDC scope so every log line — the SDK's, the adapters', and the
        // system-under-test client's on this thread — carries scenarioId/testRunId/correlationId (plan §17).
        // MdcScope restores the prior MDC on close, keeping concurrent runs isolated.
        try (MdcScope scenarioScope = MdcScope.of(scenarioMdc(context))) {
            publishScenario(context, ScenarioPhase.STARTED);
            LOG.info("Scenario '{}' started: {} step(s), env={}", context.scenarioId(), total, context.environment());
            try {
                prepareSteps(scenario, total, executionContext, context, stepResults);
                for (int index = 0; index < total; index++) {
                    ScenarioStep step = steps.get(index);
                    StepResult result = executeStep(step, index + 1, total, executionContext, context, stepResults);
                    if (result.status() == StepStatus.BROKEN) {
                        throw new StandTestException(failureMessage(index + 1, total, step, result));
                    }
                    if (result.status().isFailure()) {
                        throw new StandTestAssertionError(failureMessage(index + 1, total, step, result));
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
                logScenarioFinished(context, primary != null || report.hasFailures(), primary, startedAt);
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
            int total,
            StepExecutionContext executionContext,
            ScenarioContext context,
            List<StepResult> stepResults) {
        List<ScenarioStep> steps = scenario.steps();
        for (int index = 0; index < total; index++) {
            ScenarioStep step = steps.get(index);
            StepExecutor executor = findExecutor(step.type());
            if (executor == null) {
                continue;
            }
            try (MdcScope stepScope = MdcScope.of(stepMdc(step, index + 1))) {
                Instant start = clock.instant();
                try {
                    executor.prepare(step, executionContext);
                } catch (StandTestException alreadyClassified) {
                    recordPrepareFailure(step, start, context, stepResults, alreadyClassified);
                    // Already classified by the adapter — propagated unwrapped so its precise diagnosis
                    // survives; the step context still reaches the operator through this log line.
                    LOG.error("{} failed to prepare: {}", stepLabel(index + 1, total, step), safeMessage(alreadyClassified), alreadyClassified);
                    throw alreadyClassified;
                } catch (RuntimeException unexpected) {
                    recordPrepareFailure(step, start, context, stepResults, unexpected);
                    String message = stepLabel(index + 1, total, step) + " failed to prepare";
                    LOG.error("{}", message, unexpected);
                    throw new StandTestException(message, unexpected);
                }
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
            // from a resource's close() cannot alter the outcome either — but it is logged at WARN (plan §17).
            LOG.warn("Failed to close run-scoped resources (best-effort, ignored)", closeFailure);
        }
    }

    private StepResult executeStep(
            ScenarioStep step,
            int index,
            int total,
            StepExecutionContext executionContext,
            ScenarioContext context,
            List<StepResult> stepResults) {
        try (MdcScope stepScope = MdcScope.of(stepMdc(step, index))) {
            publishStep(context, step, StepPhase.STARTED, null, null, Map.of(), List.of());
            LOG.debug("{} starting", stepLabel(index, total, step));
            Instant start = clock.instant();
            StepResult result;
            try {
                StepExecutor executor = resolveExecutor(step);
                result = executor.execute(step, executionContext);
                Objects.requireNonNull(result, "step executor returned a null result for step '" + step.id() + "'");
            } catch (AssertionError assertionFailure) {
                recordFailure(step, start, context, stepResults, StepStatus.FAILED, assertionFailure);
                String message = stepLabel(index, total, step) + " FAILED: " + safeMessage(assertionFailure);
                LOG.warn("{}", message);
                throw new StandTestAssertionError(message, assertionFailure);
            } catch (StandTestException infraFailure) {
                recordFailure(step, start, context, stepResults, StepStatus.BROKEN, infraFailure);
                String message = stepLabel(index, total, step) + " BROKEN: " + safeMessage(infraFailure);
                LOG.error("{}", message, infraFailure);
                throw new StandTestException(message, infraFailure);
            } catch (RuntimeException unexpected) {
                recordFailure(step, start, context, stepResults, StepStatus.BROKEN, unexpected);
                String message = stepLabel(index, total, step) + " failed unexpectedly";
                LOG.error("{}", message, unexpected);
                throw new StandTestException(message, unexpected);
            }
            // The executor returned normally. Recording and the FINISHED event happen OUTSIDE the try above
            // so that a failure of the (best-effort) reporting publisher can never reclassify a passing step
            // as failed or add a duplicate StepResult (plan §17: reporting is a side-channel).
            stepResults.add(result);
            publishStep(context, step, StepPhase.FINISHED, result.status(), result.errorMessage(), result.diagnostics(), result.attachments());
            logStepOutcome(index, total, step, result);
            return result;
        }
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
        String message = safeMessage(cause);
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("exception.class", cause.getClass().getName());
        if (cause instanceof FailureAttachments withEvidence) {
            // A failing step can add its own picture of what happened (masked zones, element state) without
            // reaching for the runner; the runner is transport-agnostic and must not invent a vocabulary.
            Map<String, Object> supplied = withEvidence.failureDiagnostics();
            if (supplied == null) {
                warnNullEvidence(cause, "failureDiagnostics()");
            } else {
                diagnostics.putAll(supplied);
            }
        }
        List<Attachment> attachments = failureAttachments(cause);
        StepResult failed = new StepResult(step.id(), step.type(), status, start, clock.instant(), message, diagnostics, attachments);
        stepResults.add(failed);
        publishStep(context, step, StepPhase.FINISHED, failed.status(), failed.errorMessage(), failed.diagnostics(), failed.attachments());
    }

    /**
     * Reads the evidence a failing step opted to carry ({@link FailureAttachments}): a screenshot and
     * console log on a broken UI step must reach the report rather than die with the thrown failure.
     * A failure that does not implement the marker carries nothing — the pre-existing behaviour.
     *
     * @param cause the thrown failure of the step
     * @return the attachments the failure opted in to carry, or an empty list
     */
    private static List<Attachment> failureAttachments(Throwable cause) {
        if (cause instanceof FailureAttachments withEvidence) {
            List<Attachment> supplied = withEvidence.failureAttachments();
            if (supplied == null) {
                warnNullEvidence(cause, "failureAttachments()");
                return List.of();
            }
            return supplied;
        }
        return List.of();
    }

    /**
     * Reports an implementation of {@link FailureAttachments} that broke the marker's "empty, never null"
     * contract, and keeps going.
     *
     * <p>This is deliberately not an exception. The marker is read while the runner is recording a step
     * that ALREADY failed, so throwing here would replace the run's real reason for failing — the assertion
     * the test was about — with a failure of the reporting branch. The same rule the artefact lane follows
     * everywhere (UITG-S013: a screenshot that cannot be taken is a WARN, never a substituted failure): a
     * misbehaving adopter loses its evidence, never the run its diagnosis.
     *
     * @param cause the failure that implements the marker
     * @param method the marker method that returned null
     */
    private static void warnNullEvidence(Throwable cause, String method) {
        LOG.warn("{} returned null from {}: the contract of FailureAttachments is 'empty, never null'. "
                + "The evidence is dropped and the step's own failure is kept.", cause.getClass().getName(), method);
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
        } catch (Throwable reportingFailure) {
            // Reporting is a best-effort side-channel (plan §17): a publisher failure must never change
            // the test outcome. Throwable (not just RuntimeException) is swallowed — exactly as
            // closeQuietly does — so an Error from a version-skewed reporting sink (e.g. a LinkageError /
            // NoClassDefFoundError from a mismatched allure-model on the consumer classpath) cannot escape
            // and replace the primary test failure the runner is about to throw. Logged at WARN.
            LOG.warn("Reporting publisher failed for a scenario event (best-effort, ignored)", reportingFailure);
        }
    }

    private void publish(StepEvent event) {
        try {
            reportingEventPublisher.publish(event);
        } catch (Throwable reportingFailure) {
            // Reporting is a best-effort side-channel (plan §17): a publisher failure must never change
            // the test outcome. Throwable (not just RuntimeException) is swallowed — exactly as
            // closeQuietly does — so an Error from a version-skewed reporting sink (e.g. a LinkageError /
            // NoClassDefFoundError from a mismatched allure-model on the consumer classpath) cannot escape
            // and replace the primary test failure the runner is about to throw. Logged at WARN.
            LOG.warn("Reporting publisher failed for a step event (best-effort, ignored)", reportingFailure);
        }
    }

    private static String failureMessage(int index, int total, ScenarioStep step, StepResult result) {
        String base = stepLabel(index, total, step) + " " + result.status();
        String message = result.errorMessage();
        return (message == null) ? base : base + ": " + message;
    }

    /**
     * The single human-readable label for a step, used identically in log lines and thrown-exception
     * messages so "which step" reads the same everywhere: {@code Step [index/total] 'id' (type)}.
     */
    private static String stepLabel(int index, int total, ScenarioStep step) {
        return "Step [" + index + "/" + total + "] '" + step.id() + "' (" + step.type() + ")";
    }

    private static String safeMessage(Throwable cause) {
        return (cause.getMessage() == null) ? cause.toString() : cause.getMessage();
    }

    private static Map<String, String> scenarioMdc(ScenarioContext context) {
        return Map.of(
                MdcScope.SCENARIO_ID, context.scenarioId().value(),
                MdcScope.TEST_RUN_ID, context.testRunId().value(),
                MdcScope.CORRELATION_ID, context.correlationId().value(),
                MdcScope.ENVIRONMENT, context.environment());
    }

    private static Map<String, String> stepMdc(ScenarioStep step, int index) {
        return Map.of(
                MdcScope.STEP_ID, step.id(),
                MdcScope.STEP_TYPE, step.type(),
                MdcScope.STEP_INDEX, Integer.toString(index));
    }

    /**
     * Logs the outcome of a step that returned normally: DEBUG on success, WARN for a returned
     * FAILED/TIMEOUT (unmet expectation) and ERROR for a returned BROKEN (infrastructure) result. A step
     * that threw is logged at its catch site instead, so each step logs its outcome exactly once.
     */
    private void logStepOutcome(int index, int total, ScenarioStep step, StepResult result) {
        String label = stepLabel(index, total, step);
        long durationMs = result.duration().toMillis();
        if (!result.status().isFailure()) {
            LOG.debug("{} {} in {} ms", label, result.status(), durationMs);
            return;
        }
        String reason = (result.errorMessage() == null) ? "" : ": " + result.errorMessage();
        String message = label + " " + result.status() + reason;
        if (result.status() == StepStatus.BROKEN) {
            LOG.error("{}", message);
        } else {
            LOG.warn("{}", message);
        }
    }

    private void logScenarioFinished(ScenarioContext context, boolean failed, Throwable primary, Instant startedAt) {
        long durationMs = Duration.between(startedAt, clock.instant()).toMillis();
        if (!failed) {
            LOG.info("Scenario '{}' finished: SUCCESS ({} ms)", context.scenarioId(), durationMs);
        } else if (primary != null) {
            LOG.warn("Scenario '{}' finished: FAILED ({} ms): {}", context.scenarioId(), durationMs, safeMessage(primary));
        } else {
            LOG.warn("Scenario '{}' finished: FAILED ({} ms): test-data compensation failed", context.scenarioId(), durationMs);
        }
    }
}
