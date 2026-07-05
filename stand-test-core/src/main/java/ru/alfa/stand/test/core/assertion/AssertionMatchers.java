package ru.alfa.stand.test.core.assertion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Evaluates an {@link AssertionMatcher} against an observed value. JDK-only, shared by the adapters
 * (the adapter resolves the JSONPath itself and reports presence via {@code pathPresent} — a
 * {@code PathNotFoundException} maps to {@code pathPresent=false}).
 *
 * <p>Semantics are deliberately type-strict (mirroring the historical equals matching): a matcher
 * never string-coerces the observed value, so a response field changing type is caught as a mismatch
 * rather than masked. The only coercion is numeric — numbers compare by value ({@code 100} matches
 * {@code 100.0}).
 */
public final class AssertionMatchers {

    private AssertionMatchers() {
    }

    /**
     * Evaluates the matcher.
     *
     * @param matcher the matcher to apply (never null)
     * @param expected the expected value from the assertion (Boolean for EXISTS/NOT_NULL, regex String for MATCHES)
     * @param pathPresent whether the JSONPath resolved to a value (a JSON null is present)
     * @param actual the observed value (may be null; meaningless when {@code pathPresent} is false)
     * @return true when the observation satisfies the matcher
     */
    public static boolean matches(AssertionMatcher matcher, Object expected, boolean pathPresent, Object actual) {
        Objects.requireNonNull(matcher, "matcher must not be null");
        return switch (matcher) {
            case EQUALS -> pathPresent && equalsMatch(expected, actual);
            case CONTAINS -> pathPresent && containsMatch(expected, actual);
            case EXISTS -> Boolean.TRUE.equals(expected) == pathPresent;
            case NOT_NULL -> pathPresent && Boolean.TRUE.equals(expected) == (actual != null);
            case MATCHES -> pathPresent && regexMatch(expected, actual);
        };
    }

    /**
     * Type-aware equality: {@code Objects.equals}, plus numbers compared by numeric value so an
     * expected {@code 100} matches a JSON {@code 100.0}; any other type mismatch fails rather than
     * being string-coerced.
     *
     * @param expected the expected value
     * @param actual the observed value
     * @return true when the values are equal
     */
    public static boolean equalsMatch(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            try {
                return new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) == 0;
            } catch (NumberFormatException notComparable) {
                // A non-finite value (NaN / Infinity) is not numerically comparable: a mismatch, not an error.
                return false;
            }
        }
        return false;
    }

    private static boolean containsMatch(Object expected, Object actual) {
        if (actual instanceof String text) {
            return expected instanceof String needle && text.contains(needle);
        }
        if (actual instanceof List<?> list) {
            for (Object element : list) {
                if (equalsMatch(expected, element)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean regexMatch(Object expected, Object actual) {
        if (!(expected instanceof String regex) || !(actual instanceof String text)) {
            return false;
        }
        return Pattern.matches(regex, text);
    }
}
