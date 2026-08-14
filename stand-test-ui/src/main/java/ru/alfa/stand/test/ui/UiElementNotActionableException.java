package ru.alfa.stand.test.ui;

import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Thrown by a {@link UiDriver} when an element never became actionable within the action's timeout —
 * it stayed missing, hidden, disabled or covered.
 *
 * <p>This exists so that the executor can tell one thing apart from everything else the browser can do
 * to a step: "the screen did not offer what the test expected" is a statement about the product and is
 * reported as a failed assertion, while a browser that would not start, a page that would not load or an
 * unknown driver error is infrastructure and is reported as broken. Getting that boundary wrong quietly
 * poisons any flaky-rate measurement, so it is drawn once, here, rather than guessed at each call site.
 */
public class UiElementNotActionableException extends StandTestException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what the driver was asked to do and could not
     */
    public UiElementNotActionableException(String message) {
        super(message);
    }

    /**
     * Creates the exception with the driver's own error as the cause.
     *
     * @param message what the driver was asked to do and could not
     * @param cause the underlying driver error
     */
    public UiElementNotActionableException(String message, Throwable cause) {
        super(message, cause);
    }
}
