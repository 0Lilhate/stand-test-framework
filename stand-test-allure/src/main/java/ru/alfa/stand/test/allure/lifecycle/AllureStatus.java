package ru.alfa.stand.test.allure.lifecycle;

/**
 * Transport-neutral mirror of the Allure step/test status.
 *
 * <p>The adapter's mapping layer works against this enum so that mapping logic can be unit-tested
 * without the Allure runtime on the classpath; {@link DefaultAllureLifecycleFacade} translates it to
 * {@code io.qameta.allure.model.Status} at the boundary.
 */
public enum AllureStatus {

    /** The step passed. */
    PASSED,

    /** An assertion did not hold. */
    FAILED,

    /** An infrastructure or configuration problem prevented evaluation. */
    BROKEN,

    /** The step was skipped. */
    SKIPPED
}
