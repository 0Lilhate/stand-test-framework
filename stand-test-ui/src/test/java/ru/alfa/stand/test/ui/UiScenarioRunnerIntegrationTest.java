package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;
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

@TempDir
    Path tempDir;

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

    @Test
    @DisplayName("a failing step's StepEvent carries the file-backed screenshot — the report, not just the disk, sees it (UITG-S013)")
    void failingStepCarriesScreenshotOnItsStepEvent() {
        this.driver.present(STATUS, "Rejected");
        RecordingPublisher events = new RecordingPublisher();
        DefaultScenarioRunner recorder = runnerWith(events);

        assertThatThrownBy(() -> recorder.run(Scenario.builder("ui-screenshot-event")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check-status").assertText("Accepted").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);

        StepEvent failed = events.stepEvents().stream()
                .filter(event -> event.status() == StepStatus.FAILED)
                .filter(event -> "ui.expect".equals(event.stepType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FAILED ui.expect StepEvent was captured"));
        // The negative scenario of UITG-S014: with an empty console there is nothing textual to attach, so
        // the failing step carries exactly the screenshot — no empty "console" block.
        assertThat(failed.attachments()).as("the failing step's report must carry the screenshot and only it").hasSize(1);
        assertThat(failed.attachments().get(0).name()).isEqualTo("ui-screenshot");
        assertThat(failed.attachments().get(0).mediaType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("a failing step's StepEvent carries the browser console as a TEXT audio/аудио attachment — the maskable channel (UITG-S014)")
    void failingStepCarriesConsoleOnItsStepEvent() {
        // The console is a textual artefact: it must ride the text attachment channel, which the Allure sink
        // runs through the secret masker (SEC-05). A secret the page logged reaches the report already
        // redacted, unlike the screenshot whose bytes cannot be masked after the fact.
        this.driver.present(STATUS, "Rejected").console("error: Failed to load widget", "warn: offline assets");
        RecordingPublisher events = new RecordingPublisher();
        DefaultScenarioRunner recorder = runnerWith(events);

        assertThatThrownBy(() -> recorder.run(Scenario.builder("ui-console-event")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check-status").assertText("Accepted").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);

        StepEvent failed = events.stepEvents().stream()
                .filter(event -> event.status() == StepStatus.FAILED)
                .filter(event -> "ui.expect".equals(event.stepType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FAILED ui.expect StepEvent was captured"));
        assertThat(failed.attachments())
                .as("the failing step must attach the console as a text block beside the screenshot")
                .anyMatch(attachment -> "ui-console".equals(attachment.name())
                        && "text/plain".equals(attachment.mediaType())
                        && !attachment.isBinary()
                        && attachment.content().contains("Failed to load widget"));
    }

    @Test
    @DisplayName("a failing step's StepEvent carries the page's network as a TEXT attachment — the maskable channel (UITG-S015)")
    void failingStepCarriesNetworkOnItsStepEvent() {
        // The network story is a textual artefact like the console (UITG-S014): it must ride the text
        // attachment channel, which the Allure sink runs through the secret masker (SEC-05). The driver has
        // already reduced each line to method/path/status — no headers, no bodies — so the maskable channel
        // is the whole story, not a fallback.
        this.driver.present(STATUS, "Rejected")
                .console("error: widget off")
                .network("GET /api/status 200", "POST /api/submit 204", "GET /api/dashboard 500");
        RecordingPublisher events = new RecordingPublisher();
        DefaultScenarioRunner recorder = runnerWith(events);

        assertThatThrownBy(() -> recorder.run(Scenario.builder("ui-network-event")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check-status").assertText("Accepted").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);

        StepEvent failed = events.stepEvents().stream()
                .filter(event -> event.status() == StepStatus.FAILED)
                .filter(event -> "ui.expect".equals(event.stepType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FAILED ui.expect StepEvent was captured"));
        assertThat(failed.attachments())
                .as("the failing step must attach the network story as a text block beside the other artefacts")
                .anyMatch(attachment -> "ui-network".equals(attachment.name())
                        && "text/plain".equals(attachment.mediaType())
                        && !attachment.isBinary()
                        && attachment.content().contains("POST /api/submit 204"));
    }

    @Test
    @DisplayName("the masked-zone count of a failing step lands in the reportable diagnostics, not only in the log (UITG-S017)")
    void failingStepCarriesMaskedZoneCountInItsDiagnostics() {
        // A sensitive locator: the step's own field holds a secret, so the failure path masks it before the
        // screenshot. The count of zones actually closed must reach the diagnostics of the FAILED StepEvent.
        UiLocator secret = UiLocator.testId("token").asSensitive();
        this.driver.present(secret, "s3cret");
        RecordingPublisher events = new RecordingPublisher();
        DefaultScenarioRunner recorder = runnerWith(events);

        assertThatThrownBy(() -> recorder.run(Scenario.builder("ui-masked-zones")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.expect(UiTestSupport.APPLICATION, secret).id("check-secret").assertValue("wrong").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);

        StepEvent failed = events.stepEvents().stream()
                .filter(event -> event.status() == StepStatus.FAILED)
                .filter(event -> "ui.expect".equals(event.stepType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FAILED ui.expect StepEvent was captured"));
        assertThat(failed.message()).as("the failure reason names the sensitive locator, never its value").contains("TEST_ID").doesNotContain("s3cret");
        // Acceptance #3 of UITG-S017: the number of closed zones lands in the diagnostics, not just the log.
        assertThat(failed.diagnostics().get(UiAssertionFailure.DIAGNOSTIC_MASKED_ZONES))
                .as("the masked-zone count must reach the failing step's diagnostics")
                .isEqualTo(1);
        assertThat(failed.diagnostics()).containsKey("exception.class");
    }

    private DefaultScenarioRunner runner() {
        EnvironmentRegistry registry = UiTestSupport.registry();
        UiStepExecutor executor = new UiStepExecutor((application, settings) -> this.driver, new EnvironmentUiApplicationResolver(name -> "http://localhost:8080"));
        return new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
    }

    /**
     * A runner whose failing steps land their artefacts in this test's {@code tempDir} and whose published
     * events are captured, so a test can assert on what a failing step's report would have carried. The
     * explicit-artifacts-dir and resolved-application wiring is identical for every failure-artefact test,
     * so it lives here once instead of being rebuilt by hand four times.
     */
    private DefaultScenarioRunner runnerWith(RecordingPublisher events) {
        return new DefaultScenarioRunner(
                List.of(new UiStepExecutor(
                        (application, settings) -> this.driver,
                        (alias, context) -> UiTestSupport.resolved(),
                        Awaiter.create(),
                        () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir))),
                new DefaultScenarioValidator(),
                UiTestSupport.registry(),
                events);
    }

    /**
     * Collects the {@link StepEvent}s a runner publishes, so a test can assert on what the report would
     * have carried. Not thread-safe: used for single-threaded scenarios only.
     */
    private static final class RecordingPublisher implements ReportingEventPublisher {

        private final List<StepEvent> steps = new ArrayList<>();

        @Override
        public void publish(ScenarioEvent event) {
            // Not the subject of this test.
        }

        @Override
        public void publish(StepEvent event) {
            this.steps.add(event);
        }

        List<StepEvent> stepEvents() {
            return List.copyOf(this.steps);
        }
    }
}
