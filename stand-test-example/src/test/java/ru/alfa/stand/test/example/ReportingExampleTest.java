package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.allure.lifecycle.AllureLabel;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;

/**
 * Example: how the Allure adapter renders a run. The scenario is executed with an
 * {@link AllureReportingEventPublisher} backed by a capturing lifecycle facade, then the recorded calls
 * are asserted — showing the steps, status, test-case labels/parameters and the diagnostics attachment
 * that a real Allure report would carry.
 */
class ReportingExampleTest {

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("the Allure publisher renders the run as steps, labels, parameters and a diagnostics block")
    void allurePublisher_rendersStepsLabelsAndParameters() {
        CapturingAllureLifecycleFacade allure = new CapturingAllureLifecycleFacade();
        StandClient stand = ExampleStand.stand(ExampleStand.dbRegistry(), new AllureReportingEventPublisher(allure));
        Scenario scenario = Scenario.builder("reporting-example")
                .environment(ExampleStand.ENVIRONMENT)
                .tag("smoke")
                .step(DbStep.seed(ExampleStand.DATASOURCE)
                        .id("seed-order")
                        .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'DONE', :testRunId)")
                        .taggedByTestRunId("test_run_id")
                        .param("id", "rep-${testRunId}")
                        .build())
                .step(DbStep.cleanup(ExampleStand.DATASOURCE)
                        .sql("DELETE FROM test_data.orders")
                        .whereTestRunId("test_run_id")
                        .build())
                .build();

        stand.run(scenario);

        assertThat(allure.startedStepNames()).contains("db.seed seed-order");
        assertThat(allure.stepStatuses()).containsOnly(AllureStatus.PASSED);
        assertThat(allure.stoppedSteps()).isEqualTo(2);
        assertThat(allure.testLabels()).contains(new AllureLabel("tag", "smoke"));
        assertThat(allure.testParameters())
                .containsEntry("scenarioId", "reporting-example")
                .containsEntry("environment", ExampleStand.ENVIRONMENT)
                .containsKeys("testRunId", "correlationId");
        assertThat(allure.stepParameterSets()).anySatisfy(parameters -> assertThat(parameters)
                .containsEntry("stepType", "db.seed")
                .containsEntry("stepId", "seed-order")
                .containsKeys("scenarioId", "testRunId", "correlationId"));
        assertThat(allure.attachmentNames()).contains("diagnostics");
    }
}
