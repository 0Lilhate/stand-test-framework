package ru.alfa.stand.test.core.result;

/**
 * Outcome status of a scenario step.
 *
 * <p>The split between {@link #FAILED} and {@link #BROKEN} mirrors the SDK's failure semantics
 * (plan §8.3): an assertion that did not hold is a {@code FAILED} (the test "is red"), whereas an
 * infrastructure/configuration problem is {@code BROKEN} (the test could not be evaluated). A reporting
 * consumer (the Allure adapter) maps these deterministically:
 * {@code SUCCESS→PASSED}, {@code FAILED→FAILED}, {@code BROKEN→BROKEN}, {@code TIMEOUT→FAILED} (an
 * expired await is an unmet expectation) and {@code SKIPPED→SKIPPED}.
 */
public enum StepStatus {

    /** The step completed and all its assertions held. */
    SUCCESS,

    /** The step completed but an assertion did not hold. */
    FAILED,

    /** The step could not be evaluated because of an infrastructure or configuration problem. */
    BROKEN,

    /** The step was not executed. */
    SKIPPED,

    /** The step did not complete within its timeout. */
    TIMEOUT;

    /**
     * Returns whether this status represents a failure outcome (failed, broken or timed out).
     *
     * @return true if this status is {@link #FAILED}, {@link #BROKEN} or {@link #TIMEOUT}
     */
    public boolean isFailure() {
        return this == FAILED || this == BROKEN || this == TIMEOUT;
    }
}
