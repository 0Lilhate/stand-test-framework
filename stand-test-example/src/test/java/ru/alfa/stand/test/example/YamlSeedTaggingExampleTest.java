package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.scenario.YamlScenarioParser;

/**
 * End-to-end proof of the seed-tagging follow-up: a given/then YAML {@code db.seed} that declares
 * {@code taggedByTestRunId} parses (scenario-yaml {@code DbStepTranslator}), flows the tag column into the
 * {@code GenericStep}, and executes through the DB write-guard against H2 — while a YAML seed that omits it is
 * refused at runtime exactly like the Java-DSL path (parallel isolation, plan §15). The AI steps/type schema
 * has no {@code db.seed} (writes are out of its MVP scope), so this surface is the only one that needs it.
 */
class YamlSeedTaggingExampleTest {

    private final YamlScenarioParser parser = new YamlScenarioParser();

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("a given/then YAML seed with taggedByTestRunId is executed successfully against H2")
    void yamlSeedWithTagColumnExecutes() {
        Scenario scenario = parser.parse("""
                id: yaml-seed-flow
                env: ift
                given:
                  - db.seed:
                      datasource: mainDb
                      sql: INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)
                      params:
                        id: yaml-${testRunId}
                      taggedByTestRunId: test_run_id
                then:
                  - db.expectEventually:
                      datasource: mainDb
                      sql: SELECT status FROM test_data.orders WHERE id = :id
                      params:
                        id: yaml-${testRunId}
                      equals: NEW
                      timeout: 2s
                  - db.cleanup:
                      datasource: mainDb
                      sql: DELETE FROM test_data.orders
                      whereTestRunId: test_run_id
                """);

        ScenarioResult result = ExampleStand.stand(ExampleStand.dbRegistry()).run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("a given/then YAML seed WITHOUT taggedByTestRunId is refused at runtime by the write-guard")
    void yamlSeedWithoutTagColumnIsRefused() {
        Scenario scenario = parser.parse("""
                id: yaml-untagged-seed
                env: ift
                given:
                  - db.seed:
                      datasource: mainDb
                      sql: INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)
                      params:
                        id: yaml-untagged-${testRunId}
                """);
        StandClient stand = ExampleStand.stand(ExampleStand.dbRegistry());

        assertThatThrownBy(() -> stand.run(scenario))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("must declare its testRunId tag column");
    }
}
