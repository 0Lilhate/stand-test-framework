package ru.alfa.stand.test.ui;

/**
 * The element property a {@link UiAssertion} is made about.
 *
 * <p>Matcher support is deliberately asymmetric and is declared here rather than left to be discovered:
 * the string-valued properties accept the full core matcher set, while the boolean ones accept only
 * {@code EQUALS}. {@code CONTAINS} over a boolean has no meaning, and silently accepting it would make
 * a nonsense assertion look like a passing one. The restriction is enforced twice — when the step is
 * built and again when it is executed.
 *
 * <p>This mirrors the SDK's other declared asymmetry (Kafka assertions are equals-only while REST and
 * gRPC carry all five matchers): an undeclared asymmetry has already been a source of confusion in this
 * project.
 */
public enum UiProperty {

    /** The element's text content. String-valued: all five core matchers apply. */
    TEXT(false),

    /** The value of an input element. String-valued: all five core matchers apply. */
    VALUE(false),

    /** A named attribute of the element. String-valued: all five core matchers apply. */
    ATTRIBUTE(false),

    /** Whether the element is visible. Boolean: EQUALS only. */
    VISIBLE(true),

    /** Whether the element is enabled. Boolean: EQUALS only. */
    ENABLED(true);

    private final boolean booleanValued;

    UiProperty(boolean booleanValued) {
        this.booleanValued = booleanValued;
    }

    /**
     * Whether the property carries a boolean and therefore accepts only the EQUALS matcher.
     *
     * @return true for {@link #VISIBLE} and {@link #ENABLED}
     */
    public boolean booleanValued() {
        return this.booleanValued;
    }
}
