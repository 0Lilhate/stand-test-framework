package ru.alfa.stand.test.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

class AiScenarioParserTest {

    private final AiScenarioParser parser = new AiScenarioParser();

    private static Map<String, Object> params(ScenarioStep step) {
        return ((GenericStep) step).parameters();
    }

    @Test
    @DisplayName("a full rest/kafka/db flow maps AI fields onto the exact wire keys")
    void fullFlow_mapsToWireKeys() {
        Scenario scenario = parser.parse("""
                {
                  "id": "flow", "environment": "ift", "title": "T", "description": "D", "tags": ["integration"],
                  "steps": [
                    {"id":"create","type":"rest.post","service":"client-service","path":"/api/requests",
                     "query":{"q":"1"},"headers":{"Accept":"application/json"},
                     "correlation":{"inject":true},"body":{"fixture":"fixtures/request.json"},
                     "expect":{"status":200},"capture":{"requestId":"$.requestId"}},
                    {"id":"await","type":"kafka.expect","topic":"response-topic",
                     "correlation":{"fromContext":true},"timeout":"30s",
                     "assert":[{"path":"$.status","equals":"SUCCESS"}],"capture":{"eventId":"$.eventId"}},
                    {"id":"verify","type":"db.expectEventually","datasource":"main-db","timeout":"10s",
                     "query":"SELECT status FROM requests WHERE request_id = :requestId",
                     "params":{"requestId":"${requestId}"},"expect":{"singleValue":"DONE"}}
                  ]
                }
                """);

        assertThat(scenario.environment()).isEqualTo("ift");
        assertThat(scenario.title()).contains("T");
        assertThat(scenario.tags()).containsExactly("integration");
        assertThat(scenario.steps()).hasSize(3);
        assertThatCode(() -> new DefaultScenarioValidator().validate(scenario).throwIfInvalid()).doesNotThrowAnyException();

        Map<String, Object> rest = params(scenario.steps().get(0));
        assertThat(rest).containsEntry("method", "POST").containsEntry("service", "client-service")
                .containsEntry("path", "/api/requests").containsEntry("injectCorrelationId", true)
                .containsEntry("expectedStatus", 200).containsEntry("bodyResource", "fixtures/request.json");
        assertThat(rest).containsEntry("query", Map.of("q", "1"));
        assertThat(rest.get("captures")).isEqualTo(List.of(Map.of("variableName", "requestId", "jsonPath", "$.requestId")));

        Map<String, Object> kafka = params(scenario.steps().get(1));
        assertThat(kafka).containsEntry("topic", "response-topic").containsEntry("correlationIdFromContext", true)
                .containsEntry("timeoutMillis", 30000L);
        assertThat(kafka.get("assertions")).isEqualTo(List.of(Map.of("jsonPath", "$.status", "expectedValue", "SUCCESS")));

        Map<String, Object> db = params(scenario.steps().get(2));
        assertThat(db).containsEntry("datasource", "main-db").containsEntry("expectedValue", "DONE")
                .containsEntry("timeoutMillis", 10000L);
        assertThat((String) db.get("sql")).startsWith("SELECT");
        assertThat(db).containsEntry("params", Map.of("requestId", "${requestId}"));
    }

    @Test
    @DisplayName("minute-suffixed timeouts ('<n>m', schema-valid) parse to milliseconds — parity with the schema duration contract")
    void minuteTimeout_parses() {
        Scenario scenario = parser.parse("""
                id: flow
                environment: ift
                steps:
                  - id: await
                    type: kafka.expect
                    topic: response-topic
                    timeout: 2m
                    assert:
                      - path: $.status
                        equals: SUCCESS
                """);

        assertThat(params(scenario.steps().get(0))).containsEntry("timeoutMillis", 120_000L);
    }

    @Test
    @DisplayName("a minimal rest.get and kafka.send parse and default their optional wire keys")
    void minimalSteps_parse() {
        Scenario scenario = parser.parse("""
                id: flow
                environment: dev
                steps:
                  - id: read
                    type: rest.get
                    service: svc
                    path: /a
                  - id: send
                    type: kafka.send
                    topic: commands
                    key: k
                    payload:
                      fixture: fixtures/cmd.json
                """);
        assertThat(scenario.steps()).hasSize(2);
        assertThat(params(scenario.steps().get(0))).containsEntry("method", "GET").doesNotContainKey("injectCorrelationId");
        Map<String, Object> send = params(scenario.steps().get(1));
        assertThat(send).containsEntry("topic", "commands").containsEntry("key", "k").containsEntry("bodyResource", "fixtures/cmd.json")
                .doesNotContainKey("injectCorrelationId");
    }

