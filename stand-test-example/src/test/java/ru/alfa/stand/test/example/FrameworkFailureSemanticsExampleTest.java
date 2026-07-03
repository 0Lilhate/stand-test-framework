package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.rest.RestStep;

/**
 * The failure-semantics proof (plan §8.3): an unmet step assertion is raised as a
 * {@link StandTestAssertionError} — an {@link AssertionError}, so plain JUnit sees a FAILED test, never
 * an error — and the reporting side-channel does not hide it: the step is rendered FAILED with the
 * assertion message and its diagnostics attachment. Offline: the "service" is the in-process HTTP double
 * answering with a status the scenario does not expect. Deterministic — no waits, no external systems.
 */
class FrameworkFailureSemanticsExampleTest {

    @Test
    @DisplayName("an unmet REST assertion is thrown as a JUnit-visible StandTestAssertionError and reported as FAILED")
    void unmetAssertion_isThrownAndReportedAsFailed() {
        try (ExampleHttpServer service = new ExampleHttpServer(200, "{\"requestId\":\"neg-1\",\"status\":\"REJECTED\"}")) {
            CapturingAllureLifecycleFacade allure = new CapturingAllureLifecycleFacade();
            StandClient stand = ExampleStand.stand(
                    ExampleStand.registry(service.baseUrl()), new AllureReportingEventPublisher(allure));
            Scenario scenario = Scenario.builder("failure-semantics-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(RestStep.post(ExampleStand.SERVICE, "/api/requests")
                            .id("create-request")
                            .body("{\"amount\":1}")
                            .injectCorrelationId()
                            .expectStatus(200)
                            .assertPath("$.status", "ACCEPTED")
                            .build())
                    .build();

            assertThatThrownBy(() -> stand.run(scenario))
                    .isInstanceOf(StandTestAssertionError.class)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("$.status")
                    .hasMessageContaining("ACCEPTED");

            // Reporting does not swallow the failure: the step was started, finished FAILED with the
            // assertion message, and its diagnostics were attached for the report.
            assertThat(allure.startedStepNames()).hasSize(1);
            assertThat(allure.stepStatuses()).containsExactly(AllureStatus.FAILED);
            assertThat(allure.stoppedSteps()).isEqualTo(1);
            assertThat(allure.attachmentNames()).contains("diagnostics");
        }
    }
}
