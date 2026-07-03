package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

class GrpcStepParametersTest {

    @Test
    @DisplayName("requireString rejects a missing or blank value")
    void requireStringStrict() {
        assertThatThrownBy(() -> GrpcStepParameters.requireString(Map.of(), GrpcStepParameters.TARGET))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining(GrpcStepParameters.TARGET);
        assertThat(GrpcStepParameters.requireString(Map.of(GrpcStepParameters.TARGET, "svc"), GrpcStepParameters.TARGET)).isEqualTo("svc");
    }

    @Test
    @DisplayName("requirePositiveMillis requires a present positive number")
    void requirePositiveMillisStrict() {
        assertThatThrownBy(() -> GrpcStepParameters.requirePositiveMillis(Map.of(), GrpcStepParameters.DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> GrpcStepParameters.requirePositiveMillis(Map.of(GrpcStepParameters.DEADLINE_MILLIS, 0L), GrpcStepParameters.DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> GrpcStepParameters.requirePositiveMillis(Map.of(GrpcStepParameters.DEADLINE_MILLIS, "x"), GrpcStepParameters.DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("number");
        assertThat(GrpcStepParameters.requirePositiveMillis(Map.of(GrpcStepParameters.DEADLINE_MILLIS, 1_500L), GrpcStepParameters.DEADLINE_MILLIS)).isEqualTo(1_500L);
    }

    @Test
    @DisplayName("requirePositiveMillis accepts only whole-number types — a Double is rejected, never truncated or saturated")
    void requirePositiveMillisRejectsFloatingPoint() {
        assertThatThrownBy(() -> GrpcStepParameters.requirePositiveMillis(Map.of(GrpcStepParameters.DEADLINE_MILLIS, 1e30), GrpcStepParameters.DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
        assertThatThrownBy(() -> GrpcStepParameters.requirePositiveMillis(Map.of(GrpcStepParameters.DEADLINE_MILLIS, 3.9d), GrpcStepParameters.DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
        assertThat(GrpcStepParameters.requirePositiveMillis(Map.of(GrpcStepParameters.DEADLINE_MILLIS, 250), GrpcStepParameters.DEADLINE_MILLIS)).isEqualTo(250L);
    }

    @Test
    @DisplayName("requirePositiveMillis caps the deadline at the SDK-wide 1-hour timeout bound")
    void requirePositiveMillisBounded() {
        assertThatThrownBy(() -> GrpcStepParameters.requirePositiveMillis(
                Map.of(GrpcStepParameters.DEADLINE_MILLIS, DefaultScenarioValidator.MAX_TIMEOUT_MILLIS + 1), GrpcStepParameters.DEADLINE_MILLIS))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("at most");
        assertThat(GrpcStepParameters.requirePositiveMillis(
                Map.of(GrpcStepParameters.DEADLINE_MILLIS, DefaultScenarioValidator.MAX_TIMEOUT_MILLIS), GrpcStepParameters.DEADLINE_MILLIS))
                .isEqualTo(DefaultScenarioValidator.MAX_TIMEOUT_MILLIS);
    }

    @Test
    @DisplayName("flag is true only for Boolean.TRUE")
    void flagReadsBoolean() {
        assertThat(GrpcStepParameters.flag(Map.of(GrpcStepParameters.INJECT_CORRELATION_ID, true), GrpcStepParameters.INJECT_CORRELATION_ID)).isTrue();
        assertThat(GrpcStepParameters.flag(Map.of(), GrpcStepParameters.INJECT_CORRELATION_ID)).isFalse();
    }

    @Test
    @DisplayName("stringMap coerces entries and rejects a non-map value")
    void stringMapStrict() {
        assertThat(GrpcStepParameters.stringMap(Map.of(GrpcStepParameters.METADATA, Map.of("a", "b")), GrpcStepParameters.METADATA)).containsEntry("a", "b");
        assertThatThrownBy(() -> GrpcStepParameters.stringMap(Map.of(GrpcStepParameters.METADATA, "x"), GrpcStepParameters.METADATA))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("map");
    }

    @Test
    @DisplayName("stringMap rejects a null metadata value instead of coercing it to the literal string \"null\"")
    void stringMapRejectsNullValue() {
        java.util.Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("x-flow", null);

        assertThatThrownBy(() -> GrpcStepParameters.stringMap(Map.of(GrpcStepParameters.METADATA, metadata), GrpcStepParameters.METADATA))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must not contain null");
    }

    @Test
    @DisplayName("assertions and captures parse their nested maps and reject malformed entries")
    void assertionsAndCaptures() {
        Map<String, Object> parameters = Map.of(
                GrpcStepParameters.ASSERTIONS, List.of(Map.of(GrpcStepParameters.JSON_PATH, "$.a", GrpcStepParameters.EXPECTED_VALUE, 1)),
                GrpcStepParameters.CAPTURES, List.of(Map.of(GrpcStepParameters.VARIABLE_NAME, "v", GrpcStepParameters.JSON_PATH, "$.b")));
        assertThat(GrpcStepParameters.assertions(parameters)).containsExactly(new GrpcAssertion("$.a", 1));
        assertThat(GrpcStepParameters.captures(parameters)).containsExactly(new GrpcCapture("v", "$.b"));

        Map<String, Object> badAssertion = Map.of(GrpcStepParameters.ASSERTIONS, List.of(Map.of(GrpcStepParameters.JSON_PATH, "$.a")));
        assertThatThrownBy(() -> GrpcStepParameters.assertions(badAssertion))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining(GrpcStepParameters.EXPECTED_VALUE);
    }
}
