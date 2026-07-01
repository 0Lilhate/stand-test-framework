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

class KafkaStepYamlTest {

    private final YamlScenarioParser parser = new YamlScenarioParser();

    private static Map<String, Object> params(Scenario scenario, int index) {
        return ((GenericStep) scenario.steps().get(index)).parameters();
    }

    @Test
    @DisplayName("kafka.send maps topic/body/key/headers and injectCorrelationId, without expect-only keys")
    void send_mapsToInternalKeys() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                given:
                  - kafka.send:
                      topic: request-topic
                      body: '{"a":1}'
                      key: k-1
                      headers:
                        trace: t-1
                      injectCorrelationId: true
                """);

        assertThat(scenario.steps().get(0).type()).isEqualTo("kafka.send");
        Map<String, Object> params = params(scenario, 0);
        assertThat(params).containsEntry("topic", "request-topic").containsEntry("key", "k-1").containsEntry("body", "{\"a\":1}");
        assertThat(params.get("headers")).isEqualTo(Map.of("trace", "t-1"));
        assertThat(params.get("injectCorrelationId")).isEqualTo(Boolean.TRUE);
        assertThat(params).doesNotContainKeys("assertions", "captures", "correlationIdFromContext");
    }

    @Test
    @DisplayName("kafka.expect maps correlation/assert/capture and durations to millis (Long)")
    void expect_mapsDurationsAndAssertions() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                then:
                  - kafka.expect:
                      topic: response-topic
                      correlationIdFromContext: true
                      timeout: 30s
                      pollTimeout: 500ms
                      assert:
                        "$.status": SUCCESS
                      capture:
                        entityId: "$.entityId"
                """);

        Map<String, Object> params = params(scenario, 0);
        assertThat(params).containsEntry("topic", "response-topic").containsEntry("correlationIdFromContext", Boolean.TRUE);
        assertThat(params.get("timeoutMillis")).isEqualTo(30000L).isInstanceOf(Long.class);
        assertThat(params.get("pollTimeoutMillis")).isEqualTo(500L).isInstanceOf(Long.class);
        assertThat(params.get("assertions")).isEqualTo(List.of(Map.of("jsonPath", "$.status", "expectedValue", "SUCCESS")));
        assertThat(params.get("captures")).isEqualTo(List.of(Map.of("variableName", "entityId", "jsonPath", "$.entityId")));
    }

    @Test
    @DisplayName("kafka.expect omits timeout/pollTimeout when absent (executor defaults them)")
    void expect_omitsUnsetDurations() {
        Scenario scenario = parser.parse("""
                id: flow
                env: ift
                then:
                  - kafka.expect:
                      topic: t
                      correlationIdFromContext: true
                """);

        assertThat(params(scenario, 0)).doesNotContainKeys("timeoutMillis", "pollTimeoutMillis");
    }

    @Test
    @DisplayName("kafka.send requires a body; an expect-only key on send is rejected")
    void send_requiresBody_andRejectsExpectKeys() {
        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                given:
                  - kafka.send:
                      topic: t
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("body");

        assertThatThrownBy(() -> parser.parse("""
                id: flow
                env: ift
                given:
                  - kafka.send:
                      topic: t
                      body: x
                      assert:
                        "$.a": 1
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("assert");
    }
}
