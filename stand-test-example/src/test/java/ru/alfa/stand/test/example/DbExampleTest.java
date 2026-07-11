package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;

/**
 * Example: a DB-only flow — seed a {@code testRunId}-tagged row (a write, allowed only on seed/cleanup),
 * await it with {@code expectEventually}, then soft-cleanup the run's own rows. Runs against H2.
 */
class DbExampleTest {

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("seed writes a testRunId-tagged row, expectEventually reads it back, cleanup removes it")
    void seed_expect_cleanup() {
        StandClient stand = ExampleStand.stand(ExampleStand.dbRegistry());
        Scenario scenario = Scenario.builder("db-example")
                .environment(ExampleStand.ENVIRONMENT)
                .step(DbStep.seed(ExampleStand.DATASOURCE)
                        .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'DONE', :testRunId)")
                        .taggedByTestRunId("test_run_id")
                        .param("id", "db-${testRunId}")
                        .build())
                .step(DbStep.expectEventually(ExampleStand.DATASOURCE)
                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                        .param("id", "db-${testRunId}")
                        .expectValue("DONE")
                        .withinSeconds(2)
                        .build())
                .step(DbStep.cleanup(ExampleStand.DATASOURCE)
                        .sql("DELETE FROM test_data.orders")
                        .whereTestRunId("test_run_id")
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).hasSize(3);
    }
}
