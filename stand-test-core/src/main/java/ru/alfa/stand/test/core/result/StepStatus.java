package ru.alfa.stand.test.core.result;

/**
 * Outcome status of a scenario step.
 */
public enum StepStatus {

    /** The step completed and all its assertions held. */
    SUCCESS,

    /** The step completed but an assertion did not hold. */
    FAILED,

    /** The step was not executed. */
    SKIPPED,

    /** The step did not complete within its timeout. */
    TIMEOUT;

    /**
     * Returns whether this status represents a failure outcome (failed or timed out).
     *
     * @return true if this status is {@link #FAILED} or {@link #TIMEOUT}
     */
    public boolean isFailure() {
        return this == FAILED || this == TIMEOUT;
    }
}
