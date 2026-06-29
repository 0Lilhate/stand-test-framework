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
}
