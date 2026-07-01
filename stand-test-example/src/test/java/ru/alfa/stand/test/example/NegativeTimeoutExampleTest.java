package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;

/**
 * Example: the await/timeout path. An {@code expectEventually} whose row never appears times out and is
 * raised as a JUnit-native {@link StandTestAssertionError} (no {@code Thread.sleep} — the SDK await owns
 * the wait), carrying the diagnostics a reader needs.
 */
class NegativeTimeoutExampleTest {

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("an expectEventually that never matches times out as a StandTestAssertionError")
    void expectEventually_timesOut() {
        StandClient stand = ExampleStand.stand(ExampleStand.dbRegistry());
        Scenario scenario = Scenario.builder("db-timeout-example")
                .environment(ExampleStand.ENVIRONMENT)
                .step(DbStep.expectEventually(ExampleStand.DATASOURCE)
                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                        .param("id", "never-present")
                        .expectValue("DONE")
                        .withinSeconds(1)
                        .build())
                .build();

        assertThatThrownBy(() -> stand.run(scenario))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("did not observe the expected value");
    }
}
