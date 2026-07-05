package ru.alfa.stand.test.core.assertion;

/**
 * How an assertion's expected value is compared with the value observed at a JSONPath. This is the
 * runtime counterpart of the AI schema's assertion grammar ({@code equals} / {@code contains} /
 * {@code exists} / {@code notNull} / {@code matches}); the wire key
 * {@code StepParameterKeys.MATCHER} carries the enum name, and an absent key means {@link #EQUALS}.
 */
public enum AssertionMatcher {

    /** Type-aware equality: {@code Objects.equals} plus numeric comparison by value. */
    EQUALS,

    /** A String value contains the expected substring; a List value contains an equal element. */
    CONTAINS,

    /** The path is present ({@code true}) or absent ({@code false}); a JSON null counts as present. */
    EXISTS,

    /** The present value is non-null ({@code true}) or null ({@code false}); an absent path fails both. */
    NOT_NULL,

    /** The String value fully matches the expected regular expression. */
    MATCHES
}
