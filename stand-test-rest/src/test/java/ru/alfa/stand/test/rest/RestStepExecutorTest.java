package ru.alfa.stand.test.rest;

import ru.alfa.stand.test.http.HttpCaller;
import ru.alfa.stand.test.http.RestResponse;
import ru.alfa.stand.test.http.BaseUrlResolver;
import ru.alfa.stand.test.http.FakeHttpCaller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class RestStepExecutorTest {

    private static final String BASE_URL = "http://stand.local";

    private final BaseUrlResolver passthrough = ref -> ref;

    private StepResult run(ScenarioStep step, FakeHttpCaller caller, StepExecutionContext context) {
        return new RestStepExecutor(caller, passthrough).execute(step, context);
    }

    private static StepExecutionContext context(EnvironmentRegistry registry, VariableStore store) {
        return RestTestSupport.context(registry, store);
    }

    private static FakeHttpCaller responding(int status, String body) {
        return new FakeHttpCaller().respondWith(new RestResponse(status, Map.of(), body));
    }

    private StepStatus assertionStatus(RestStep step, String body) {
        return run(step.build(), responding(200, body), context(RestTestSupport.registry(BASE_URL), new VariableStore())).status();
    }

    @Test
    @DisplayName("supports only rest.* step types")
    void supportsOnlyRestTypes() {
        RestStepExecutor executor = new RestStepExecutor(new FakeHttpCaller(), passthrough);
        assertThat(executor.supports("rest.get")).isTrue();
        assertThat(executor.supports("rest.post")).isTrue();
        assertThat(executor.supports("kafka.expect")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    @DisplayName("a GET happy path returns SUCCESS with request diagnostics")
    void getHappyPath() {
        FakeHttpCaller caller = responding(200, "{}");
        StepResult result = run(
                RestStep.get(RestTestSupport.SERVICE, "/api/items").expectStatus(200).build(),
                caller,
                context(RestTestSupport.registry(BASE_URL), new VariableStore()));
        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics())
                .containsEntry("http.method", "GET")
                .containsEntry("http.path", "/api/items")
                .containsEntry("http.status", 200);
        assertThat(caller.lastRequest().method()).isEqualTo("GET");
        assertThat(caller.lastRequest().body()).isNull();
    }

    @Test
    @DisplayName("the correlation id is injected into the outbound request when requested")
    void correlationIdInjected() {
        FakeHttpCaller caller = responding(200, "{}");
        StepExecutionContext context = context(RestTestSupport.registry(BASE_URL), new VariableStore());
        run(RestStep.post(RestTestSupport.SERVICE, "/x").injectCorrelationId().build(), caller, context);
        assertThat(caller.lastRequest().headers())
                .containsEntry(RestTestSupport.CORRELATION_HEADER, context.scenarioContext().correlationId().value());
    }

    @Test
    @DisplayName("matchers execute on a single-shot step: contains/exists/matches pass and fail precisely")
    void matchersExecuteOnSingleShot() {
        String body = "{\"list\":[\"PU_LST\",\"P_AS\"],\"id\":\"r-42\",\"nullField\":null}";
        assertThat(assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x")
                .assertPathContains("$.list", "P_AS")
                .assertPathMatches("$.id", "r-[0-9]+")
                .assertPathExists("$.nullField")
                .assertPathIsNull("$.nullField")
                .assertPathAbsent("$.missing")
                .assertPathNotNull("$.id"), body)).isEqualTo(StepStatus.SUCCESS);

        ScenarioStep failing = RestStep.get(RestTestSupport.SERVICE, "/x").assertPathContains("$.list", "STS").build();
        assertThatThrownBy(() -> run(failing, responding(200, body), context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("matcher CONTAINS")
                .hasMessageContaining("expected <STS>");
    }

    @Test
    @DisplayName("a service with an auth config gets the resolved Authorization header injected")
    void authHeaderInjected() {
        FakeHttpCaller caller = responding(200, "{}");
        StepExecutionContext context = context(RestTestSupport.registryWithBasicAuth(BASE_URL), new VariableStore());
        RestStepExecutor executor = new RestStepExecutor(caller, passthrough, auth -> "Basic dGVzdA==");

        executor.execute(RestStep.get(RestTestSupport.SERVICE, "/x").build(), context);

        assertThat(caller.lastRequest().headers()).containsEntry("Authorization", "Basic dGVzdA==");
    }

    @Test
    @DisplayName("a service without an auth config gets no Authorization header and never touches the auth resolver")
    void noAuth_resolverNotCalled() {
        FakeHttpCaller caller = responding(200, "{}");
        StepExecutionContext context = context(RestTestSupport.registry(BASE_URL), new VariableStore());
        RestStepExecutor executor = new RestStepExecutor(caller, passthrough, auth -> {
            throw new StandTestException("the auth resolver must not be called for a service without auth");
        });

        executor.execute(RestStep.get(RestTestSupport.SERVICE, "/x").build(), context);

        assertThat(caller.lastRequest().headers()).doesNotContainKey("Authorization");
    }

    @Test
    @DisplayName("auth and correlation injection compose on one request")
    void authAndCorrelation_bothInjected() {
        FakeHttpCaller caller = responding(200, "{}");
        StepExecutionContext context = context(RestTestSupport.registryWithBasicAuth(BASE_URL), new VariableStore());
        RestStepExecutor executor = new RestStepExecutor(caller, passthrough, auth -> "Basic dGVzdA==");

        executor.execute(RestStep.post(RestTestSupport.SERVICE, "/x").injectCorrelationId().build(), context);

        assertThat(caller.lastRequest().headers())
                .containsEntry("Authorization", "Basic dGVzdA==")
                .containsEntry(RestTestSupport.CORRELATION_HEADER, context.scenarioContext().correlationId().value());
    }

    @Test
    @DisplayName("correlation is injected by default when the service declares a HEADER carrier; an explicit opt-out and a carrier-less service both skip it")
    void correlationDefaultOnAndOptOut() {
        // Default-on: a HEADER-carrier service injects even without an explicit builder call.
        FakeHttpCaller onByDefault = responding(200, "{}");
        StepExecutionContext defaultCtx = context(RestTestSupport.registry(BASE_URL), new VariableStore());
        run(RestStep.get(RestTestSupport.SERVICE, "/x").build(), onByDefault, defaultCtx);
        assertThat(onByDefault.lastRequest().headers())
                .containsEntry(RestTestSupport.CORRELATION_HEADER, defaultCtx.scenarioContext().correlationId().value());

        // Explicit opt-out: no header even though the service declares a carrier.
        FakeHttpCaller optOut = responding(200, "{}");
        run(RestStep.get(RestTestSupport.SERVICE, "/x").injectCorrelationId(false).build(), optOut, context(RestTestSupport.registry(BASE_URL), new VariableStore()));
        assertThat(optOut.lastRequest().headers()).doesNotContainKey(RestTestSupport.CORRELATION_HEADER);

        // No carrier declared: nothing to inject and no error.
        FakeHttpCaller noCarrier = responding(200, "{}");
        run(RestStep.get(RestTestSupport.SERVICE, "/x").build(), noCarrier, context(RestTestSupport.registryWithoutCorrelation(BASE_URL), new VariableStore()));
        assertThat(noCarrier.lastRequest().headers()).doesNotContainKey(RestTestSupport.CORRELATION_HEADER);
    }

    @Test
    @DisplayName("requesting correlation injection without a HEADER config is an infrastructure error")
    void correlationInjectionWithoutConfigFails() {
        FakeHttpCaller caller = responding(200, "{}");
        StepExecutionContext context = context(RestTestSupport.registryWithoutCorrelation(BASE_URL), new VariableStore());
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").injectCorrelationId().build();
        assertThatThrownBy(() -> run(step, caller, context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("HEADER correlation");
    }

    @Test
    @DisplayName("${...} placeholders in path and body are substituted from the variable store")
    void substitutesVariables() {
        FakeHttpCaller caller = responding(200, "{}");
        VariableStore store = new VariableStore();
        store.put("itemId", "42");
        run(
                RestStep.post(RestTestSupport.SERVICE, "/items/${itemId}").body("{\"id\":\"${itemId}\"}").build(),
                caller,
                context(RestTestSupport.registry(BASE_URL), store));
        assertThat(caller.lastRequest().path()).isEqualTo("/items/42");
        assertThat(caller.lastRequest().body()).isEqualTo("{\"id\":\"42\"}");
    }

    @Test
    @DisplayName("an unresolved ${...} variable is an infrastructure error")
    void missingVariableFails() {
        FakeHttpCaller caller = responding(200, "{}");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/items/${missing}").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unresolved variable");
    }

    @Test
    @DisplayName("a status mismatch raises an assertion error")
    void statusMismatchRaisesAssertionError() {
        FakeHttpCaller caller = responding(500, "{}");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").expectStatus(200).build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("Expected HTTP status 200");
    }

    @Test
    @DisplayName("matching assertions pass; numbers compare by value (100 == 100.0), other types match strictly")
    void assertPathTypeAwareMatching() {
        assertThat(assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.status", "OK"), "{\"status\":\"OK\"}")).isEqualTo(StepStatus.SUCCESS);
        assertThat(assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.active", true), "{\"active\":true}")).isEqualTo(StepStatus.SUCCESS);
        assertThat(assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.amount", 100), "{\"amount\":100.0}")).isEqualTo(StepStatus.SUCCESS);
        assertThat(assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.amount", 100.0), "{\"amount\":100}")).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("a type change (boolean→string, string vs number) fails rather than being string-coerced")
    void assertPathRejectsTypeMismatch() {
        assertThatThrownBy(() -> assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.active", true), "{\"active\":\"true\"}"))
                .isInstanceOf(StandTestAssertionError.class);
        assertThatThrownBy(() -> assertionStatus(RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.amount", "100"), "{\"amount\":100}"))
                .isInstanceOf(StandTestAssertionError.class);
    }

    @Test
    @DisplayName("a failing JSONPath assertion raises an assertion error")
    void assertPathMismatchRaisesAssertionError() {
        FakeHttpCaller caller = responding(200, "{\"status\":\"FAIL\"}");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.status", "OK").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("JSONPath assertion failed");
    }

    @Test
    @DisplayName("captured response values are written to the variable store")
    void captureStoresVariable() {
        VariableStore store = new VariableStore();
        run(
                RestStep.post(RestTestSupport.SERVICE, "/x").capture("requestId", "$.requestId").build(),
                responding(200, "{\"requestId\":\"abc-1\"}"),
                context(RestTestSupport.registry(BASE_URL), store));
        assertThat(store.get("requestId")).contains("abc-1");
    }

    @Test
    @DisplayName("capturing a missing JSONPath raises an assertion error")
    void captureMissingPathFails() {
        FakeHttpCaller caller = responding(200, "{}");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").capture("requestId", "$.requestId").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class);
    }

    @Test
    @DisplayName("an unknown service alias is an infrastructure error")
    void unknownServiceFails() {
        FakeHttpCaller caller = responding(200, "{}");
        ScenarioStep step = RestStep.get("unknown-service", "/x").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not whitelisted");
    }

    @Test
    @DisplayName("an unknown environment is reported distinctly from an unknown service")
    void unknownEnvironmentFails() {
        FakeHttpCaller caller = responding(200, "{}");
        EnvironmentRegistry empty = new InMemoryEnvironmentRegistry(Map.of());
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").build();
        assertThatThrownBy(() -> run(step, caller, context(empty, new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Environment 'ift' is not whitelisted");
    }

    @Test
    @DisplayName("a step that is not a GenericStep is rejected")
    void nonGenericStepFails() {
        FakeHttpCaller caller = new FakeHttpCaller();
        StepExecutionContext context = context(RestTestSupport.registry(BASE_URL), new VariableStore());
        assertThatThrownBy(() -> run(new StubStep("x", "rest.get", ""), caller, context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("GenericStep");
    }

    @Test
    @DisplayName("the request body is loaded from a classpath resource and resolved")
    void bodyFromResourceIsLoadedAndResolved() {
        FakeHttpCaller caller = responding(200, "{}");
        StepExecutionContext context = context(RestTestSupport.registry(BASE_URL), new VariableStore());
        run(RestStep.post(RestTestSupport.SERVICE, "/x").bodyFromResource("fixtures/request.json").build(), caller, context);
        assertThat(caller.lastRequest().body())
                .contains(context.scenarioContext().correlationId().value())
                .contains("\"amount\": 100");
    }

    @Test
    @DisplayName("a malformed-JSON response body fails an assertion as an assertion error")
    void invalidJsonWithAssertionFails() {
        FakeHttpCaller caller = responding(200, "{bad");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.status", "OK").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    @DisplayName("an empty response body on an assert/capture step is an assertion error, not a raw exception")
    void emptyBodyWithAssertionFails() {
        FakeHttpCaller caller = responding(200, "");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.status", "OK").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("capturing a JSONPath that resolves to JSON null raises an assertion error")
    void captureNullValueFails() {
        FakeHttpCaller caller = responding(200, "{\"requestId\":null}");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").capture("requestId", "$.requestId").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("requestId");
    }

    @Test
    @DisplayName("query parameters are resolved and placed on the request")
    void queryParametersAreResolved() {
        FakeHttpCaller caller = responding(200, "{}");
        VariableStore store = new VariableStore();
        store.put("page", "2");
        run(RestStep.get(RestTestSupport.SERVICE, "/x").query("page", "${page}").query("size", "10").build(), caller, context(RestTestSupport.registry(BASE_URL), store));
        assertThat(caller.lastRequest().query()).containsEntry("page", "2").containsEntry("size", "10");
    }

    @Test
    @DisplayName("multiple assertions and a capture run together against one response")
    void multipleAssertionsAndCaptureTogether() {
        FakeHttpCaller caller = responding(200, "{\"a\":1,\"b\":\"x\",\"id\":\"id-9\"}");
        VariableStore store = new VariableStore();
        StepResult result = run(
                RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.a", 1).assertPath("$.b", "x").capture("id", "$.id").build(),
                caller,
                context(RestTestSupport.registry(BASE_URL), store));
        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(store.get("id")).contains("id-9");
    }

    @Test
    @DisplayName("the second of several assertions is still enforced")
    void secondAssertionIsEnforced() {
        FakeHttpCaller caller = responding(200, "{\"a\":1,\"b\":\"x\"}");
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").assertPath("$.a", 1).assertPath("$.b", "WRONG").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("$.b");
    }

    @Test
    @DisplayName("a missing body resource is an infrastructure error")
    void missingBodyResourceFails() {
        FakeHttpCaller caller = responding(200, "{}");
        ScenarioStep step = RestStep.post(RestTestSupport.SERVICE, "/x").bodyFromResource("fixtures/does-not-exist.json").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not found on classpath");
    }

    @Test
    @DisplayName("a transport failure from the caller propagates unchanged")
    void transportFailurePropagates() {
        FakeHttpCaller caller = new FakeHttpCaller().failWith(new StandTestException("connect refused"));
        ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/x").build();
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("connect refused");
    }

    @Test
    @DisplayName("PUT and DELETE build and execute through the same path")
    void putAndDeleteExecute() {
        FakeHttpCaller putCaller = responding(200, "{}");
        run(RestStep.put(RestTestSupport.SERVICE, "/items/1").body("{}").build(), putCaller, context(RestTestSupport.registry(BASE_URL), new VariableStore()));
        assertThat(putCaller.lastRequest().method()).isEqualTo("PUT");
        FakeHttpCaller deleteCaller = responding(204, "");
        run(RestStep.delete(RestTestSupport.SERVICE, "/items/1").build(), deleteCaller, context(RestTestSupport.registry(BASE_URL), new VariableStore()));
        assertThat(deleteCaller.lastRequest().method()).isEqualTo("DELETE");
        assertThat(deleteCaller.lastRequest().body()).isNull();
    }

    @Test
    @DisplayName("a structurally invalid parameter map fails before any HTTP call (fail-fast)")
    void schemaValidationIsFailFast() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(RestStepParameters.METHOD, "GET");
        parameters.put(RestStepParameters.SERVICE, RestTestSupport.SERVICE);
        parameters.put(RestStepParameters.PATH, "/x");
        parameters.put(RestStepParameters.ASSERTIONS, "not-a-list");
        ScenarioStep step = new GenericStep("s", "rest.get", "", parameters);
        FakeHttpCaller caller = responding(200, "{}");
        assertThatThrownBy(() -> run(step, caller, context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestException.class);
        assertThat(caller.lastRequest()).isNull();
    }

    @Test
    @DisplayName("a parameter map with both body and bodyResource is rejected as an infrastructure error")
    void bothBodyAndResourceInMapFails() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(RestStepParameters.METHOD, "POST");
        parameters.put(RestStepParameters.SERVICE, RestTestSupport.SERVICE);
        parameters.put(RestStepParameters.PATH, "/x");
        parameters.put(RestStepParameters.BODY, "inline");
        parameters.put(RestStepParameters.BODY_RESOURCE, "fixtures/request.json");
        ScenarioStep step = new GenericStep("s", "rest.post", "", parameters);
        assertThatThrownBy(() -> run(step, responding(200, "{}"), context(RestTestSupport.registry(BASE_URL), new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not both");
    }

    private record StubStep(String id, String type, String description) implements ScenarioStep {
    }
}
