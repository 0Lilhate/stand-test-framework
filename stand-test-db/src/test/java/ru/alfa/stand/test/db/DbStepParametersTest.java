package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class DbStepParametersTest {

    @Test
    @DisplayName("requireString rejects a missing or blank value")
    void requireStringStrict() {
        assertThatThrownBy(() -> DbStepParameters.requireString(Map.of(), DbStepParameters.DATASOURCE))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("non-blank string");
    }

    @Test
    @DisplayName("bindValues reads a name-to-value map and rejects null values")
    void bindValues() {
        Map<String, Object> params = Map.of(DbStepParameters.PARAMS, Map.of("id", "x", "n", 5));

        assertThat(DbStepParameters.bindValues(params)).containsEntry("id", "x").containsEntry("n", 5);
        assertThat(DbStepParameters.bindValues(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("captures reads variableName/column entries and rejects malformed ones")
    void captures() {
        Map<String, Object> params = Map.of(DbStepParameters.CAPTURES,
                List.of(Map.of(DbStepParameters.VARIABLE_NAME, "status", DbStepParameters.COLUMN, "status")));

        assertThat(DbStepParameters.captures(params)).containsExactly(new DbCapture("status", "status"));

        Map<String, Object> malformed = Map.of(DbStepParameters.CAPTURES, List.of(Map.of(DbStepParameters.VARIABLE_NAME, "x")));
        assertThatThrownBy(() -> DbStepParameters.captures(malformed))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("DB capture");
    }

    @Test
    @DisplayName("requireExpectedValue rejects a missing expected value")
    void requireExpectedValue() {
        assertThatThrownBy(() -> DbStepParameters.requireExpectedValue(Map.of()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("expected value");
    }

    @Test
    @DisplayName("positiveMillis falls back to the default and rejects non-positive values")
    void positiveMillis() {
        assertThat(DbStepParameters.positiveMillis(Map.of(), DbStepParameters.TIMEOUT_MILLIS, 100L)).isEqualTo(100L);
        assertThat(DbStepParameters.positiveMillis(Map.of(DbStepParameters.TIMEOUT_MILLIS, 250L), DbStepParameters.TIMEOUT_MILLIS, 100L)).isEqualTo(250L);
        assertThatThrownBy(() -> DbStepParameters.positiveMillis(Map.of(DbStepParameters.TIMEOUT_MILLIS, 0L), DbStepParameters.TIMEOUT_MILLIS, 100L))
                .isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("positiveMillis accepts Integer and Long but rejects fractional or non-integer values (no silent truncation)")
    void positiveMillisStrictType() {
        // Integer accepted (e.g. from a YAML front-end where a small number deserialises to Integer).
        assertThat(DbStepParameters.positiveMillis(Map.of(DbStepParameters.TIMEOUT_MILLIS, 250), DbStepParameters.TIMEOUT_MILLIS, 100L)).isEqualTo(250L);
        // Double rejected rather than silently truncated (250.9 -> 250).
        assertThatThrownBy(() -> DbStepParameters.positiveMillis(Map.of(DbStepParameters.TIMEOUT_MILLIS, 250.9d), DbStepParameters.TIMEOUT_MILLIS, 100L))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
        // A non-number is rejected too.
        assertThatThrownBy(() -> DbStepParameters.positiveMillis(Map.of(DbStepParameters.TIMEOUT_MILLIS, "30000"), DbStepParameters.TIMEOUT_MILLIS, 100L))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    @DisplayName("the parameter-map readers reject malformed shapes (strict contract for a YAML front-end)")
    void rejectsMalformedParameterShapes() {
        assertThatThrownBy(() -> DbStepParameters.bindValues(Map.of(DbStepParameters.PARAMS, "nope")))
                .isInstanceOf(StandTestException.class).hasMessageContaining("must be a map");

        Map<String, Object> nullBind = new HashMap<>();
        nullBind.put("id", null);
        assertThatThrownBy(() -> DbStepParameters.bindValues(Map.of(DbStepParameters.PARAMS, nullBind)))
                .isInstanceOf(StandTestException.class).hasMessageContaining("must not be null");

        assertThatThrownBy(() -> DbStepParameters.optionalString(Map.of(DbStepParameters.SQL, 123), DbStepParameters.SQL))
                .isInstanceOf(StandTestException.class).hasMessageContaining("must be a string");

        assertThatThrownBy(() -> DbStepParameters.captures(Map.of(DbStepParameters.CAPTURES, "nope")))
                .isInstanceOf(StandTestException.class).hasMessageContaining("must be a list");

        assertThatThrownBy(() -> DbStepParameters.captures(Map.of(DbStepParameters.CAPTURES, List.of(123))))
                .isInstanceOf(StandTestException.class).hasMessageContaining("must be maps");
    }
}
