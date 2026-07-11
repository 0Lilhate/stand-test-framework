package ru.alfa.stand.test.core.compensation;

/**
 * An opaque, per-run compensating action registered into the {@link UndoLog} by an adapter and applied
 * by the runner in its {@code finally}. Core knows nothing of what the action undoes (DB row, message,
 * …) — the DB adapter implements it as a delete/restore by primary key over the run-scoped connection.
 *
 * <p><strong>Contract: {@link #compensate()} must never throw.</strong> It folds any
 * infrastructure/SQL error into a {@link CompensationStatus#FAILED} {@link CompensationOutcome} so the
 * runner's drain loop can attempt every remaining compensation (best-effort per action) and aggregate
 * failures without an escaping exception skipping the resource-close / FINISHED tail.
 */
public interface Compensator {

    /**
     * @return the stable action id, reused as the reporting step id (e.g. the write step id)
     */
    String actionId();

    /**
     * @return a free-form label for grouping and reporting; the DB adapter passes the datasource alias
     */
    String target();

    /**
     * Applies the compensation. Must not throw — errors are returned as a
     * {@link CompensationStatus#FAILED} outcome.
     *
     * @return the outcome of this compensation
     */
    CompensationOutcome compensate();
}
