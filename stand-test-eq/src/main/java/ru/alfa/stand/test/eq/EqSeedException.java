package ru.alfa.stand.test.eq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.FailureAttachments;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.eq.report.SeedLog;

/**
 * Safe failure of an EQ seed operation: a {@code BROKEN} precondition, not a TKS assertion failure.
 *
 * <p>It implements {@link FailureAttachments} so the step's own operation journal reaches the report even
 * though the runner builds a thrown failure's diagnostics from the cause alone. The snapshot carries only
 * NFR-05 fields (option, sequence, classified status, duration and confirmed identifiers); raw
 * request/response bodies, headers, params, personal names, INN, DUL and credentials are never recorded.
 */
public final class EqSeedException extends StandTestException implements FailureAttachments {

    private final String category;
    private final String operation;
    private final SeedLog log;
    private final Map<String, Object> details;

    /** Creates a failure carrying a safe category, the failing operation and a message. */
    public EqSeedException(String category, String operation, String message) {
        this(category, operation, message, null, Map.of(), null);
    }

    /**
     * Creates a failure that keeps the original transport cause in the chain.
     *
     * <p>NFR-04 and Приложение Г-10 require the original cause to survive: the reference library wrapped
     * {@code RestClientException} without it, which hid whether a failure was a connect reset, a read
     * timeout or a TLS problem. The cause is stored but never rendered into the message or any attachment,
     * so a raw transport detail cannot leak through the {@code BROKEN} diagnostics.
     */
    public EqSeedException(String category, String operation, String message, Throwable cause) {
        this(category, operation, message, null, Map.of(), cause);
    }

    private EqSeedException(String category, String operation, String message, SeedLog log,
                            Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.operation = operation;
        this.log = log;
        this.details = Map.copyOf(details);
    }

    /** The stable failure category, for cross-run comparison (BR-51). */
    public String category() {
        return category;
    }

    /** The failing operation name. */
    public String operation() {
        return operation;
    }

    /**
     * Attaches the immutable per-step journal snapshot.
     *
     * <p>Called by a backend after it knows which writes were confirmed, so a partial chain still tells a
     * reader what was created (BR-23). The message gains the confirmed identifiers; the raw response never
     * does.
     */
    public EqSeedException withLog(SeedLog value) {
        String suffix = value == null ? "" : describe(value);
        return new EqSeedException(category, operation, getMessage() + suffix, value, details, getCause());
    }

    /** Adds bounded, non-sensitive operation metadata to the failure report. */
    public EqSeedException withDiagnostics(Map<String, Object> diagnostics) {
        return new EqSeedException(category, operation, getMessage(), log, diagnostics, getCause());
    }

    @Override
    public List<Attachment> failureAttachments() {
        if (log == null) {
            return List.of();
        }
        return List.of(Attachment.of("eq-seed", "text/plain", log.toText()));
    }

    @Override
    public Map<String, Object> failureDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("eq.failure.category", category);
        diagnostics.put("eq.operation", operation);
        if (log != null) {
            diagnostics.put("eq.confirmed.accounts", log.accounts());
            diagnostics.put("eq.confirmed.deals", log.deals());
            if (log.pin() != null) {
                diagnostics.put("eq.confirmed.pin", log.pin());
            }
        } else {
            diagnostics.put("eq.confirmed.accounts", List.of());
            diagnostics.put("eq.confirmed.deals", 0);
        }
        diagnostics.putAll(details);
        return ru.alfa.stand.test.core.event.Diagnostics.immutable(diagnostics);
    }

    private static String describe(SeedLog value) {
        StringBuilder text = new StringBuilder();
        text.append("; ").append(value.pin() == null ? "no confirmed client" : "confirmed PIN " + value.pin());
        if (!value.accounts().isEmpty()) {
            text.append(", confirmed accounts ").append(value.accounts());
        }
        return text.toString();
    }
}