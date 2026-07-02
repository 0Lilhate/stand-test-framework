package ru.alfa.stand.test.grpc;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * JSONPath assertion and capture against the JSON rendering of a gRPC unary response, with the same
 * type-aware matching as the REST/Kafka adapters: numbers compare by value (so {@code 100} matches
 * {@code 100.0}) but any other type change is a genuine mismatch rather than being string-coerced. A
 * missing/blank/invalid value or an unmatched assertion is a {@link StandTestAssertionError} (a
 * JUnit-native failure). The class is copied per adapter on purpose — adapters must not depend on one
 * another (plan §5).
 */
final class ResponseAssertions {

    private ResponseAssertions() {
    }

    static DocumentContext parse(String value) {
        if (value == null || value.isBlank()) {
            throw new StandTestAssertionError("Response is empty; expected JSON to assert or capture against");
        }
        try {
            return JsonPath.parse(value);
        } catch (InvalidJsonException | IllegalArgumentException invalid) {
            throw new StandTestAssertionError("Response is not valid JSON: " + invalid.getMessage());
        }
    }

    static void verify(List<GrpcAssertion> assertions, DocumentContext document) {
        for (GrpcAssertion assertion : assertions) {
            Object actual = read(document, assertion.jsonPath());
            if (!valuesMatch(assertion.expectedValue(), actual)) {
                throw new StandTestAssertionError("JSONPath assertion failed at '" + assertion.jsonPath() + "': expected <" + assertion.expectedValue() + "> but got <" + actual + ">");
            }
        }
    }

    static void applyCaptures(List<GrpcCapture> captures, DocumentContext document, VariableStore store) {
        for (GrpcCapture capture : captures) {
            Object value = read(document, capture.jsonPath());
            if (value == null) {
                throw new StandTestAssertionError("Captured value at '" + capture.jsonPath() + "' is null; cannot store variable '" + capture.variableName() + "'");
            }
            store.put(capture.variableName(), value);
        }
    }

    private static Object read(DocumentContext document, String jsonPath) {
        try {
            return document.read(jsonPath);
        } catch (PathNotFoundException notFound) {
            throw new StandTestAssertionError("JSONPath '" + jsonPath + "' not found in response");
        }
    }

    private static boolean valuesMatch(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        // Numbers are compared by numeric value so e.g. an expected int 100 matches a JSON 100.0; all
        // other type mismatches (boolean vs string, string vs number, ...) are a genuine mismatch and
        // must fail rather than be string-coerced, so a field changing type is caught.
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            try {
                return new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) == 0;
            } catch (NumberFormatException notComparable) {
                // A non-finite expected value (NaN / Infinity) is not numerically comparable: treat it as
                // a mismatch rather than letting a raw NumberFormatException escape (plan §8.3).
                return false;
            }
        }
        return false;
    }
}
