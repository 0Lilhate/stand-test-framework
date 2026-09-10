package ru.alfa.stand.test.core.compensation;

import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.event.Diagnostics;

/**
 * Immutable result of applying one {@link Compensator}. Carries the reporting identity (action id and a
 * free-form target label such as a datasource alias), the {@link CompensationStatus}, the number of
 * affected rows when known ({@code -1} otherwise), a human message, an already-masked diagnostics map and
 * an optional cause.
 *
 * <p>Diagnostics must be pre-masked by the producing adapter — core copies them verbatim into the report
 * and the reporting event, so no secret/parameter value may be placed here unmasked.
 *
 * @param actionId the compensation action id (used as the reporting step id)
 * @param target a free-form label for grouping/reporting (the DB adapter passes the datasource alias)
 * @param status the outcome classification
 * @param affectedRows rows affected by the compensation, or {@code -1} when unknown
 * @param message a human-readable message, or {@code null}
 * @param diagnostics an immutable, already-masked diagnostics map
 * @param cause the failure cause, or {@code null}
 */
public record CompensationOutcome(
        String actionId,
        String target,
        CompensationStatus status,
        long affectedRows,
        String message,
        Map<String, Object> diagnostics,
        Throwable cause) {

    public CompensationOutcome {
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(status, "status must not be null");
        diagnostics = Diagnostics.immutable(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }

    /**
     * @return {@code true} if this outcome is a {@link CompensationStatus#isFailure() failure}
     *     (CONFLICT or FAILED)
     */
    public boolean isFailure() {
        return status.isFailure();
    }

    /**
     * Builds an {@link CompensationStatus#APPLIED APPLIED} outcome.
     *
     * @param actionId the action id
     * @param target the target label
     * @param affectedRows rows affected
     * @param diagnostics masked diagnostics
     * @return the outcome
     */
    public static CompensationOutcome applied(String actionId, String target, long affectedRows, Map<String, Object> diagnostics) {
        return new CompensationOutcome(actionId, target, CompensationStatus.APPLIED, affectedRows, null, diagnostics, null);
    }

    /**
     * Builds a {@link CompensationStatus#SKIPPED SKIPPED} (idempotent no-op) outcome.
     *
     * @param actionId the action id
     * @param target the target label
     * @param message the reason it was a no-op
     * @param diagnostics masked diagnostics
     * @return the outcome
     */
    public static CompensationOutcome skipped(String actionId, String target, String message, Map<String, Object> diagnostics) {
        return new CompensationOutcome(actionId, target, CompensationStatus.SKIPPED, 0L, message, diagnostics, null);
    }

    /**
     * Builds a {@link CompensationStatus#CONFLICT CONFLICT} outcome (target diverged, not overwritten).
     *
     * @param actionId the action id
     * @param target the target label
     * @param message the divergence description
     * @param diagnostics masked diagnostics
     * @return the outcome
     */
    public static CompensationOutcome conflict(String actionId, String target, String message, Map<String, Object> diagnostics) {
        return new CompensationOutcome(actionId, target, CompensationStatus.CONFLICT, -1L, message, diagnostics, null);
    }

    /**
     * Builds a {@link CompensationStatus#FAILED FAILED} outcome.
     *
     * @param actionId the action id
     * @param target the target label
     * @param message the failure message
     * @param cause the failure cause, or {@code null}
     * @param diagnostics masked diagnostics
     * @return the outcome
     */
    public static CompensationOutcome failed(String actionId, String target, String message, Throwable cause,
            Map<String, Object> diagnostics) {
        return new CompensationOutcome(actionId, target, CompensationStatus.FAILED, -1L, message, diagnostics, cause);
    }
}
