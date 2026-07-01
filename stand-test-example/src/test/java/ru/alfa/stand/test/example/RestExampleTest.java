package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Example: a single REST POST that injects the SDK-owned correlation id, asserts the response status and
 * a JSON field, and captures a value for later steps. Runs against a local {@link ExampleHttpServer}.
 */
class RestExampleTest {

    @Test
    @DisplayName("a REST POST injects the SDK correlation id, asserts status and JSON, and captures a value")
    void restPost_injectsCorrelationAssertsAndCaptures() {
        try (ExampleHttpServer service = new ExampleHttpServer(200, "{\"requestId\":\"r-1\",\"status\":\"ACCEPTED\"}")) {
            StandClient stand = ExampleStand.stand(ExampleStand.registry(service.baseUrl()));
            Scenario scenario = Scenario.builder("rest-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(RestStep.post(ExampleStand.SERVICE, "/api/requests")
                            .header("Content-Type", "application/json")
                            .body("{\"amount\":100}")
                            .injectCorrelationId()
                            .expectStatus(200)
                            .assertPath("$.status", "ACCEPTED")
                            .capture("requestId", "$.requestId")
                            .build())
                    .build();

            ScenarioResult result = stand.run(scenario);

            assertThat(result.isSuccessful()).isTrue();
            assertThat(service.receivedBody()).isEqualTo("{\"amount\":100}");
            assertThat(service.receivedHeader(ExampleStand.CORRELATION_HEADER)).isNotBlank();
        }
    }
}
