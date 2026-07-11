package ru.alfa.stand.test.core.compensation;

/**
 * Governs whether the per-run {@link UndoLog} is drained (test-data compensation applied) after a
 * scenario finishes. Attached to a scenario ({@code Scenario.cleanupPolicy}) and read by the runner in
 * its {@code finally}.
 *
 * <p><strong>Capture is always-on.</strong> Adapters register {@link Compensator}s into the
 * {@link UndoLog} at execution time regardless of this policy; the policy gates only the
 * <em>application</em> of those compensations, never their capture. This keeps
 * {@link #ON_FAILURE} correct — the before-state is already captured by the time a failure occurs.
 */
public enum CleanupPolicy {

    /**
     * Compensate only when the run failed (a step threw). The default: on a green run the prepared data
     * is left in place (visible for inspection), on a red run every registered compensation is applied.
     */
    ON_FAILURE,

    /** Compensate on every run, success or failure. */
    ALWAYS,

    /** Never compensate. Capture still runs; the registered compensations are discarded. */
    NEVER;

    /**
     * Decides whether the {@link UndoLog} should be drained given the run's outcome.
     *
     * @param runFailed {@code true} if a step failed (the run is in a failed state)
     * @return {@code true} if compensation should be applied
     */
    public boolean shouldCompensate(boolean runFailed) {
        return switch (this) {
            case ALWAYS -> true;
            case NEVER -> false;
            case ON_FAILURE -> runFailed;
        };
    }
}
