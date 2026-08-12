package ru.alfa.stand.test.core.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.FailureAttachments;

/**
 * An SDK assertion failure that carries its structured diagnostics into the report.
 *
 * <p>It is still a {@link StandTestAssertionError} — JUnit and Allure treat it natively as a failed
 * test, and the runner classifies it as {@code FAILED} exactly as before — but it also implements
 * {@link FailureAttachments}, so {@code DefaultScenarioRunner} folds the supplied map into the failing
 * step's diagnostics. That is the difference between a report showing
 * {@code attempts=30, elapsed=PT30S, lastValue=PENDING} as key/value rows and a report showing the same
 * facts glued into the middle of one long message string.
 *
 * <p>The intended producer is an adapter whose await expired: it renders
 * {@code TimeoutDiagnostics.summary()} into the message for a human reading the stack trace, and passes
 * {@code TimeoutDiagnostics.toMap()} here for a human reading the report. Nothing restricts it to
 * awaits, though — any failure with more to say than one sentence can use it.
 *
 * <p>It carries no attachments: an adapter with a screenshot to attach has richer needs and subclasses
 * {@link FailureAttachments} itself (as the UI adapter does).
 */
public class DiagnosticAssertionError extends StandTestAssertionError implements FailureAttachments {

    private final Map<String, Object> diagnostics;

    /**
     * Creates a diagnostic assertion failure with no cause.
     *
     * <p>Deliberately {@code super(message)} rather than {@code super(message, null)}: the two-argument
     * form of {@link Throwable} FIXES the cause at null, and the JDK then refuses {@code initCause} for
     * good. {@code AwaitResult.orElseThrow} attaches the probe's last error that way, so spelling it with
     * an explicit null would quietly drop the one thing explaining why every poll failed.
     *
     * @param message the detail message
     * @param diagnostics the reportable diagnostics, folded into the failing step (may be null or empty)
     */
    public DiagnosticAssertionError(String message, Map<String, Object> diagnostics) {
        super(message);
        this.diagnostics = copy(diagnostics);
    }

    /**
     * Creates a diagnostic assertion failure with a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     * @param diagnostics the reportable diagnostics, folded into the failing step (may be null or empty)
     */
    public DiagnosticAssertionError(String message, Throwable cause, Map<String, Object> diagnostics) {
        super(message, cause);
        this.diagnostics = copy(diagnostics);
    }

    @Override
    public List<Attachment> failureAttachments() {
        return List.of();
    }

    @Override
    public Map<String, Object> failureDiagnostics() {
        return this.diagnostics;
    }

    /**
     * Copies defensively while preserving iteration order and tolerating a null value.
     *
     * <p>{@code Map.copyOf} would do neither: it scrambles the order the diagnostics were assembled in —
     * which is the order a report renders them — and throws on a null value. Throwing here would replace
     * the failure being reported with a failure of the reporting branch.
     */
    private static Map<String, Object> copy(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
    }
}
