package ru.alfa.stand.test.rest;

import java.util.Objects;

/**
 * A single JSONPath equality assertion against a REST response body.
 *
 * @param jsonPath the JSONPath expression locating the value (never blank)
 * @param expectedValue the expected value at that path (never null)
 */
public record RestAssertion(String jsonPath, Object expectedValue) {

    public RestAssertion {
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("jsonPath must not be blank");
        }
        Objects.requireNonNull(expectedValue, "expectedValue must not be null");
    }
}
