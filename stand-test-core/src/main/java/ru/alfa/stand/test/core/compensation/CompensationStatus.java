package ru.alfa.stand.test.core.compensation;

/**
 * Outcome classification of a single {@link Compensator} application.
 */
public enum CompensationStatus {

    /** The compensation ran and undid the change (rows affected, or a confirmed no-op undo). */
    APPLIED,

    /** Nothing needed to be done (already undone / target row absent) — an idempotent no-op. */
    SKIPPED,

    /**
     * The target diverged from the state the write left behind, so the compensation was <em>not</em>
     * applied (no blind overwrite). Treated as a failure so a green run fails loudly.
     */
    CONFLICT,

    /** The compensation could not run (infrastructure/SQL error). */
    FAILED;

    /**
     * @return {@code true} for {@link #CONFLICT} and {@link #FAILED} — the states that must fail a green
     *     run and be surfaced as suppressed on a failed run.
     */
    public boolean isFailure() {
        return this == CONFLICT || this == FAILED;
    }
}
