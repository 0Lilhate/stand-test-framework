package ru.alfa.stand.test.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

class YamlScenarioParserTest {

    private final YamlScenarioParser parser = new YamlScenarioParser();

    private static Map<String, Object> params(Scenario scenario, int index) {
        return ((GenericStep) scenario.steps().get(index)).parameters();
    }

    @Test
    @DisplayName("top-level id/env/title/description/tags are mapped onto the Scenario")
    void topLevel_isMapped() {
        Scenario scenario = parser.parse("""
                id: example-flow
                env: ift
                title: Example async flow
                description: a demo
                tags: [integration, kafka]
                given:
                  - rest.get:
                      service: client-service
                      path: /health
                """);

        assertThat(scenario.id().value()).isEqualTo("example-flow");
        assertThat(scenario.environment()).isEqualTo("ift");
        assertThat(scenario.title()).contains("Example async flow");
        assertThat(scenario.description()).contains("a demo");
        assertThat(scenario.tags()).containsExactlyInAnyOrder("integration", "kafka");
        assertThat(scenario.steps()).hasSize(1);
    }

    @Test
    @DisplayName("given then then are concatenated in declaration order")
    void givenThen_areConcatenatedInOrder() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.post:
                      service: client-service
                      path: /a
                then:
                  - db.expectEventually:
                      datasource: mainDb
                      sql: select 1
                      equals: 1
                """);

        assertThat(scenario.steps()).extracting(step -> step.type()).containsExactly("rest.post", "db.expectEventually");
    }

    @Test
    @DisplayName("a step id is generated as <phase>.<type>#<index> when not declared, and an explicit id wins")
    void stepId_generatedOrExplicit() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.get:
                      service: s
                      path: /a
                  - rest.get:
                      id: custom-id
                      service: s
                      path: /b
                """);

        assertThat(scenario.steps().get(0).id()).isEqualTo("given.rest.get#0");
        assertThat(scenario.steps().get(1).id()).isEqualTo("custom-id");
    }

    @Test
    @DisplayName("a full rest.post surface maps to the exact GenericStep parameter keys and types")
    void restPost_mapsToInternalKeysAndTypes() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.post:
                      service: client-service
                      path: /api/request
                      headers:
                        Content-Type: application/json
                      injectCorrelationId: true
                      expectStatus: 200
                      body: '{"amount":1}'
                      assert:
                        "$.status": ACCEPTED
                      capture:
                        requestId: "$.requestId"
                """);

        assertThat(scenario.steps().get(0).type()).isEqualTo("rest.post");
        assertThat(scenario.steps().get(0).description()).isEqualTo("POST client-service /api/request");
        Map<String, Object> params = params(scenario, 0);
        assertThat(params).containsEntry("method", "POST").containsEntry("service", "client-service").containsEntry("path", "/api/request");
        assertThat(params.get("headers")).isEqualTo(Map.of("Content-Type", "application/json"));
        assertThat(params.get("query")).isEqualTo(Map.of());
        assertThat(params.get("injectCorrelationId")).isEqualTo(Boolean.TRUE);
        assertThat(params.get("expectedStatus")).isEqualTo(200).isInstanceOf(Integer.class);
        assertThat(params.get("body")).isEqualTo("{\"amount\":1}");
        assertThat(params).doesNotContainKey("bodyResource");
        assertThat(params.get("assertions")).isEqualTo(List.of(Map.of("jsonPath", "$.status", "expectedValue", "ACCEPTED")));
        assertThat(params.get("captures")).isEqualTo(List.of(Map.of("variableName", "requestId", "jsonPath", "$.requestId")));
    }

    @Test
    @DisplayName("bodyResource maps to the resource key; body and bodyResource together are rejected")
    void restBody_inlineVersusResource() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.post:
                      service: s
                      path: /a
                      bodyResource: fixtures/request.json
                """);
        assertThat(params(scenario, 0)).containsEntry("bodyResource", "fixtures/request.json").doesNotContainKey("body");

        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.post:
                      service: s
                      path: /a
                      body: inline
                      bodyResource: fixtures/x.json
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("only one");
    }

    @Test
    @DisplayName("${...} placeholders are preserved verbatim (resolved at runtime, not by the parser)")
    void placeholders_arePreserved() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - db.seed:
                      datasource: mainDb
                      sql: "insert into t(id) values (:id)"
                      params:
                        id: "${requestId}"
                """);

        assertThat(params(scenario, 0).get("params")).isEqualTo(Map.of("id", "${requestId}"));
    }

    @Test
    @DisplayName("an unknown top-level key is rejected fail-closed")
    void unknownTopLevelKey_isRejected() {
        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                setup: nope
                given:
                  - rest.get:
                      service: s
                      path: /a
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("setup");
    }

    @Test
    @DisplayName("an unknown step field is rejected fail-closed")
    void unknownStepField_isRejected() {
        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                given:
                  - rest.get:
                      service: s
                      path: /a
                      retries: 3
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("retries");
    }

    @Test
    @DisplayName("an unknown step type is rejected fail-closed")
    void unknownStepType_isRejected() {
        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                given:
                  - grpc.call:
                      target: t
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("grpc.call");
    }

    @Test
    @DisplayName("a scenario with no steps and missing id/env are rejected")
    void structuralRequirements_areEnforced() {
        assertThatThrownBy(() -> parser.parse("id: flow\nenv: ift\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("at least one step");
        assertThatThrownBy(() -> parser.parse("env: ift\ngiven: [ { rest.get: { service: s, path: /a } } ]\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> parser.parse("id: flow\ngiven: [ { rest.get: { service: s, path: /a } } ]\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("env");
    }
}
