package ru.alfa.stand.test.core.environment;

/**
 * A named browser viewport size declared in the environment registry.
 *
 * <p>Viewport is <strong>configuration, not scenario</strong>: the scenario model carries no
 * browser-specific field, so switching a run from a desktop to a mobile viewport changes the registry
 * (or the run parameter selecting a profile), never the test code.
 *
 * @param width the viewport width in CSS pixels (positive)
 * @param height the viewport height in CSS pixels (positive)
 */
public record ViewportProfile(int width, int height) {

    public ViewportProfile {
        if (width <= 0) {
            throw new IllegalArgumentException("viewport width must be positive, but was " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("viewport height must be positive, but was " + height);
        }
    }
}
