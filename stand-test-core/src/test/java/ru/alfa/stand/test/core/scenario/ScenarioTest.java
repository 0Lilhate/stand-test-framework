package ru.alfa.stand.test.core.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.identifier.ScenarioId;

class ScenarioTest {

    @Test
    @DisplayName("builder assembles an immutable scenario with steps, tags and optional title")
    void builder_assemblesScenario() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(GenericStep.of("s1", "rest.post"))
                .step(GenericStep.of("s2", "kafka.expect"))
                .tag("smoke")
                .title("Example flow")
                .build();

        assertThat(scenario.id()).isEqualTo(ScenarioId.of("flow"));
        assertThat(scenario.environment()).isEqualTo("ift");
        assertThat(scenario.steps()).hasSize(2);
        assertThat(scenario.tags()).containsExactly("smoke");
        assertThat(scenario.title()).contains("Example flow");
        assertThat(scenario.description()).isEmpty();
    }

    @Test
    @DisplayName("steps and tags are exposed as immutable collections")
    void collections_areImmutable() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .environment("ift")
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        assertThatThrownBy(() -> scenario.steps().add(GenericStep.of("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scenario.tags().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the model is permissive about a missing environment (validator's job to flag it)")
    void missingEnvironment_defaultsToEmpty() {
        Scenario scenario = Scenario.builder(ScenarioId.of("flow"))
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        assertThat(scenario.environment()).isEmpty();
    }

    @Test
    @DisplayName("GenericStep rejects blank id and type and defaults description to empty")
    void genericStep_validation() {
        GenericStep step = GenericStep.of("s1", "rest.post");

        assertThat(step.id()).isEqualTo("s1");
        assertThat(step.type()).isEqualTo("rest.post");
        assertThat(step.description()).isEmpty();
        assertThat(step.parameters()).isEmpty();
        assertThatThrownBy(() -> GenericStep.of(" ", "type")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GenericStep.of("id", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("scenarios are equal when their fields are equal")
    void scenario_equality() {
        Scenario first = Scenario.builder(ScenarioId.of("flow")).environment("ift")
                .step(GenericStep.of("s1", "rest.post")).build();
        Scenario second = Scenario.builder(ScenarioId.of("flow")).environment("ift")
                .step(GenericStep.of("s1", "rest.post")).build();

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    @DisplayName("builder(String) is an ergonomic overload of builder(ScenarioId)")
    void builder_stringOverload() {
        Scenario scenario = Scenario.builder("flow")
                .environment("ift")
                .step(GenericStep.of("s1", "rest.post"))
                .build();

        assertThat(scenario.id()).isEqualTo(ScenarioId.of("flow"));
    }

    @Test
    @DisplayName("GenericStep parameters are defensively copied, immutable and reject null values")
    void genericStep_parameters() {
        Map<String, Object> params = new HashMap<>();
        params.put("path", "/api/request");
        GenericStep step = new GenericStep("s1", "rest.post", "post a request", params);

        params.put("leak", "x");

        assertThat(step.parameters()).containsOnlyKeys("path");
        assertThat(step.description()).isEqualTo("post a request");
        assertThatThrownBy(() -> step.parameters().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> nullValued = new HashMap<>();
        nullValued.put("bad", null);
        assertThatThrownBy(() -> new GenericStep("s2", "rest.post", "", nullValued))
                .isInstanceOf(NullPointerException.class);
    }
}
