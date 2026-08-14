package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.await.DefaultAwaiter;
import ru.alfa.stand.test.core.exception.DiagnosticAssertionError;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * The {@code rest.expectEventually} poll loop, driven offline: a queued {@link FakeHttpCaller}
 * sequence plays the stand's convergence, and a {@link FakeTimeSource}-backed awaiter resolves
 * timeouts instantly without real blocking.
 */
class RestStepExecutorPollTest {

    private static final String BASE_URL = "http://stand.local";

    private final BaseUrlResolver passthrough = ref -> ref;

    private static Awaiter fakeTimeAwaiter() {
        return new DefaultAwaiter(new FakeTimeSource());
    }

    private RestStepExecutor executor(FakeHttpCaller caller, Awaiter awaiter) {
        return new RestStepExecutor(caller, this.passthrough, auth -> {
            throw new StandTestException("no auth expected in poll tests");
        }, awaiter);
    }

    private static StepExecutionContext context(VariableStore store) {
        return RestTestSupport.context(RestTestSupport.registry(BASE_URL), store);
    }

    private static RestResponse json(int status, String body) {
        return new RestResponse(status, Map.of(), body);
    }

    @Test
    @DisplayName("a poll that is satisfied immediately succeeds on the first probe")
    void satisfiedImmediately() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(json(200, "{\"status\":\"DONE\"}"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .expectStatus(200)
                .assertPath("$.status", "DONE")
                .build();

        StepResult result = executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore()));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(caller.requests()).hasSize(1);
    }

    @Test
    @DisplayName("a poll keeps probing until the expected value appears, then captures from the final response")
    void satisfiedAfterRetries() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(
                json(200, "{\"status\":\"PENDING\"}"),
                json(200, "{\"status\":\"PENDING\"}"),
                json(200, "{\"status\":\"DONE\",\"requestId\":\"r-9\"}"));
        VariableStore store = new VariableStore();
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .assertPath("$.status", "DONE")
                .capture("requestId", "$.requestId")
                .build();

        StepResult result = executor(caller, fakeTimeAwaiter()).execute(step, context(store));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(caller.requests()).hasSize(3);
        assertThat(store.get("requestId")).contains("r-9");
    }

    @Test
    @DisplayName("a status that never matches times out as a JUnit-native assertion error naming the mismatch")
    void timeoutOnStatus() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(json(503, "{}"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .expectStatus(200)
                .withinSeconds(2)
                .build();

        assertThatThrownBy(() -> executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("rest.expectEventually")
                .hasMessageContaining("did not observe the expected response")
                .hasMessageContaining("Expected HTTP status 200 but got 503");
    }

    @Test
    @DisplayName("a timed-out poll carries the await's structured diagnostics into the report, not only into its message")
    void timeout_carriesReportableDiagnostics() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(json(503, "{}"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .expectStatus(200)
                .withinSeconds(2)
                .build();

        assertThatThrownBy(() -> executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore())))
                .isInstanceOf(DiagnosticAssertionError.class)
                .asInstanceOf(InstanceOfAssertFactories.type(DiagnosticAssertionError.class))
                .extracting(DiagnosticAssertionError::failureDiagnostics)
                .satisfies(diagnostics -> assertThat(diagnostics)
                        .containsEntry("timeout", "PT2S")
                        .containsEntry("rest.service", RestTestSupport.SERVICE)
                        .containsEntry("rest.path", "/api/status")
                        .containsKeys("await", "attempts", "elapsed", "pollInterval"));
    }

    @Test
    @DisplayName("an assertion that never matches times out with expected-vs-got and without the response body")
    void timeoutOnAssertion() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(json(200, "{\"status\":\"PENDING\",\"secret\":\"body-secret\"}"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .assertPath("$.status", "DONE")
                .build();

        assertThatThrownBy(() -> executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore())))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("expected <DONE> but got <PENDING>")
                .hasMessageContaining("HTTP 200")
                .hasMessageNotContaining("body-secret");
    }

    @Test
    @DisplayName("a transport failure aborts the poll immediately as an infrastructure error")
    void transportFailureAborts() {
        FakeHttpCaller caller = new FakeHttpCaller().failWith(new StandTestException("connection refused"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .expectStatus(200)
                .build();

        assertThatThrownBy(() -> executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("connection refused");
        assertThat(caller.requests()).hasSize(1);
    }

    @Test
    @DisplayName("an unparseable body is a not-yet observation, not an abort — the poll continues to the parseable one")
    void unparseableBodyPollsThrough() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(
                json(200, "not json"),
                json(200, "{\"status\":\"DONE\"}"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/status")
                .assertPath("$.status", "DONE")
                .build();

        StepResult result = executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore()));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(caller.requests()).hasSize(2);
    }

    @Test
    @DisplayName("matchers work inside the poll condition: contains over a list")
    void matcherInsidePoll() {
        FakeHttpCaller caller = new FakeHttpCaller().respondWith(
                json(200, "{\"packages\":[\"PU_LST\"]}"),
                json(200, "{\"packages\":[\"PU_LST\",\"P_AS\"]}"));
        ScenarioStep step = RestStep.expectEventually(RestTestSupport.SERVICE, "/api/packages")
                .assertPathContains("$.packages", "P_AS")
                .build();

        StepResult result = executor(caller, fakeTimeAwaiter()).execute(step, context(new VariableStore()));

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(caller.requests()).hasSize(2);
    }
}
