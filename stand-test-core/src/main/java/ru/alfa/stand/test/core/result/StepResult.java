package ru.alfa.stand.test.core.result;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.Diagnostics;

/**
 * Immutable outcome of a single scenario step.
 *
 * <p>The {@code diagnostics} map and {@code attachments} list are defensively copied and exposed as
 * immutable. {@code errorMessage} may be null when the step succeeded. A {@code FAILED}/{@code BROKEN}/
 * {@code TIMEOUT} status is a reporting record and never a silent substitute for raising a JUnit
 * failure. {@code attachments} (plan §8.9) carries transport-agnostic evidence (already redacted by the
 * producing adapter) that flows into the reporting {@link ru.alfa.stand.test.core.event.StepEvent}.
 *
 * @param stepId the step id
 * @param stepType the step type
 * @param status the outcome status
 * @param startedAt when the step started
 * @param finishedAt when the step finished
 * @param errorMessage an optional error message (may be null)
 * @param diagnostics an immutable map of diagnostic values
 * @param attachments an immutable list of reporting attachments
 */
public record StepResult(
        String stepId,
        String stepType,
        StepStatus status,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        Map<String, Object> diagnostics,
        List<Attachment> attachments) {

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
        diagnostics = Diagnostics.immutable(diagnostics);
        attachments = (attachments == null) ? List.of() : List.copyOf(attachments);
    }

    /**
     * Backwards-compatible constructor without attachments (defaults to an empty list). Existing
     * adapters and tests that build a {@link StepResult} with diagnostics but no attachments keep
     * compiling unchanged.
     *
     * @param stepId the step id
     * @param stepType the step type
     * @param status the outcome status
     * @param startedAt when the step started
     * @param finishedAt when the step finished
     * @param errorMessage an optional error message (may be null)
     * @param diagnostics an immutable map of diagnostic values
     */
    public StepResult(
            String stepId,
            String stepType,
            StepStatus status,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            Map<String, Object> diagnostics) {
        this(stepId, stepType, status, startedAt, finishedAt, errorMessage, diagnostics, List.of());
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
     * Creates a failed result (an assertion did not hold).
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
     * Creates a broken result (an infrastructure or configuration problem prevented evaluation).
     *
     * @param stepId the step id
     * @param stepType the step type
     * @param startedAt when the step started
     * @param finishedAt when the step finished
     * @param errorMessage the failure message
     * @return a broken step result
     */
    public static StepResult broken(String stepId, String stepType, Instant startedAt, Instant finishedAt, String errorMessage) {
        return new StepResult(stepId, stepType, StepStatus.BROKEN, startedAt, finishedAt, errorMessage, Map.of());
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
