package ru.alfa.stand.test.core.result;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;

/**
 * Immutable aggregate result of a scenario run.
 *
 * <p>The {@code stepResults} list is defensively copied and exposed as immutable. The overall
 * {@code status} is {@link StepStatus#FAILED} if any step failed or timed out, otherwise
 * {@link StepStatus#SUCCESS}. The SDK-owned {@code correlationId} the run injected outbound is part
 * of the result, so a test can correlate external systems (log search, manual checks) with the run.
 *
 * @param scenarioId the scenario id
 * @param testRunId the run id
 * @param correlationId the SDK-owned correlation id injected outbound during the run
 * @param status the overall status
 * @param stepResults the immutable list of step results
 * @param startedAt when the run started
 * @param finishedAt when the run finished
 */
public record ScenarioResult(
        ScenarioId scenarioId,
        TestRunId testRunId,
        CorrelationId correlationId,
        StepStatus status,
        List<StepResult> stepResults,
        Instant startedAt,
        Instant finishedAt) {

    public ScenarioResult {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(testRunId, "testRunId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        stepResults = (stepResults == null) ? List.of() : List.copyOf(stepResults);
    }

    /**
     * Returns the wall-clock duration between start and finish.
     *
     * @return the run duration
     */
    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    /**
     * Returns whether the overall status is successful.
     *
     * @return true if successful
     */
    public boolean isSuccessful() {
        return status == StepStatus.SUCCESS;
    }

    /**
     * Builds a result from a context and the collected step results, deriving the overall status.
     *
     * <p>The status is {@link StepStatus#FAILED} if any step failed or timed out, otherwise
     * {@link StepStatus#SUCCESS}. An empty or fully-skipped result therefore derives SUCCESS — the
     * runner relies on the validator to reject empty scenarios, so such a result should not occur in
     * practice.
     *
     * @param context the scenario context
     * @param stepResults the step results
     * @param startedAt when the run started
     * @param finishedAt when the run finished
     * @return a new scenario result
     */
    public static ScenarioResult from(
            ScenarioContext context,
            List<StepResult> stepResults,
            Instant startedAt,
            Instant finishedAt) {
        Objects.requireNonNull(context, "context must not be null");
        List<StepResult> copy = (stepResults == null) ? List.of() : List.copyOf(stepResults);
        return new ScenarioResult(
                context.scenarioId(),
                context.testRunId(),
                context.correlationId(),
                deriveStatus(copy),
                copy,
                startedAt,
                finishedAt);
    }

    private static StepStatus deriveStatus(List<StepResult> stepResults) {
        boolean anyFailure = stepResults.stream().anyMatch(result -> result.status().isFailure());
        return anyFailure ? StepStatus.FAILED : StepStatus.SUCCESS;
    }
}
