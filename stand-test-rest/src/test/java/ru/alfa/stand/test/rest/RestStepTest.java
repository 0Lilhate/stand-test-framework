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
