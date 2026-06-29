package ru.alfa.stand.test.core.result;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable outcome of a single scenario step.
 *
 * <p>The {@code diagnostics} map is defensively copied and exposed as immutable. {@code errorMessage}
 * may be null when the step succeeded. A {@code FAILED}/{@code TIMEOUT} status is a reporting record
 * and never a silent substitute for raising a JUnit failure.
 *
 * @param stepId the step id
 * @param stepType the step type
 * @param status the outcome status
 * @param startedAt when the step started
 * @param finishedAt when the step finished
 * @param errorMessage an optional error message (may be null)
 * @param diagnostics an immutable map of diagnostic values
 */
public record StepResult(
        String stepId,
        String stepType,
        StepStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        Map<String, Object> diagnostics) {

    public StepResult {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must not be blank");
        }
        if (stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("stepType must not be blank");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        diagnostics = (diagnostics == null) ? Map.of() : Map.copyOf(diagnostics);
    }

    /**
     * Returns the wall-clock duration between start and finish.
     *
     * @return the step duration
     */
    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    /**
     * Creates a successful result.
     *
     * @param stepId the step id
     * @param stepType the step type
     * @param startedAt when the step started
     * @param finishedAt when the step finished
     * @return a successful step result
     */
    public static StepResult success(String stepId, String stepType, Instant startedAt, Instant finishedAt) {
        return new StepResult(stepId, stepType, StepStatus.SUCCESS, startedAt, finishedAt, null, Map.of());
    }

    /**
     * Creates a failed result.
     *
     * @param stepId the step id
     * @param stepType the step type
     * @param startedAt when the step started
     * @param finishedAt when the step finished
     * @param errorMessage the failure message
     * @return a failed step result
     */
    public static StepResult failed(String stepId, String stepType, Instant startedAt, Instant finishedAt, String errorMessage) {
        return new StepResult(stepId, stepType, StepStatus.FAILED, startedAt, finishedAt, errorMessage, Map.of());
    }

    /**
     * Creates a timed-out result.
     *
     * @param stepId the step id
     * @param stepType the step type
     * @param startedAt when the step started
     * @param finishedAt when the step finished
     * @param errorMessage the timeout message
     * @return a timed-out step result
     */
    public static StepResult timeout(String stepId, String stepType, Instant startedAt, Instant finishedAt, String errorMessage) {
        return new StepResult(stepId, stepType, StepStatus.TIMEOUT, startedAt, finishedAt, errorMessage, Map.of());
    }

    /**
     * Creates a skipped result.
     *
     * @param stepId the step id
     * @param stepType the step type
     * @param startedAt when the step was evaluated
     * @param finishedAt when evaluation finished
     * @return a skipped step result
     */
    public static StepResult skipped(String stepId, String stepType, Instant startedAt, Instant finishedAt) {
        return new StepResult(stepId, stepType, StepStatus.SKIPPED, startedAt, finishedAt, null, Map.of());
    }
}
