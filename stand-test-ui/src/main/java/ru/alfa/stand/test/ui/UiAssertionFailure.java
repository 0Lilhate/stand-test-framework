package ru.alfa.stand.test.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.FailureAttachments;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;

/**
 * A UI step's unmet expectation about the screen, extended with the failure artefacts the run captured.
 *
 * <p>It is still a {@link StandTestAssertionError} — JUnit and Allure keep treating it natively as a
 * failed test — but it can additionally carry, for example, a screenshot taken the moment the assertion
 * did not hold. {@code DefaultScenarioRunner} reads {@link #failureAttachments()} when it records the
 * failure, so the evidence reaches the failing step's report without changing how the runner classifies
 * an assertion failure.
 *
 * <p>Constructed only by the {@code UiStepExecutor}; a scenario author never sees this type.
 */
final class UiAssertionFailure extends StandTestAssertionError implements FailureAttachments {

    /** The reportable key under which the masked-zone count of the failing step lands. */
    static final String DIAGNOSTIC_MASKED_ZONES = "ui.masked.zones";

    private final List<Attachment> attachments;

    private final int maskedZones;

    UiAssertionFailure(String message, Throwable cause, UiEvidence evidence) {
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
        diagnostics.put(DIAGNOSTIC_MASKED_ZONES, this.maskedZones);
        return diagnostics;
    }
}