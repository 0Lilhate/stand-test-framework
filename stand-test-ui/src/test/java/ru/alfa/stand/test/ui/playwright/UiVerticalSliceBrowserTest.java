package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.ui.EnvironmentUiApplicationResolver;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiStep;
import ru.alfa.stand.test.ui.UiStepExecutor;

/**
 * The vertical slice end to end against a real Chromium: registry alias → browser → context → page →
 * action → assertion → the browser closed by the runner. Tagged {@code browser}.
 */
@Tag("browser")
class UiVerticalSliceBrowserTest {

    private static final String ENVIRONMENT = "ift";

    private static final String APPLICATION = "client-portal";

    private static final UiLocator AMOUNT = UiLocator.label("Amount");

    private static final UiLocator SUBMIT = UiLocator.role("button", "Confirm");

    private static final UiLocator CANCEL = UiLocator.testId("cancel");

    private static final UiLocator STATUS = UiLocator.testId("status");

    private static final UiLocator NUMBER = UiLocator.testId("number");

    private static final UiLocator COOKIE = UiLocator.testId("cookie");

    private LocalUiTestApplication application;

    @BeforeEach
    void startApplication() {
        this.application = new LocalUiTestApplication();
    }

    @AfterEach
    void stopApplication() {
        this.application.close();
    }

    @Test
    @DisplayName("alias → browser → page → fill → click → await → capture, and the browser is gone when the run ends")
    void theWholeSliceRunsAgainstARealBrowser() {
        ScenarioResult result = runner().run(Scenario.builder("ui-vertical-slice")
                .environment(ENVIRONMENT)
                .tag("ui")
                .step(UiStep.open(APPLICATION, "/applications/new").id("open-form").injectCorrelationId().build())
                .step(UiStep.expect(APPLICATION, AMOUNT).id("form-is-ready").assertVisible().assertEnabled(true).build())
                .step(UiStep.expect(APPLICATION, CANCEL).id("cancel-is-disabled").assertEnabled(false).build())
                .step(UiStep.fill(APPLICATION, AMOUNT, "100000").id("fill-amount").build())
                .step(UiStep.expect(APPLICATION, AMOUNT).id("amount-was-typed").assertValue("100000").build())
                .step(UiStep.click(APPLICATION, SUBMIT).id("submit").build())
                .step(UiStep.expectEventually(APPLICATION, STATUS)
                        .id("await-accepted")
                        .assertVisible()
                        .assertText("Accepted")
                        .capture("applicationNumber", NUMBER)
                        .within(Duration.ofSeconds(10))
                        .pollInterval(Duration.ofMillis(100))
                        .build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).extracting(step -> step.status()).containsOnly(StepStatus.SUCCESS);
        assertThat(result.stepResults().get(0).diagnostics()).containsEntry("ui.application", APPLICATION);
        assertThat(this.application.submissions()).as("the page's own request carried the SDK correlation id")
                .anySatisfy(headers -> assertThat(headers).containsEntry("x-correlation-id", result.correlationId().value()));
    }

    @Test
    @DisplayName("a captured screen value is visible to the next step as ${var} — the UI-to-backend data binding")
    void capturedValueIsVisibleToTheNextStep() {
        ScenarioResult result = runner().run(Scenario.builder("ui-capture")
                .environment(ENVIRONMENT)
                .step(UiStep.open(APPLICATION, "/applications/new").id("open-form").build())
                .step(UiStep.click(APPLICATION, SUBMIT).id("submit").build())
                .step(UiStep.expectEventually(APPLICATION, NUMBER)
                        .id("await-number")
                        .assertVisible()
                        .capture("applicationNumber", NUMBER)
                        .within(Duration.ofSeconds(10))
                        .pollInterval(Duration.ofMillis(100))
                        .build())
                // The next step consumes the captured value: filling the amount field with ${applicationNumber}
                // only succeeds if the variable really landed in the run's store.
                .step(UiStep.fill(APPLICATION, AMOUNT, "${applicationNumber}").id("reuse-capture").build())
                .step(UiStep.expect(APPLICATION, AMOUNT).id("check-reuse").assertValue("AP-42").build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("two runs do not share browser state: the second run sees none of the first run's cookies")
    void parallelRunsDoNotShareCookies() {
        DefaultScenarioRunner runner = runner();
        runner.run(Scenario.builder("ui-first-run")
                .environment(ENVIRONMENT)
                .step(UiStep.open(APPLICATION, "/applications/new").id("open-form").build())
                .step(UiStep.click(APPLICATION, SUBMIT).id("submit-sets-a-cookie").build())
                .step(UiStep.expectEventually(APPLICATION, STATUS).id("await").assertText("Accepted").within(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).build())
                .build());

        ScenarioResult second = runner.run(Scenario.builder("ui-second-run")
                .environment(ENVIRONMENT)
                .step(UiStep.open(APPLICATION, "/applications/new").id("open-form").build())
                .step(UiStep.expect(APPLICATION, COOKIE).id("no-cookie-from-the-previous-run").assertText("none").build())
                .build());

        assertThat(second.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("an expectation the screen never meets fails as an assertion, with the await diagnostics")
    void unmetExpectationFailsAsAnAssertion() {
        assertThatThrownBy(() -> runner().run(Scenario.builder("ui-never-accepted")
                .environment(ENVIRONMENT)
                .step(UiStep.open(APPLICATION, "/applications/new").id("open-form").build())
                .step(UiStep.expectEventually(APPLICATION, STATUS)
                        .id("await-without-submitting")
                        .assertText("Accepted")
                        .within(Duration.ofSeconds(2))
                        .pollInterval(Duration.ofMillis(100))
                        .build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("await-without-submitting")
                .hasMessageContaining("Accepted");
    }

    @Test
    @DisplayName("clicking something that is not on the screen is a failed expectation, not broken infrastructure")
    void clickingAMissingElementIsAFailedExpectation() {
        assertThatThrownBy(() -> runner().run(Scenario.builder("ui-missing-button")
                .environment(ENVIRONMENT)
                .step(UiStep.open(APPLICATION, "/applications/new").id("open-form").build())
                .step(UiStep.click(APPLICATION, UiLocator.testId("nothing-here")).id("click-a-ghost").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);
    }

    private DefaultScenarioRunner runner() {
        UiApplicationDefinition definition = new UiApplicationDefinition(APPLICATION, SecretReferences.literal(this.application.baseUrl()));
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(Map.of(
                ENVIRONMENT,
                new EnvironmentDefinition(ENVIRONMENT, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(APPLICATION, definition))));
        UiStepExecutor executor = new UiStepExecutor(new PlaywrightDriverFactory(), new EnvironmentUiApplicationResolver(name -> null));
        return new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
    }
}
