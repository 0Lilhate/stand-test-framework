// Java DSL test template — stand-test-sdk (Spring Boot starter wiring).
// Rules: SKILL.md in this directory (stand-test-java-dsl-authoring)
// Plain-JUnit variant: replace class annotations with @StandTest and take
// (StandClient stand, @StandScenarioId String id, @StandEnv String env) as method
// parameters — and pass id/env into the builder EXPLICITLY.
//
// Style (checkstyle-enforced in SDK-style repos): AssertJ only (JUnit Assertions import is
// banned), no System.out, one statement per line, blank line between members, Java-17 sources.

package replace.with.consumer.pkg;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.rest.RestStep;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "REPLACE_SERVICE_URL", matches = ".+")
class ReplaceScenarioNameTest {

    @Autowired
    private StandClient stand;

    @Test
    @DisplayName("Replace with the behaviour this test proves")
    void replaceScenarioName() {
        Scenario scenario = Scenario.builder("replace-scenario-id")
                .environment("replace-env-alias")
                .tag("integration")
                // Optional precondition (Java track only) — rows tagged with the reserved :testRunId bind:
                .step(DbStep.seed("replace-datasource-alias")
                        .id("seed-entity")
                        .sql("INSERT INTO test_data.entities(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                        .param("id", "entity-${testRunId}")
                        .build())
                // Trigger — alias only, correlation injected, id captured:
                .step(RestStep.post("replace-service-alias", "/api/entities")
                        .id("create-entity")
                        .header("Content-Type", "application/json")
                        .body("{\"externalId\":\"entity-${testRunId}\",\"amount\":100}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .assertPath("$.status", "ACCEPTED")
                        .capture("entityId", "$.entityId")
                        .build())
                // Await the async effect — bounded declarative poll, NEVER Thread.sleep:
                .step(RestStep.expectEventually("replace-service-alias", "/api/entities/${entityId}")
                        .id("await-processed")
                        .expectStatus(200)
                        .assertPath("$.status", "DONE")
                        .withinSeconds(30)
                        .build())
                // Verify the DB projection — single row, single value, equals-only:
                .step(DbStep.expectEventually("replace-datasource-alias")
                        .id("verify-projection")
                        .sql("SELECT status FROM test_data.entities WHERE id = :id")
                        .param("id", "entity-${testRunId}")
                        .expectValue("DONE")
                        .withinSeconds(10)
                        .build())
                // Cleanup — bare DELETE, no WHERE of your own; SDK appends WHERE <col> = :testRunId:
                .step(DbStep.cleanup("replace-datasource-alias")
                        .id("cleanup-entities")
                        .sql("DELETE FROM test_data.entities")
                        .whereTestRunId("test_run_id")
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }

    // Negative-path pattern (expected business failure):
    //
    // assertThatThrownBy(() -> stand.run(rejectionScenario))
    //         .isInstanceOf(StandTestAssertionError.class)
    //         .hasMessageContaining("$.status");
}
