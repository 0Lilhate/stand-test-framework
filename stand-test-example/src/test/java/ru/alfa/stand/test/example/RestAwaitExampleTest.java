package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Example: replacing a fixed sleep with a declarative REST poll. The double plays a converging stand
 * (PENDING → PENDING → DONE); {@code rest.expectEventually} GET-polls until the expectations hold and
 * captures from the final response — no {@code Thread.sleep} anywhere.
 */
class RestAwaitExampleTest {

    @Test
    @DisplayName("rest.expectEventually polls a converging endpoint to DONE and captures from the final response")
    void expectEventually_pollsToDone() {
        List<String> convergingBodies = List.of(
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"DONE\",\"processedAt\":\"2026-07-05T12:00:00Z\"}");
        try (ExampleHttpServer service = new ExampleHttpServer(200, convergingBodies, 0)) {
            StandClient stand = ExampleStand.stand(ExampleStand.registry(service.baseUrl()));
            Scenario scenario = Scenario.builder("rest-await-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(RestStep.expectEventually(ExampleStand.SERVICE, "/api/requests/r-1")
                            .expectStatus(200)
                            .assertPath("$.status", "DONE")
                            .withinSeconds(5)
                            .pollInterval(Duration.ofMillis(20))
                            .capture("processedAt", "$.processedAt")
                            .build())
                    .build();

            ScenarioResult result = stand.run(scenario);

            assertThat(result.isSuccessful()).isTrue();
        }
    }
}
