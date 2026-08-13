package ru.alfa.stand.test.grpc;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.List;
import ru.alfa.stand.test.core.assertion.AssertionMatchers;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * JSONPath assertion and capture against the JSON rendering of a gRPC unary response, using the shared
 * core {@link AssertionMatchers} grammar (equals/contains/exists/notNull/matches) — the same full matcher
 * set as the REST adapter, so a gRPC assertion is no longer equals-only. Numbers compare by value (so
 * {@code 100} matches {@code 100.0}) but any other type change is a genuine mismatch rather than being
 * string-coerced. A missing/blank/invalid response or an unmatched assertion is a
 * {@link StandTestAssertionError} (a JUnit-native failure). The class is thin per adapter on purpose —
 * adapters must not depend on one another (plan §5) — but the comparison logic lives once in core.
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
            throw new StandTestAssertionError("Response is not valid JSON (" + value.length() + " characters, parse failed)");
        }
    }

    static void verify(List<GrpcAssertion> assertions, DocumentContext document) {
        for (GrpcAssertion assertion : assertions) {
            boolean pathPresent = true;
            Object actual = null;
            try {
                actual = document.read(assertion.jsonPath());
            } catch (PathNotFoundException notFound) {
                pathPresent = false;
            }
            if (AssertionMatchers.matches(assertion.matcher(), assertion.expectedValue(), pathPresent, actual)) {
                continue;
            }
            String observed = pathPresent ? "<" + actual + ">" : "no value (path not found)";
            throw new StandTestAssertionError("JSONPath assertion [" + assertion.matcher() + "] failed at '" + assertion.jsonPath()
                    + "': expected <" + assertion.expectedValue() + "> but got " + observed);
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
}
