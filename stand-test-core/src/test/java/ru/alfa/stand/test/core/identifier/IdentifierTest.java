package ru.alfa.stand.test.core.identifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentifierTest {

    @Test
    @DisplayName("ScenarioId.of accepts a value and exposes it via value() and toString()")
    void scenarioId_of_exposesValue() {
        ScenarioId id = ScenarioId.of("example-flow");

        assertThat(id.value()).isEqualTo("example-flow");
        assertThat(id.toString()).isEqualTo("example-flow");
    }

    @Test
    @DisplayName("ScenarioId.of rejects null and blank values")
    void scenarioId_of_rejectsBlank() {
        assertThatThrownBy(() -> ScenarioId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScenarioId.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScenarioId.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ScenarioId is value-based for equals and hashCode")
    void scenarioId_isValueBased() {
        assertThat(ScenarioId.of("a")).isEqualTo(ScenarioId.of("a"));
        assertThat(ScenarioId.of("a")).hasSameHashCodeAs(ScenarioId.of("a"));
        assertThat(ScenarioId.of("a")).isNotEqualTo(ScenarioId.of("b"));
    }

    @Test
    @DisplayName("TestRunId.generate produces unique non-blank values")
    void testRunId_generate_isUnique() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            values.add(TestRunId.generate().value());
        }

        assertThat(values).hasSize(1000);
        assertThat(TestRunId.generate().value()).isNotBlank();
    }

    @Test
    @DisplayName("CorrelationId.generate produces unique non-blank values")
    void correlationId_generate_isUnique() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            values.add(CorrelationId.generate().value());
        }

        assertThat(values).hasSize(1000);
        assertThat(CorrelationId.generate().value()).isNotBlank();
    }

    @Test
    @DisplayName("TestRunId.of and CorrelationId.of reject blank values")
    void runAndCorrelation_of_rejectsBlank() {
        assertThatThrownBy(() -> TestRunId.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorrelationId.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
