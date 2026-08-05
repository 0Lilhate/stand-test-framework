package ru.alfa.stand.test.ui;

import java.util.Objects;

/**
 * Reads a value off the page into the run's variable store, from where every later step of any adapter
 * sees it as {@code ${variableName}}.
 *
 * <p>This is the whole of the SDK's UI-to-backend data binding: it needs no new machinery in the core
 * model, only the existing per-run variable store.
 *
 * @param variableName the variable to store the value under
 * @param locator the element to read
 * @param source which part of the element is read
 * @param attribute the attribute name, required when the source is {@link UiCaptureSource#ATTRIBUTE}, null otherwise
 */
public record UiCapture(String variableName, UiLocator locator, UiCaptureSource source, String attribute) {

    /**
     * Validates the capture.
     */
    public UiCapture {
        if (variableName == null || variableName.isBlank()) {
            throw new IllegalArgumentException("capture variableName must not be blank");
        }
        Objects.requireNonNull(locator, "capture locator must not be null");
        Objects.requireNonNull(source, "capture source must not be null");
        if (source == UiCaptureSource.ATTRIBUTE) {
            if (attribute == null || attribute.isBlank()) {
                throw new IllegalArgumentException("an ATTRIBUTE capture requires an attribute name");
            }
        } else if (attribute != null) {
            throw new IllegalArgumentException("an attribute name is only meaningful for an ATTRIBUTE capture, but source was " + source);
        }
    }

    /**
     * Creates a capture of the element's text.
     *
     * @param variableName the variable to store the value under
     * @param locator the element to read
     */
    public UiCapture(String variableName, UiLocator locator) {
        this(variableName, locator, UiCaptureSource.TEXT, null);
    }

    /**
     * Creates a capture from a non-attribute source.
     *
     * @param variableName the variable to store the value under
     * @param locator the element to read
     * @param source which part of the element is read
     */
    public UiCapture(String variableName, UiLocator locator, UiCaptureSource source) {
        this(variableName, locator, source, null);
    }
}
