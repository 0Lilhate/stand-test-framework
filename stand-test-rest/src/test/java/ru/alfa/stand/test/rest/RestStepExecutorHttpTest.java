package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class RestStepExecutorHttpTest {

    @Test
    @DisplayName("a real GET injects the correlation header and captures from the live response")
    void realGetInjectsCorrelationAndCaptures() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{\"requestId\":\"r-1\"}")) {
            VariableStore store = new VariableStore();
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), store);
            ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/api/items")
                    .injectCorrelationId()
                    .expectStatus(200)
                    .capture("requestId", "$.requestId")
                    .build();
            StepResult result = RestTestSupport.liveExecutor().execute(step, context);
            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
            assertThat(server.capturedMethod()).isEqualTo("GET");
            assertThat(server.capturedPath()).isEqualTo("/api/items");
            assertThat(server.capturedHeader(RestTestSupport.CORRELATION_HEADER)).isEqualTo(context.scenarioContext().correlationId().value());
            assertThat(store.get("requestId")).contains("r-1");
        }
    }

    @Test
    @DisplayName("a literal-wrapped base URL rides through the PRODUCTION resolver end-to-end (Spring starter value fields)")
    void literalBaseUrlResolvesThroughProductionResolver() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{\"requestId\":\"r-9\"}")) {
            VariableStore store = new VariableStore();
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(SecretReferences.literal(server.baseUrl())), store);
            ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/api/items")
                    .expectStatus(200)
                    .capture("requestId", "$.requestId")
                    .build();
            RestStepExecutor executor = new RestStepExecutor(new WebClientHttpCaller(), new EnvironmentBaseUrlResolver());
            StepResult result = executor.execute(step, context);
            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
            assertThat(store.get("requestId")).contains("r-9");
        }
    }

    @Test
    @DisplayName("a real POST sends the request body")
    void realPostSendsBody() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(201, "{}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            ScenarioStep step = RestStep.post(RestTestSupport.SERVICE, "/api/request")
                    .header("Content-Type", "application/json")
                    .body("{\"amount\":100}")
                    .expectStatus(201)
                    .build();
            RestTestSupport.liveExecutor().execute(step, context);
            assertThat(server.capturedMethod()).isEqualTo("POST");
            assertThat(server.capturedBody()).isEqualTo("{\"amount\":100}");
        }
    }

    @Test
    @DisplayName("rest.expectEventually polls over real HTTP until the stand converges")
    void realExpectEventuallyPollsToDone() {
        try (RecordingHttpServer server = new RecordingHttpServer()
                .respondSequence(200, "{\"status\":\"PENDING\"}")
                .respondSequence(200, "{\"status\":\"PENDING\"}")
                .respondSequence(200, "{\"status\":\"DONE\"}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                    .expectStatus(200)
                    .assertPath("$.status", "DONE")
                    .withinSeconds(5)
                    .pollInterval(java.time.Duration.ofMillis(10))
                    .build();

            StepResult result = RestTestSupport.liveExecutor().execute(step, context);

            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        }
    }

    @Test
    @DisplayName("registry-driven basic auth reaches the wire as a correctly encoded Authorization header")
    void realBasicAuthReachesTheWire() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registryWithBasicAuth(server.baseUrl()), new VariableStore());
            EnvironmentAuthHeaderResolver authResolver = new EnvironmentAuthHeaderResolver(
                    java.util.Map.of("CLIENT_USER", "Aladdin", "CLIENT_PASSWORD", "open sesame")::get);
            RestStepExecutor executor = new RestStepExecutor(new WebClientHttpCaller(), ref -> ref, authResolver);

            executor.execute(RestStep.get(RestTestSupport.SERVICE, "/x").expectStatus(200).build(), context);

            assertThat(server.capturedHeader("Authorization")).isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==");
        }
    }

    @Test
    @DisplayName("a status mismatch against a live response raises an assertion error")
    void realStatusMismatch() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(500, "{}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").expectStatus(200).build();
            assertThatThrownBy(() -> RestTestSupport.liveExecutor().execute(step, context)).isInstanceOf(StandTestAssertionError.class);
        }
    }

    @Test
    @DisplayName("query parameters are transmitted and percent-encoded on the wire")
    void realQueryParameters() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/search").query("q", "a b").query("page", "2").expectStatus(200).build();
            RestTestSupport.liveExecutor().execute(step, context);
            assertThat(server.capturedRawQuery()).contains("q=a%20b").contains("page=2");
        }
    }

    @Test
    @DisplayName("a literal '+' in a query value is percent-encoded so a form-decoding server keeps it")
    void realQueryPlusIsEncoded() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/search").query("phone", "+7900").expectStatus(200).build();
            RestTestSupport.liveExecutor().execute(step, context);
            assertThat(server.capturedRawQuery()).contains("phone=%2B7900").doesNotContain("phone=+7900");
        }
    }

    @Test
    @DisplayName("PUT sends a body and DELETE sends none, over real HTTP")
    void realPutAndDelete() {
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            RestTestSupport.liveExecutor().execute(RestStep.put(RestTestSupport.SERVICE, "/items/1").body("{\"v\":1}").expectStatus(200).build(), context);
            assertThat(server.capturedMethod()).isEqualTo("PUT");
            assertThat(server.capturedBody()).isEqualTo("{\"v\":1}");
        }
        try (RecordingHttpServer server = new RecordingHttpServer().respond(204, "")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry(server.baseUrl()), new VariableStore());
            RestTestSupport.liveExecutor().execute(RestStep.delete(RestTestSupport.SERVICE, "/items/1").expectStatus(204).build(), context);
            assertThat(server.capturedMethod()).isEqualTo("DELETE");
            assertThat(server.capturedBody()).isEmpty();
        }
    }

    @Test
    @DisplayName("a connection failure surfaces as an infrastructure error")
    void connectionFailureIsInfrastructureError() {
        StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry("http://127.0.0.1:1"), new VariableStore());
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").build();
        assertThatThrownBy(() -> RestTestSupport.liveExecutor().execute(step, context)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("a base URL carrying userinfo credentials is redacted in the transport-failure message")
    void userInfoIsRedactedInFailureMessage() {
        StepExecutionContext context = RestTestSupport.context(RestTestSupport.registry("http://user:secretpass@127.0.0.1:1"), new VariableStore());
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").build();
        assertThatThrownBy(() -> RestTestSupport.liveExecutor().execute(step, context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("***@127.0.0.1:1")
                .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("secretpass"));
    }
}
