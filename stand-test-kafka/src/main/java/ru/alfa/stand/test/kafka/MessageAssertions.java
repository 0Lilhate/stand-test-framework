package ru.alfa.stand.test.kafka;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidJsonException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.List;
import ru.alfa.stand.test.core.assertion.AssertionMatchers;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * JSONPath assertion and capture against a matched Kafka message value. Equality is delegated to
 * {@link AssertionMatchers#equalsMatch(Object, Object)} — the same evaluator REST and gRPC use — so
 * numbers compare by value (so {@code 100} matches {@code 100.0}) while any other type change stays a
 * genuine mismatch rather than being string-coerced, and the three adapters cannot drift apart. Kafka
 * exposes only that one matcher: {@code KafkaAssertion} carries no matcher field, so {@code kafka.expect}
 * is equals-only by construction. A missing/blank/invalid value or an unmatched assertion is a
 * {@link StandTestAssertionError} (a JUnit-native failure).
 */
final class MessageAssertions {

    private MessageAssertions() {
    }

    static DocumentContext parse(String value) {
        if (value == null || value.isBlank()) {
            throw new StandTestAssertionError("Message value is empty; expected JSON to assert or capture against");
        }
        try {
            return JsonPath.parse(value);
        } catch (InvalidJsonException | IllegalArgumentException invalid) {
            // Deliberately does NOT echo the parser's message: json-smart quotes a fragment of the
            // offending value, and a message value read off a shared stand topic is real payload that
            // must not travel into a report. The length is safe context; the value itself stays out.
            throw new StandTestAssertionError("Message value is not valid JSON (" + value.length() + " characters, parse failed)");
        }
    }

    static void verify(List<KafkaAssertion> assertions, DocumentContext document) {
        for (KafkaAssertion assertion : assertions) {
            Object actual = read(document, assertion.jsonPath());
            if (!AssertionMatchers.equalsMatch(assertion.expectedValue(), actual)) {
                throw new StandTestAssertionError("JSONPath assertion failed at '" + assertion.jsonPath() + "': expected <" + assertion.expectedValue() + "> but got <" + actual + ">");
            }
        }
    }

    static void applyCaptures(List<KafkaCapture> captures, DocumentContext document, VariableStore store) {
        for (KafkaCapture capture : captures) {
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
            throw new StandTestAssertionError("JSONPath '" + jsonPath + "' not found in message value");
        }
    }
}
