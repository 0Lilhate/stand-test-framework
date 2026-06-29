package ru.alfa.stand.test.core.exception;

/**
 * SDK-level assertion failure.
 *
 * <p>Extends {@link AssertionError} so that JUnit and Allure treat it natively as a failed test. The
 * SDK raises this (rather than silently recording a {@code FAILED} step) whenever a scenario
 * assertion does not hold.
 */
public class StandTestAssertionError extends AssertionError {

    /**
     * Creates an assertion error with the given message.
     *
     * @param message the detail message
     */
    public StandTestAssertionError(String message) {
        super(message);
    }

    /**
     * Creates an assertion error with the given message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public StandTestAssertionError(String message, Throwable cause) {
        super(message, cause);
    }
}
