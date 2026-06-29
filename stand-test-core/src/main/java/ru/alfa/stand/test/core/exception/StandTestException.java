package ru.alfa.stand.test.core.exception;

/**
 * Unchecked exception for infrastructure and configuration failures in the stand-test SDK.
 *
 * <p>Use this for problems such as a missing required variable, an unresolved alias or an invalid
 * scenario. Assertion failures must use {@link StandTestAssertionError} instead, so that JUnit treats
 * them as a failed test rather than an error.
 */
public class StandTestException extends RuntimeException {

    /**
     * Creates an exception with the given message.
     *
     * @param message the detail message
     */
    public StandTestException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public StandTestException(String message, Throwable cause) {
        super(message, cause);
    }
}
