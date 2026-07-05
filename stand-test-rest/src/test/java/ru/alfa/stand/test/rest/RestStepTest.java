package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class RestStepTest {

    @Test
    @DisplayName("get builds a rest.get GenericStep with method/service/path parameters")
    void get_buildsGenericStep() {
        ScenarioStep step = RestStep.get("client-service", "/api/items").build();
        assertThat(step).isInstanceOf(GenericStep.class);
        assertThat(step.type()).isEqualTo("rest.get");
        Map<String, Object> parameters = ((GenericStep) step).parameters();
        assertThat(parameters)
                .containsEntry(RestStepParameters.METHOD, "GET")
                .containsEntry(RestStepParameters.SERVICE, "client-service")
                .containsEntry(RestStepParameters.PATH, "/api/items");
    }

    @Test
    @DisplayName("default id derives from method and path; explicit id overrides it")
    void defaultAndExplicitId() {
        assertThat(RestStep.get("svc", "/x").build().id()).isEqualTo("GET /x");
        assertThat(RestStep.get("svc", "/x").id("custom").build().id()).isEqualTo("custom");
    }

    @Test
    @DisplayName("post stores body, header, query, status, captures and assertions")
    void post_storesAllParameters() {
        GenericStep step = (GenericStep) RestStep.post("svc", "/api/request")
                .body("{\"a\":1}")
                .header("Content-Type", "application/json")
                .query("page", "1")
                .injectCorrelationId()
                .expectStatus(201)
                .capture("requestId", "$.requestId")
                .assertPath("$.status", "OK")
                .build();
        Map<String, Object> parameters = step.parameters();
        assertThat(step.type()).isEqualTo("rest.post");
        assertThat(parameters)
                .containsEntry(RestStepParameters.BODY, "{\"a\":1}")
                .containsEntry(RestStepParameters.EXPECTED_STATUS, 201)
                .containsEntry(RestStepParameters.INJECT_CORRELATION_ID, true);
        assertThat(asStringMap(parameters.get(RestStepParameters.HEADERS))).containsEntry("Content-Type", "application/json");
        assertThat(asStringMap(parameters.get(RestStepParameters.QUERY))).containsEntry("page", "1");
        assertThat(RestStepParameters.captures(parameters)).containsExactly(new RestCapture("requestId", "$.requestId"));
        assertThat(RestStepParameters.assertions(parameters)).containsExactly(new RestAssertion("$.status", "OK"));
    }

    @Test
    @DisplayName("the produced parameter map is immutable")
    void parametersAreImmutable() {
        Map<String, Object> parameters = ((GenericStep) RestStep.get("svc", "/x").build()).parameters();
        assertThatThrownBy(() -> parameters.put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("each matcher method writes its wire record; plain assertPath stays matcher-less (equals)")
    void matcherMethods_writeWireRecords() {
        GenericStep step = (GenericStep) RestStep.get("svc", "/x")
                .assertPath("$.a", "v")
                .assertPathContains("$.b", "part")
                .assertPathMatches("$.c", "r-[0-9]+")
                .assertPathExists("$.d")
                .assertPathAbsent("$.e")
                .assertPathNotNull("$.f")
                .assertPathIsNull("$.g")
                .build();

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> assertions = (java.util.List<Map<String, Object>>) step.parameters().get(RestStepParameters.ASSERTIONS);
        assertThat(assertions).hasSize(7);
        assertThat(assertions.get(0)).doesNotContainKey(RestStepParameters.MATCHER);
        assertThat(assertions.get(1)).containsEntry(RestStepParameters.MATCHER, "CONTAINS").containsEntry(RestStepParameters.EXPECTED_VALUE, "part");
        assertThat(assertions.get(2)).containsEntry(RestStepParameters.MATCHER, "MATCHES").containsEntry(RestStepParameters.EXPECTED_VALUE, "r-[0-9]+");
        assertThat(assertions.get(3)).containsEntry(RestStepParameters.MATCHER, "EXISTS").containsEntry(RestStepParameters.EXPECTED_VALUE, true);
        assertThat(assertions.get(4)).containsEntry(RestStepParameters.MATCHER, "EXISTS").containsEntry(RestStepParameters.EXPECTED_VALUE, false);
        assertThat(assertions.get(5)).containsEntry(RestStepParameters.MATCHER, "NOT_NULL").containsEntry(RestStepParameters.EXPECTED_VALUE, true);
        assertThat(assertions.get(6)).containsEntry(RestStepParameters.MATCHER, "NOT_NULL").containsEntry(RestStepParameters.EXPECTED_VALUE, false);
    }

    @Test
    @DisplayName("expectEventually builds a rest.expectEventually GET step with timeout wire keys")
    void expectEventually_buildsPollStep() {
        GenericStep step = (GenericStep) RestStep.expectEventually("svc", "/api/status")
                .expectStatus(200)
                .withinSeconds(20)
                .pollInterval(java.time.Duration.ofMillis(250))
                .build();

        assertThat(step.type()).isEqualTo("rest.expectEventually");
        assertThat(step.id()).isEqualTo("EXPECT GET /api/status");
        assertThat(step.parameters())
                .containsEntry(RestStepParameters.METHOD, "GET")
                .containsEntry(RestStepParameters.TIMEOUT_MILLIS, 20_000L)
                .containsEntry(RestStepParameters.POLL_INTERVAL_MILLIS, 250L);
    }

    @Test
    @DisplayName("expectEventually defaults leave the timeout keys absent (executor defaults 30s/200ms apply)")
    void expectEventually_defaultsOmitTimeoutKeys() {
        GenericStep step = (GenericStep) RestStep.expectEventually("svc", "/x").expectStatus(200).build();

        assertThat(step.parameters())
                .doesNotContainKey(RestStepParameters.TIMEOUT_MILLIS)
                .doesNotContainKey(RestStepParameters.POLL_INTERVAL_MILLIS);
    }

    @Test
    @DisplayName("expectEventually validations: no expectation, a body, or poll knobs on a regular step all fail at build time")
    void expectEventually_buildValidations() {
        assertThatThrownBy(() -> RestStep.expectEventually("svc", "/x").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one expectation");
        assertThatThrownBy(() -> RestStep.expectEventually("svc", "/x").expectStatus(200).body("{}").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no request body");
        assertThatThrownBy(() -> RestStep.get("svc", "/x").withinSeconds(5).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only valid on an expectEventually step");
        assertThatThrownBy(() -> RestStep.expectEventually("svc", "/x").within(java.time.Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("setting both body and bodyFromResource fails at build time")
    void conflictingBodyFails() {
        RestStep step = RestStep.post("svc", "/x").body("a").bodyFromResource("f.json");
        assertThatThrownBy(step::build).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("blank service or path is rejected")
    void blankArgumentsRejected() {
        assertThatThrownBy(() -> RestStep.get(" ", "/x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestStep.get("svc", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object value) {
        return (Map<String, String>) value;
    }
}
