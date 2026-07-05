package ru.alfa.stand.test.rest;

import java.util.Objects;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;

/**
 * A single JSONPath assertion against a REST response body: a path, a matcher and the expected value.
 *
 * <p>For {@link AssertionMatcher#EXISTS}/{@link AssertionMatcher#NOT_NULL} the expected value is the
 * Boolean polarity; for {@link AssertionMatcher#MATCHES} it is the regular expression. The expected
 * value is never null — absence checks are expressed via {@code EXISTS} with {@code false}.
 *
 * @param jsonPath the JSONPath expression locating the value (never blank)
 * @param expectedValue the expected value / polarity / regex (never null)
 * @param matcher how the observed value is compared (null defaults to {@link AssertionMatcher#EQUALS})
 */
public record RestAssertion(String jsonPath, Object expectedValue, AssertionMatcher matcher) {

    public RestAssertion {
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("jsonPath must not be blank");
        }
        Objects.requireNonNull(expectedValue, "expectedValue must not be null");
        matcher = (matcher == null) ? AssertionMatcher.EQUALS : matcher;
    }

    /**
     * Creates an equality assertion (the historical two-argument form).
     *
     * @param jsonPath the JSONPath expression locating the value (never blank)
     * @param expectedValue the expected value at that path (never null)
     */
    public RestAssertion(String jsonPath, Object expectedValue) {
        this(jsonPath, expectedValue, AssertionMatcher.EQUALS);
    }
}