    @Test
    @DisplayName("an absent step id is generated as <type>#<index>")
    void absentId_isGenerated() {
        Scenario scenario = parser.parse("""
                id: flow
                environment: ift
                steps:
                  - type: rest.get
                    service: svc
                    path: /a
                """);
        assertThat(scenario.steps().get(0).id()).isEqualTo("rest.get#0");
    }

    @Test
    @DisplayName("an explicit description overrides the derived subtitle")
    void explicitDescription_wins() {
        Scenario scenario = parser.parse("""
                id: flow
                environment: ift
                steps:
                  - id: s
                    type: rest.get
                    description: custom subtitle
                    service: svc
                    path: /a
                """);
        assertThat(scenario.steps().get(0).description()).isEqualTo("custom subtitle");
    }

    @Test
    @DisplayName("unknown top-level and step fields are rejected fail-closed")
    void unknownFields_rejected() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps: []\nextra: 1\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("extra");
        assertThatThrownBy(() -> parser.parse("""
                id: f
                environment: ift
                steps:
                  - id: s
                    type: rest.get
                    service: svc
                    path: /a
                    bogus: 1
                """)).isInstanceOf(StandTestException.class).hasMessageContaining("bogus");
    }

    @Test
    @DisplayName("missing id/environment/steps are rejected")
    void missingEnvelope_rejected() {
        assertThatThrownBy(() -> parser.parse("environment: ift\nsteps: []\n")).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> parser.parse("id: f\nsteps: []\n")).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\n")).isInstanceOf(StandTestException.class).hasMessageContaining("steps");
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps: []\n")).isInstanceOf(StandTestException.class).hasMessageContaining("at least one");
    }

    @Test
    @DisplayName("a non-string tag is rejected")
    void nonStringTag_rejected() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\ntags: [1]\nsteps:\n  - type: rest.get\n    service: s\n    path: /a\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("tags");
    }

    @Test
    @DisplayName("inline body.json and payload.json are rejected as not-yet-executable")
    void inlineJson_rejected() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: rest.post\n    service: s\n    path: /a\n    body:\n      json: {x: 1}\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("body.json");
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: kafka.send\n    topic: t\n    payload:\n      json: {x: 1}\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("payload.json");
    }

    @Test
    @DisplayName("an AI rest step carries assert with matchers onto the wire — the historical 'unknown field' rejection is gone")
    void restAssert_withMatchers_mapsToWire() {
        Scenario scenario = parser.parse("""
                {
                  "id": "flow", "environment": "ift",
                  "steps": [
                    {"id":"q","type":"rest.get","service":"s","path":"/x",
                     "expect":{"status":200},
                     "assert":[{"path":"$.status","equals":"DONE"},
                               {"path":"$.list","contains":"P_AS"},
                               {"path":"$.error","exists":false}]}
                  ]
                }
                """);

        Map<String, Object> rest = params(scenario.steps().get(0));
        assertThat(rest.get("assertions")).isEqualTo(List.of(
                Map.of("jsonPath", "$.status", "expectedValue", "DONE"),
                Map.of("jsonPath", "$.list", "expectedValue", "P_AS", "matcher", "CONTAINS"),
                Map.of("jsonPath", "$.error", "expectedValue", false, "matcher", "EXISTS")));
    }

    @Test
    @DisplayName("an AI rest.expectEventually maps timeout and expectations onto the polling wire keys")
    void restExpectEventually_mapsToWire() {
        Scenario scenario = parser.parse("""
                {
                  "id": "flow", "environment": "ift",
                  "steps": [
                    {"id":"wait","type":"rest.expectEventually","service":"s","path":"/status",
                     "timeout":"20s","expect":{"status":200},
                     "assert":[{"path":"$.status","equals":"DONE"}],
                     "capture":{"requestId":"$.requestId"}}
                  ]
                }
                """);

        ScenarioStep step = scenario.steps().get(0);
        assertThat(step.type()).isEqualTo("rest.expectEventually");
        Map<String, Object> wire = params(step);
        assertThat(wire)
                .containsEntry("method", "GET")
                .containsEntry("service", "s")
                .containsEntry("timeoutMillis", 20_000L)
                .containsEntry("expectedStatus", 200);
        assertThat(wire.get("assertions")).isEqualTo(List.of(Map.of("jsonPath", "$.status", "expectedValue", "DONE")));
        assertThat(wire.get("captures")).isEqualTo(List.of(Map.of("variableName", "requestId", "jsonPath", "$.requestId")));
    }

    @Test
    @DisplayName("a non-equals matcher on kafka.expect and rowExists are rejected as not-yet-executable")
    void unsupportedMatchers_rejected() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: kafka.expect\n    topic: t\n    timeout: 5s\n    assert:\n      - path: $.x\n        exists: true\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("equals' only");
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: db.expectEventually\n    datasource: d\n    timeout: 5s\n    query: SELECT 1\n    expect:\n      rowExists: true\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("rowExists");
    }

    @Test
    @DisplayName("an assertion without a non-null equals is rejected")
    void assertWithoutEquals_rejected() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: kafka.expect\n    topic: t\n    timeout: 5s\n    assert:\n      - path: $.x\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("equals");
    }

    @Test
    @DisplayName("unknown types and db.query are rejected as unsupported")
    void unsupportedTypes_rejected() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: http.call\n    service: s\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: db.query\n    datasource: d\n    sql: SELECT 1\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("Unsupported");
    }

    @Test
    @DisplayName("a grpc.unary step maps AI fields onto the exact wire keys")
    void grpcUnary_mapsToWireKeys() {
        Scenario scenario = parser.parse("""
                {
                  "id": "grpc-flow", "environment": "ift",
                  "steps": [
                    {"id":"charge","type":"grpc.unary","target":"billing-grpc",
                     "method":"billing.BillingService/Charge","correlation":{"inject":true},
                     "request":{"fixture":"fixtures/charge.json"},"timeout":"5s",
                     "expect":{"assert":[{"path":"$.status","equals":"OK"}]},
                     "capture":{"chargeId":"$.chargeId"}}
                  ]
                }
                """);

        Map<String, Object> params = params(scenario.steps().get(0));
        assertThat(params)
                .containsEntry("target", "billing-grpc")
                .containsEntry("methodFullName", "billing.BillingService/Charge")
                .containsEntry("deadlineMillis", 5000L)
                .containsEntry("injectCorrelationId", true)
                .containsEntry("requestResource", "fixtures/charge.json");
        assertThat(params.get("assertions")).isEqualTo(List.of(Map.of("jsonPath", "$.status", "expectedValue", "OK")));
        assertThat(params.get("captures")).isEqualTo(List.of(Map.of("variableName", "chargeId", "jsonPath", "$.chargeId")));
    }

    @Test
    @DisplayName("grpc.unary fails closed on inline request.json, expect.status and unknown fields")
    void grpcUnary_failsClosedOnNonExecutable() {
        String base = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"c\",\"type\":\"grpc.unary\","
                + "\"target\":\"t\",\"method\":\"p.S/M\",\"timeout\":\"5s\",";
        assertThatThrownBy(() -> parser.parse(base + "\"request\":{\"json\":{}}}]}"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("request.json");
        assertThatThrownBy(() -> parser.parse(base + "\"expect\":{\"status\":\"OK\"}}]}"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("expect.status");
        assertThatThrownBy(() -> parser.parse(base + "\"bogus\":1}]}"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("bogus");
    }

    @Test
    @DisplayName("grpc.unary maps non-equals matchers (exists/contains) onto the wire keys — the full matcher set")
    void grpcUnary_fullMatcherSet() {
        Scenario scenario = parser.parse("{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"c\",\"type\":\"grpc.unary\","
                + "\"target\":\"t\",\"method\":\"p.S/M\",\"timeout\":\"5s\","
                + "\"expect\":{\"assert\":[{\"path\":\"$.status\",\"exists\":true},{\"path\":\"$.msg\",\"contains\":\"OK\"}]}}]}");

        assertThat(params(scenario.steps().get(0)).get("assertions")).isEqualTo(List.of(
                Map.of("jsonPath", "$.status", "expectedValue", true, "matcher", "EXISTS"),
                Map.of("jsonPath", "$.msg", "expectedValue", "OK", "matcher", "CONTAINS")));
    }

    @Test
    @DisplayName("db.expectEventually requires expect.singleValue (non-null)")
    void dbExpect_requiresSingleValue() {
        assertThatThrownBy(() -> parser.parse("id: f\nenvironment: ift\nsteps:\n  - type: db.expectEventually\n    datasource: d\n    timeout: 5s\n    query: SELECT 1\n"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("singleValue");
    }

    @Test
    @DisplayName("parseResource reads a classpath document and rejects a missing one")
    void parseResource_behaviour() {
        assertThatThrownBy(() -> parser.parseResource("ai/does-not-exist.json"))
                .isInstanceOf(StandTestException.class).hasMessageContaining("not found");
    }

    @Test
    @DisplayName("null document is rejected")
    void nullDocument_rejected() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
