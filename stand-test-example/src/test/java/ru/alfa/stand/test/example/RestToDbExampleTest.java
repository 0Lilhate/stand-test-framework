package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Example: a cross-transport flow. A REST POST captures a service-generated id, a DB seed uses that
 * captured id via {@code ${requestId}}, and a DB await reads the row back — demonstrating how the per-run
 * variable store carries a value from one step (REST) to the next (DB).
 */
class RestToDbExampleTest {

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("a value captured from REST flows into later DB steps via the per-run variable store")
    void capturedValue_flowsFromRestToDb() {
        try (ExampleHttpServer service = new ExampleHttpServer(200, "{\"requestId\":\"flow-1\"}")) {
            StandClient stand = ExampleStand.stand(ExampleStand.registry(service.baseUrl()));
            Scenario scenario = Scenario.builder("rest-to-db-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(RestStep.post(ExampleStand.SERVICE, "/api/requests")
                            .body("{\"amount\":1}")
                            .injectCorrelationId()
                            .expectStatus(200)
                            .capture("requestId", "$.requestId")
                            .build())
                    .step(DbStep.seed(ExampleStand.DATASOURCE)
                            .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                            .param("id", "${requestId}")
                            .build())
                    .step(DbStep.expectEventually(ExampleStand.DATASOURCE)
                            .sql("SELECT status FROM test_data.orders WHERE id = :id")
                            .param("id", "${requestId}")
                            .expectValue("NEW")
                            .withinSeconds(2)
                            .build())
                    .step(DbStep.cleanup(ExampleStand.DATASOURCE)
                            .sql("DELETE FROM test_data.orders")
                            .whereTestRunId("test_run_id")
                            .build())
                    .build();

            ScenarioResult result = stand.run(scenario);

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.stepResults()).hasSize(4);
        }
    }
}
