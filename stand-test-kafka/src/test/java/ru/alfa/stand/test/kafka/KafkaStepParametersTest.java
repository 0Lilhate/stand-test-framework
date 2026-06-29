package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class KafkaStepParametersTest {

    @Test
    @DisplayName("requireString rejects a missing, blank or non-string value")
    void requireString() {
        assertThat(KafkaStepParameters.requireString(Map.of("topic", "t"), "topic")).isEqualTo("t");
        assertThatThrownBy(() -> KafkaStepParameters.requireString(Map.of(), "topic")).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> KafkaStepParameters.requireString(Map.of("topic", " "), "topic")).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> KafkaStepParameters.requireString(Map.of("topic", 1), "topic")).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("optionalString returns empty, the value, or fails on the wrong type")
    void optionalString() {
        assertThat(KafkaStepParameters.optionalString(Map.of(), "key")).isEmpty();
        assertThat(KafkaStepParameters.optionalString(Map.of("key", "k"), "key")).contains("k");
        assertThatThrownBy(() -> KafkaStepParameters.optionalString(Map.of("key", 1), "key")).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("flag is true only for Boolean.TRUE")
    void flag() {
        assertThat(KafkaStepParameters.flag(Map.of("injectCorrelationId", true), "injectCorrelationId")).isTrue();
        assertThat(KafkaStepParameters.flag(Map.of("injectCorrelationId", false), "injectCorrelationId")).isFalse();
        assertThat(KafkaStepParameters.flag(Map.of(), "injectCorrelationId")).isFalse();
    }

    @Test
    @DisplayName("positiveMillis returns the default, the value, or fails on a non-positive / non-number")
    void positiveMillis() {
        assertThat(KafkaStepParameters.positiveMillis(Map.of(), "timeoutMillis", 30_000L)).isEqualTo(30_000L);
        assertThat(KafkaStepParameters.positiveMillis(Map.of("timeoutMillis", 250L), "timeoutMillis", 30_000L)).isEqualTo(250L);
        assertThatThrownBy(() -> KafkaStepParameters.positiveMillis(Map.of("timeoutMillis", 0L), "timeoutMillis", 1L)).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> KafkaStepParameters.positiveMillis(Map.of("timeoutMillis", "soon"), "timeoutMillis", 1L)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("stringMap returns an empty map, coerces entries, or fails on a non-map")
    void stringMap() {
        assertThat(KafkaStepParameters.stringMap(Map.of(), "headers")).isEmpty();
        assertThat(KafkaStepParameters.stringMap(Map.of("headers", Map.of("a", "b")), "headers")).containsEntry("a", "b");
        assertThatThrownBy(() -> KafkaStepParameters.stringMap(Map.of("headers", "x"), "headers")).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("assertions reads the list and rejects malformed entries")
    void assertions() {
        Map<String, Object> valid = Map.of("assertions", List.of(Map.of("jsonPath", "$.s", "expectedValue", "OK")));
        assertThat(KafkaStepParameters.assertions(valid)).containsExactly(new KafkaAssertion("$.s", "OK"));
        assertThat(KafkaStepParameters.assertions(Map.of())).isEmpty();
        assertThatThrownBy(() -> KafkaStepParameters.assertions(Map.of("assertions", "x"))).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> KafkaStepParameters.assertions(Map.of("assertions", List.of("x")))).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> KafkaStepParameters.assertions(Map.of("assertions", List.of(Map.of("jsonPath", 1, "expectedValue", "OK"))))).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("captures reads the list and rejects entries missing string fields")
    void captures() {
        Map<String, Object> valid = Map.of("captures", List.of(Map.of("variableName", "id", "jsonPath", "$.id")));
        assertThat(KafkaStepParameters.captures(valid)).containsExactly(new KafkaCapture("id", "$.id"));
        assertThatThrownBy(() -> KafkaStepParameters.captures(Map.of("captures", List.of(Map.of("variableName", "id"))))).isInstanceOf(StandTestException.class);
    }
}
