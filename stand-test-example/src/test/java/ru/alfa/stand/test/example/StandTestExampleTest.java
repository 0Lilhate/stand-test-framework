package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.junit.StandScenarioId;
import ru.alfa.stand.test.junit.StandEnv;
import ru.alfa.stand.test.junit.StandTest;
import ru.alfa.stand.test.rest.RestStep;

/**
 * The canonical {@code @StandTest} path: the JUnit extension injects a ready {@link StandClient} (no
 * hand-built runner) whose {@code EnvironmentRegistry} and Allure publisher are discovered via
 * {@link java.util.ServiceLoader}. The same REST→DB scenario as {@code RestToDbExampleTest} runs offline
 * against in-process doubles — but here the REST alias resolves through an env-ref ({@code CLIENT_SERVICE_URL})
 * rather than a passthrough literal, and {@link ExampleDoublesExtension} pins the doubles to that address.
 */
@StandTest(env = ExampleStand.ENVIRONMENT)
@StandScenarioId("standtest-rest-to-db")
@ExtendWith(ExampleDoublesExtension.class)
class StandTestExampleTest {

    @Test
    @DisplayName("@StandTest injects a StandClient that runs a REST→DB scenario against the discovered registry")
    void restToDb_viaStandTestInjection(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
        Scenario scenario = Scenario.builder(id)
                .environment(env)
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
