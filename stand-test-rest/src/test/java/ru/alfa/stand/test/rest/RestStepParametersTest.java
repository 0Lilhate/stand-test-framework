package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Exercises the strict reader error branches of {@link RestStepParameters} — the validation boundary a
 * future YAML/AI producer of the parameter map relies on (the typed {@link RestStep} builder always
 * emits well-formed values, so these branches are only reachable with a hand-built malformed map).
 */
class RestStepParametersTest {

    @Test
    @DisplayName("requireString rejects a missing or non-string value")
    void requireStringRejectsBadValue() {
        assertThatThrownBy(() -> RestStepParameters.requireString(Map.of(), RestStepParameters.SERVICE)).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> RestStepParameters.requireString(Map.of(RestStepParameters.SERVICE, 1), RestStepParameters.SERVICE)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("optionalString rejects a non-string value")
    void optionalStringRejectsNonString() {
        assertThatThrownBy(() -> RestStepParameters.optionalString(Map.of(RestStepParameters.BODY, 1), RestStepParameters.BODY)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("method rejects an unsupported HTTP method")
    void methodRejectsUnsupported() {
        assertThatThrownBy(() -> RestStepParameters.method(Map.of(RestStepParameters.METHOD, "FOO"))).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("expectedStatus is empty when absent, present when an integer, and rejects a non-integer")
    void expectedStatusValidation() {
        assertThat(RestStepParameters.expectedStatus(Map.of())).isEmpty();
        assertThat(RestStepParameters.expectedStatus(Map.of(RestStepParameters.EXPECTED_STATUS, 200))).hasValue(200);
        assertThatThrownBy(() -> RestStepParameters.expectedStatus(Map.of(RestStepParameters.EXPECTED_STATUS, "200"))).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("stringMap rejects a non-map value")
    void stringMapRejectsNonMap() {
        assertThatThrownBy(() -> RestStepParameters.stringMap(Map.of(RestStepParameters.QUERY, "x"), RestStepParameters.QUERY)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("assertions rejects a non-list, a non-map entry, a non-string path and a null expected value")
    void assertionsValidation() {
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, "x"))).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of("x")))).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of(Map.of(RestStepParameters.JSON_PATH, 1, RestStepParameters.EXPECTED_VALUE, "v"))))).isInstanceOf(StandTestException.class);
        Map<String, Object> nullExpected = new HashMap<>();
        nullExpected.put(RestStepParameters.JSON_PATH, "$.x");
        nullExpected.put(RestStepParameters.EXPECTED_VALUE, null);
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of(nullExpected)))).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("captures rejects a non-string name or path")
    void capturesValidation() {
        assertThatThrownBy(() -> RestStepParameters.captures(Map.of(RestStepParameters.CAPTURES, List.of(Map.of(RestStepParameters.VARIABLE_NAME, 1, RestStepParameters.JSON_PATH, "$.x"))))).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("an absent matcher key means EQUALS; a known matcher parses case-insensitively")
    void matcherParsing() {
        Map<String, Object> plain = Map.of(RestStepParameters.JSON_PATH, "$.x", RestStepParameters.EXPECTED_VALUE, "v");
        Map<String, Object> contains = Map.of(RestStepParameters.JSON_PATH, "$.x", RestStepParameters.EXPECTED_VALUE, "v", RestStepParameters.MATCHER, "contains");

        List<RestAssertion> assertions = RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of(plain, contains)));

        assertThat(assertions.get(0).matcher()).isEqualTo(ru.alfa.stand.test.core.assertion.AssertionMatcher.EQUALS);
        assertThat(assertions.get(1).matcher()).isEqualTo(ru.alfa.stand.test.core.assertion.AssertionMatcher.CONTAINS);
    }

    @Test
    @DisplayName("matcher operand validation is fail-fast: unknown matcher, non-boolean exists, invalid regex")
    void matcherOperandValidation() {
        Map<String, Object> unknown = Map.of(RestStepParameters.JSON_PATH, "$.x", RestStepParameters.EXPECTED_VALUE, "v", RestStepParameters.MATCHER, "SCRIPT");
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of(unknown))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unknown REST assertion matcher");

        Map<String, Object> existsWithString = Map.of(RestStepParameters.JSON_PATH, "$.x", RestStepParameters.EXPECTED_VALUE, "yes", RestStepParameters.MATCHER, "EXISTS");
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of(existsWithString))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("requires a boolean");

        Map<String, Object> badRegex = Map.of(RestStepParameters.JSON_PATH, "$.x", RestStepParameters.EXPECTED_VALUE, "[unclosed", RestStepParameters.MATCHER, "MATCHES");
        assertThatThrownBy(() -> RestStepParameters.assertions(Map.of(RestStepParameters.ASSERTIONS, List.of(badRegex))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("invalid regular expression");
    }

    @Test
    @DisplayName("positiveMillis applies the default, accepts Integer/Long and rejects other shapes")
    void positiveMillisValidation() {
        assertThat(RestStepParameters.positiveMillis(Map.of(), RestStepParameters.TIMEOUT_MILLIS, 30_000L)).isEqualTo(30_000L);
        assertThat(RestStepParameters.positiveMillis(Map.of(RestStepParameters.TIMEOUT_MILLIS, 5_000L), RestStepParameters.TIMEOUT_MILLIS, 30_000L)).isEqualTo(5_000L);
        assertThat(RestStepParameters.positiveMillis(Map.of(RestStepParameters.TIMEOUT_MILLIS, 250), RestStepParameters.TIMEOUT_MILLIS, 30_000L)).isEqualTo(250L);
        assertThatThrownBy(() -> RestStepParameters.positiveMillis(Map.of(RestStepParameters.TIMEOUT_MILLIS, "5s"), RestStepParameters.TIMEOUT_MILLIS, 30_000L)).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> RestStepParameters.positiveMillis(Map.of(RestStepParameters.TIMEOUT_MILLIS, 0L), RestStepParameters.TIMEOUT_MILLIS, 30_000L)).isInstanceOf(StandTestException.class);
    }
}
