package ru.alfa.stand.test.ui;

import java.util.Objects;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;

/**
 * One expectation about an element property.
 *
 * <p>Evaluation is delegated to the core {@code AssertionMatchers}, exactly as the Kafka and DB adapters
 * delegate their equality; the UI adapter owns no comparison logic of its own, so the five adapters
 * cannot drift apart in what "equals" or "contains" mean.
 *
 * @param property the property under test
 * @param attribute the attribute name, required when the property is {@link UiProperty#ATTRIBUTE}, null otherwise
 * @param expectedValue the expected value; a Boolean for boolean properties and for EXISTS / NOT_NULL
 * @param matcher how expected and actual are compared
 */
public record UiAssertion(UiProperty property, String attribute, Object expectedValue, AssertionMatcher matcher) {

    /**
     * Validates the assertion, including the boolean-property / EQUALS-only rule.
     */
    public UiAssertion {
        Objects.requireNonNull(property, "property must not be null");
        Objects.requireNonNull(matcher, "matcher must not be null");
        Objects.requireNonNull(expectedValue, "expectedValue must not be null");
        if (property == UiProperty.ATTRIBUTE) {
            if (attribute == null || attribute.isBlank()) {
                throw new IllegalArgumentException("an ATTRIBUTE assertion requires an attribute name");
            }
        } else if (attribute != null) {
            throw new IllegalArgumentException("an attribute name is only meaningful for an ATTRIBUTE assertion, but property was " + property);
        }
        if (property.booleanValued()) {
            if (matcher != AssertionMatcher.EQUALS) {
                throw new IllegalArgumentException("property " + property + " is boolean and supports only the EQUALS matcher, but got " + matcher);
            }
            if (!(expectedValue instanceof Boolean)) {
                throw new IllegalArgumentException("property " + property + " is boolean and requires a boolean expected value, but got " + expectedValue.getClass().getSimpleName());
            }
        }
        if ((matcher == AssertionMatcher.EXISTS || matcher == AssertionMatcher.NOT_NULL) && !(expectedValue instanceof Boolean)) {
            throw new IllegalArgumentException("matcher " + matcher + " requires a boolean expected value, but got " + expectedValue.getClass().getSimpleName());
        }
    }

    /**
     * Creates an assertion about a non-attribute property.
     *
     * @param property the property under test
     * @param expectedValue the expected value
     * @param matcher how expected and actual are compared
     */
    public UiAssertion(UiProperty property, Object expectedValue, AssertionMatcher matcher) {
        this(property, null, expectedValue, matcher);
    }

    /**
     * A short rendering used in failure messages, for example {@code TEXT EQUALS <Accepted>}.
     *
     * @return the description
     */
    public String describe() {
        String name = (this.property == UiProperty.ATTRIBUTE) ? this.property + "[" + this.attribute + "]" : this.property.toString();
        return name + " " + this.matcher + " <" + this.expectedValue + ">";
    }
}
