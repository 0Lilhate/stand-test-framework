package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

/**
 * The UI adapter driven by the real {@code DefaultScenarioRunner} — validator, resource scope, variable
 * store and all — against a fake driver. This is where the ownership claims are actually proven: that the
 * session is closed on every outcome, that a failing close does not repaint a green run red, and that a
 * non-whitelisted application is refused before a browser is ever asked for.
 */
class UiScenarioRunnerIntegrationTest {

    private static final UiLocator AMOUNT = UiLocator.label("Amount");

    private static final UiLocator SUBMIT = UiLocator.role("button", "Confirm");

    private static final UiLocator STATUS = UiLocator.testId("status");

    private static final UiLocator NUMBER = UiLocator.testId("number");

    private final FakeUiDriver driver = new FakeUiDriver();

    @Test
    @DisplayName("the whole vertical slice runs: alias, session, actions, assertion, capture, and a closed browser at the end")
    void fullSliceRunsAndClosesTheSession() {
        this.driver.present(AMOUNT, "")
                .present(SUBMIT, "Confirm")
                .snapshot(STATUS, ElementSnapshot.absent(), new ElementSnapshot(true, true, true, "Accepted", null, Map.of()))
                .present(NUMBER, "AP-42");

        ScenarioResult result = runner().run(Scenario.builder("ui-slice")
                .environment(UiTestSupport.ENVIRONMENT)
                .tag("ui")
                .step(UiStep.open(UiTestSupport.APPLICATION, "/applications/new").id("open-form").injectCorrelationId().build())
                .step(UiStep.fill(UiTestSupport.APPLICATION, AMOUNT, "100000").id("fill-amount").build())
                .step(UiStep.click(UiTestSupport.APPLICATION, SUBMIT).id("submit").build())
                .step(UiStep.expectEventually(UiTestSupport.APPLICATION, STATUS)
                        .id("await-accepted")
                        .assertVisible()
                        .assertText("Accepted")
                        .capture("applicationNumber", NUMBER)
                        .within(Duration.ofSeconds(2))
                        .pollInterval(Duration.ofMillis(10))
                        .build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).extracting(step -> step.status()).containsOnly(StepStatus.SUCCESS);
        assertThat(this.driver.closed()).as("the run's browsing session must be closed by the runner").isTrue();
        assertThat(this.driver.extraHeaders()).containsKey(UiStepExecutor.CORRELATION_HEADER);
    }

    @Test
    @DisplayName("a failed run raises an AssertionError — JUnit and Allure see a failed test, not a swallowed status")
    void assertionFailureSurfacesAsAssertionErrorAndClosesTheSession() {
        this.driver.present(STATUS, "Rejected");

        assertThatThrownBy(() -> runner().run(Scenario.builder("ui-failing")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check").assertText("Accepted").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("check")
                .hasMessageContaining("Accepted");
        assertThat(this.driver.closed()).as("the browser must not outlive a failed scenario either").isTrue();
    }

    @Test
    @DisplayName("a driver that throws from close() does not turn a green run red")
    void closeFailureDoesNotChangeOutcome() {
        this.driver.present(STATUS, "Accepted").failCloseWith(new IllegalStateException("the browser refused to close"));

        ScenarioResult result = runner().run(Scenario.builder("ui-close-failure")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check").assertText("Accepted").build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("an application alias absent from the registry is refused before the run starts — no browser is opened")
    void nonWhitelistedApplicationIsRefusedBeforeTheBrowserStarts() {
        assertThatThrownBy(() -> runner().run(Scenario.builder("ui-unknown-alias")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.open("rogue-portal", "/new").id("open").build())
                .build()))
                .hasMessageContaining("NON_WHITELISTED_UI_APPLICATION");
        assertThat(this.driver.calls()).as("the guardrail must fire before any browser work").isEmpty();
    }

    @Test
    @DisplayName("a timeout beyond the SDK's bound is refused by the same generic validator that guards the other adapters")
    void unboundedTimeoutIsRefusedByTheValidator() {
        assertThatThrownBy(() -> runner().run(Scenario.builder("ui-unbounded")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expectEventually(UiTestSupport.APPLICATION, STATUS)
                        .id("await")
                        .assertVisible()
                        .within(Duration.ofHours(2))
                        .build())
                .build()))
                .hasMessageContaining("timeoutMillis");
    }

    @Test
    @DisplayName("infrastructure breakage stays infrastructure: it is not reported as a failed expectation")
    void driverBreakageIsNotAFailedExpectation() {
        this.driver.present(SUBMIT, "Confirm").failClickWith(new IllegalStateException("the browser went away"));

        assertThatThrownBy(() -> runner().run(Scenario.builder("ui-broken-driver")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.click(UiTestSupport.APPLICATION, SUBMIT).id("submit").build())
                .build()))
                .isInstanceOf(StandTestException.class)
                .isNotInstanceOf(AssertionError.class);
        assertThat(this.driver.closed()).isTrue();
    }

    private DefaultScenarioRunner runner() {
        EnvironmentRegistry registry = UiTestSupport.registry();
        UiStepExecutor executor = new UiStepExecutor((application, settings) -> this.driver, new EnvironmentUiApplicationResolver(name -> "http://localhost:8080"));
        return new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
    }
}
