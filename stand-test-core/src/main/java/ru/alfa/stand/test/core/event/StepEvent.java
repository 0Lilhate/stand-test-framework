package ru.alfa.stand.test.core.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;
import ru.alfa.stand.test.core.result.StepStatus;

/**
 * Reporting event emitted at a step lifecycle phase.
 *
 * <p>{@code status} may be null (for example during the {@link StepPhase#STARTED} phase) and is set
 * once the step finishes. {@code message} is optional. {@code diagnostics} is a defensively copied,
 * immutable map mirroring {@link ru.alfa.stand.test.core.result.StepResult} diagnostics, so that
 * timeout/await diagnostics can flow into reporting without string-encoding. {@code attachments}
 * (plan §8.9) is a defensively copied, immutable list of transport-agnostic, already-redacted evidence
 * carried on the {@link StepPhase#FINISHED} event for the reporting consumer to render.
 *
 * @param scenarioId the scenario id
 * @param testRunId the run id
 * @param correlationId the SDK-owned correlation id
 * @param stepId the step id
 * @param stepType the step type
 * @param phase the step phase
 * @param status the outcome status (may be null before the step finishes)
 * @param timestamp when the event occurred
 * @param message an optional message (may be null)
 * @param diagnostics an immutable map of diagnostic values
 * @param attachments an immutable list of reporting attachments
 */
public record StepEvent(
        ScenarioId scenarioId,
        TestRunId testRunId,
        CorrelationId correlationId,
        String stepId,
        String stepType,
        StepPhase phase,
        StepStatus status,
        Instant timestamp,
        String message,
        Map<String, Object> diagnostics,
        List<Attachment> attachments) implements ReportingEvent {

    public StepEvent {
        Objects.requireNonNull(scenarioId, "scenarioId must not be null");
        Objects.requireNonNull(testRunId, "testRunId must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must not be blank");
        }
        if (stepType == null || stepType.isBlank()) {
            throw new IllegalArgumentException("stepType must not be blank");
        }
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        diagnostics = (diagnostics == null) ? Map.of() : Map.copyOf(diagnostics);
        attachments = (attachments == null) ? List.of() : List.copyOf(attachments);
    }

    /**
     * Backwards-compatible constructor without attachments (defaults to an empty list), so existing
     * callers and tests that build a {@link StepEvent} with diagnostics but no attachments keep
     * compiling unchanged.
     *
     * @param scenarioId the scenario id
     * @param testRunId the run id
     * @param correlationId the SDK-owned correlation id
     * @param stepId the step id
     * @param stepType the step type
     * @param phase the step phase
     * @param status the outcome status (may be null before the step finishes)
     * @param timestamp when the event occurred
     * @param message an optional message (may be null)
     * @param diagnostics an immutable map of diagnostic values
     */
    public StepEvent(
            ScenarioId scenarioId,
            TestRunId testRunId,
            CorrelationId correlationId,
            String stepId,
            String stepType,
            StepPhase phase,
            StepStatus status,
            Instant timestamp,
            String message,
            Map<String, Object> diagnostics) {
        this(scenarioId, testRunId, correlationId, stepId, stepType, phase, status, timestamp, message, diagnostics, List.of());
    }
}
