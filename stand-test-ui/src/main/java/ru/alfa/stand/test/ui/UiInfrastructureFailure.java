package ru.alfa.stand.test.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.FailureAttachments;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * A UI step's infrastructure failure (a browser that would not start, a page that would not load), an
 * unknown alias or any other driver error — extended with the failure artefacts the run captured.
 *
 * <p>It is still a {@link StandTestException}, so the runner records it as {@code BROKEN} rather than
 * {@code FAILED}, but it can additionally carry, for example, a screenshot of the stuck screen.
 * {@code DefaultScenarioRunner} reads {@link #failureAttachments()} when it records the failure.
 *
 * <p>Constructed only by the {@code UiStepExecutor}; a scenario author never sees this type.
 */
final class UiInfrastructureFailure extends StandTestException implements FailureAttachments {

    private final List<Attachment> attachments;

    private final int maskedZones;

    UiInfrastructureFailure(String message, Throwable cause, UiEvidence evidence) {
        super(message, cause);
        this.attachments = evidence.attachments();
        this.maskedZones = evidence.maskedZones();
    }

    @Override
    public List<Attachment> failureAttachments() {
        return this.attachments;
    }

    @Override
    public Map<String, Object> failureDiagnostics() {
        if (this.maskedZones == 0) {
            return Map.of();
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put(UiAssertionFailure.DIAGNOSTIC_MASKED_ZONES, this.maskedZones);
        return diagnostics;
    }
}