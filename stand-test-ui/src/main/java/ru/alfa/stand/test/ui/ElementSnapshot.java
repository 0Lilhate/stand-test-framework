package ru.alfa.stand.test.ui;

import java.util.Map;

/**
 * What the driver observed about one element at one moment.
 *
 * <p>Absence is data, not a failure: an element that is not on the page yields {@link #absent()} rather
 * than an exception. Otherwise {@code assertVisible(false)} — "this must not be there" — could only be
 * written as exception handling, and the await engine's exception tolerance would start masking genuine
 * driver breakage.
 *
 * @param present whether the element exists in the DOM
 * @param visible whether it is visible
 * @param enabled whether it is enabled
 * @param text its text content, or null when absent
 * @param value the value of an input element, or null
 * @param attributes the attributes the driver was asked to read
 */
public record ElementSnapshot(boolean present, boolean visible, boolean enabled, String text, String value,
        Map<String, String> attributes) {

    /**
     * Defensively copies the attribute map.
     */
    public ElementSnapshot {
        attributes = (attributes == null) ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * The snapshot of an element that is not on the page.
     *
     * @return an absent snapshot
     */
    public static ElementSnapshot absent() {
        return new ElementSnapshot(false, false, false, null, null, Map.of());
    }

    /**
     * The value of one attribute, or null when the driver did not read it.
     *
     * @param name the attribute name
     * @return the attribute value or null
     */
    public String attribute(String name) {
        return this.attributes.get(name);
    }
}
