package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class UiStepExecutorTest {

    private static final UiLocator SUBMIT = UiLocator.testId("submit");

    private static final UiLocator STATUS = UiLocator.testId("status");

    private final FakeUiDriver driver = new FakeUiDriver();

    private final AtomicInteger opened = new AtomicInteger();

    private final UiStepExecutor executor = new UiStepExecutor(
            (application, settings) -> {
                this.opened.incrementAndGet();
                return this.driver;
            },
            (alias, context) -> new ResolvedUiApplication(alias, "http://localhost:8080", null));

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("it claims every ui.* step and nothing else")
    void supportsOnlyUiSteps() {
        assertThat(this.executor.supports("ui.open")).isTrue();
        assertThat(this.executor.supports("ui.expectEventually")).isTrue();
        assertThat(this.executor.supports("rest.get")).isFalse();
        assertThat(this.executor.supports(null)).isFalse();
    }

    @Test
    @DisplayName("prepare opens no browser: a scenario that never reaches its UI step pays nothing")
    void prepareDoesNotOpenBrowser() {
        this.executor.prepare(UiStep.open(UiTestSupport.APPLICATION, "/new").build(), UiTestSupport.context());

        assertThat(this.opened).hasValue(0);
        assertThat(this.driver.calls()).isEmpty();
    }

    @Test
    @DisplayName("the session is opened once and registered in the run's resource scope, then reused by later steps")
    void sessionIsOpenedOnceAndRegisteredInResourceScope() {
        ResourceScope scope = new ResourceScope();
        StepExecutionContext context = UiTestSupport.context(scope);
        this.driver.present(SUBMIT, "Confirm");

        execute(UiStep.open(UiTestSupport.APPLICATION, "/new").build(), context);
        execute(UiStep.click(UiTestSupport.APPLICATION, SUBMIT).build(), context);

        assertThat(this.opened).hasValue(1);
        assertThat(scope.contains("ui.session:" + UiTestSupport.APPLICATION)).isTrue();
        assertThat(this.driver.calls()).containsExactly("navigate:/new", "click:TEST_ID(submit)");
    }

    @Test
    @DisplayName("the executor holds no session or driver in a field — it is a JVM-wide singleton shared by parallel runs")
    void browserContextIsNotHeldByExecutor() {
        for (Field field : UiStepExecutor.class.getDeclaredFields()) {
            assertThat(Arrays.asList(UiSession.class, UiDriver.class, ElementSnapshot.class, ResolvedUiApplication.class))
                    .as("field '%s' would be run state shared across concurrent scenarios", field.getName())
                    .doesNotContain(field.getType());
        }
    }

    @Test
    @DisplayName("fill resolves ${var} through the run's variable resolver, exactly as a REST body does")
    void fillResolvesVariablePlaceholder() {
        StepExecutionContext context = UiTestSupport.context();
        context.variableStore().put("amount", "100000");

        execute(UiStep.fill(UiTestSupport.APPLICATION, SUBMIT, "${amount}").build(), context);

        assertThat(this.driver.calls()).contains("fill:TEST_ID(submit)=100000");
    }

    @Test
    @DisplayName("an undefined ${var} is a configuration failure, not a silent empty value")
    void undefinedVariableFails() {
        assertThatThrownBy(() -> execute(UiStep.fill(UiTestSupport.APPLICATION, SUBMIT, "${missing}").build(), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("ui.expect evaluates through the core matchers and reports the step's diagnostics")
    void expectEvaluatesAssertions() {
        this.driver.snapshot(STATUS, new ElementSnapshot(true, true, false, "Accepted", "v", Map.of("data-state", "final")));

        StepResult result = execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS)
                .id("check-status")
                .assertVisible()
                .assertEnabled(false)
                .assertText("Accepted")
                .assertTextContains("cept")
                .assertTextMatches("Acc.*")
                .assertValue("v")
                .assertAttribute("data-state", "final")
                .build(), UiTestSupport.context());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics())
                .containsEntry("ui.application", UiTestSupport.APPLICATION)
                .containsEntry("ui.locator", "TEST_ID(status)")
                .containsEntry("ui.locator.strategy", "TEST_ID");
    }

    @Test
    @DisplayName("an unmet expectation about the screen is an assertion failure, and says what it saw")
    void unmetExpectationIsAnAssertionFailure() {
        this.driver.present(STATUS, "Rejected");

        assertThatThrownBy(() -> execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(), UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("TEST_ID(status)")
                .hasMessageContaining("Accepted")
                .hasMessageContaining("Rejected");
    }

    @Test
    @DisplayName("a missing element is data, not an error: the assertion fails and says the element was not found")
    void missingElementFailsTheAssertionRatherThanBreaking() {
        assertThatThrownBy(() -> execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertVisible().build(), UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("not found on the page");
    }

    @Test
    @DisplayName("'the element must not be there' is expressible without exception handling")
    void absenceIsAssertable() {
        StepResult result = execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertVisible(false).build(), UiTestSupport.context());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("ui.expectEventually polls until the screen settles, without a single sleep")
    void expectEventuallyPollsUntilSatisfied() {
        this.driver.snapshot(STATUS,
                ElementSnapshot.absent(),
                new ElementSnapshot(true, true, true, "Pending", null, Map.of()),
                new ElementSnapshot(true, true, true, "Accepted", null, Map.of()));

        StepResult result = execute(UiStep.expectEventually(UiTestSupport.APPLICATION, STATUS)
                .assertText("Accepted")
                .within(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(10))
                .build(), UiTestSupport.context());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(this.driver.calls()).filteredOn(call -> call.startsWith("snapshot")).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a poll that never settles fails as an assertion, with the await diagnostics attached")
    void expectEventuallyTimesOutAsAnAssertionFailure() {
        this.driver.present(STATUS, "Pending");

        assertThatThrownBy(() -> execute(UiStep.expectEventually(UiTestSupport.APPLICATION, STATUS)
                .assertText("Accepted")
                .within(Duration.ofMillis(120))
                .pollInterval(Duration.ofMillis(20))
                .build(), UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("did not hold")
                .hasMessageContaining("attempts=");
    }

    @Test
    @DisplayName("a captured value lands in the variable store and is visible to the next step as ${var}")
    void captureLandsInVariableStore() {
        StepExecutionContext context = UiTestSupport.context();
        this.driver.present(STATUS, "Accepted").snapshot(UiLocator.testId("number"), new ElementSnapshot(true, true, true, "AP-42", "AP-43", Map.of("data-id", "AP-44")));

        execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS)
                .assertText("Accepted")
                .capture("byText", UiLocator.testId("number"))
                .capture("byValue", UiLocator.testId("number"), UiCaptureSource.VALUE)
                .captureAttribute("byAttribute", UiLocator.testId("number"), "data-id")
                .build(), context);

        assertThat(context.variableStore().get("byText")).contains("AP-42");
        assertThat(context.variableStore().get("byValue")).contains("AP-43");
        assertThat(context.variableStore().get("byAttribute")).contains("AP-44");
        assertThat(context.resolver().resolve("${byText}")).isEqualTo("AP-42");
    }

    @Test
    @DisplayName("capturing from an element that is not there fails the step instead of storing null")
    void captureFromMissingElementFails() {
        this.driver.present(STATUS, "Accepted");

        assertThatThrownBy(() -> execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS)
                .assertText("Accepted")
                .capture("number", UiLocator.testId("number"))
                .build(), UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("Cannot capture 'number'");
    }

    @Test
    @DisplayName("an element that never became actionable is a failed expectation, not broken infrastructure")
    void notActionableElementIsReportedAsAFailedAssertion() {
        this.driver.failClickWith(new UiElementNotActionableException("element TEST_ID(submit) was not clickable within PT10S"));

        assertThatThrownBy(() -> execute(UiStep.click(UiTestSupport.APPLICATION, SUBMIT).build(), UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("was not clickable");
    }

    @Test
    @DisplayName("any other driver error is infrastructure and stays a StandTestException")
    void driverErrorIsInfrastructure() {
        this.driver.failClickWith(new IllegalStateException("the browser went away"));

        assertThatThrownBy(() -> execute(UiStep.click(UiTestSupport.APPLICATION, SUBMIT).build(), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .isNotInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("browser went away");
    }

    @Test
    @DisplayName("observing goes through the same classifier as acting — a probe is not a second, unclassified path")
    void snapshotFailuresAreClassifiedLikeActions() {
        // Regression: the snapshot call bypassed the classifier, so a driver reporting "not actionable"
        // from a probe was reported as broken infrastructure, and any other driver error reached the
        // runner unclassified, losing the adapter's message.
        UiStepExecutor notActionable = new UiStepExecutor(
                (application, settings) -> new FailingSnapshotDriver(new UiElementNotActionableException("the element never settled")),
                (alias, context) -> UiTestSupport.resolved());
        UiStepExecutor broken = new UiStepExecutor(
                (application, settings) -> new FailingSnapshotDriver(new IllegalStateException("the browser went away")),
                (alias, context) -> UiTestSupport.resolved());
        ScenarioStep step = UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertVisible().build();

        assertThatThrownBy(() -> notActionable.execute(step, UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("never settled");
        assertThatThrownBy(() -> broken.execute(step, UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .isNotInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("observe TEST_ID(status)");
    }

    @Test
    @DisplayName("a failing assertion on a sensitive field puts neither the expected nor the typed value into the failure")
    void sensitiveFieldValuesNeverReachTheFailure() {
        UiLocator password = UiLocator.label("Password").asSensitive();
        this.driver.snapshot(password, new ElementSnapshot(true, true, true, null, "s3cret-typed", Map.of()));

        assertThatThrownBy(() -> execute(UiStep.expect(UiTestSupport.APPLICATION, password).assertValue("hunter2").build(), UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageNotContaining("hunter2")
                .hasMessageNotContaining("s3cret-typed");
    }

    @Test
    @DisplayName("an alias missing from the registry is refused by the resolver, and no browser is opened")
    void unknownAliasIsRefused() {
        UiStepExecutor registryBacked = new UiStepExecutor(
                (application, settings) -> {
                    this.opened.incrementAndGet();
                    return this.driver;
                },
                new EnvironmentUiApplicationResolver(name -> "http://localhost:8080"));

        assertThatThrownBy(() -> registryBacked.execute(UiStep.open("unknown-app", "/new").build(), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("is not whitelisted");
        assertThat(this.opened).hasValue(0);
    }

    @Test
    @DisplayName("a driver that returns nothing is a broken driver, reported rather than dereferenced")
    void nullFromTheDriverIsReported() {
        UiStepExecutor nullDriver = new UiStepExecutor((application, settings) -> null, (alias, context) -> UiTestSupport.resolved());

        assertThatThrownBy(() -> nullDriver.execute(UiStep.open(UiTestSupport.APPLICATION, "/new").build(), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("returned no driver");
    }

    @Test
    @DisplayName("a driver whose snapshot is null is reported instead of being read as an absent element")
    void nullSnapshotIsReported() {
        UiStepExecutor nullSnapshots = new UiStepExecutor((application, settings) -> new NullSnapshotDriver(), (alias, context) -> UiTestSupport.resolved());

        assertThatThrownBy(() -> nullSnapshots.execute(UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertVisible().build(), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("ElementSnapshot.absent()");
    }

    @Test
    @DisplayName("the SDK-owned correlation id is injected into the page's requests when the step asks for it")
    void correlationIdIsInjectedOnRequest() {
        StepExecutionContext context = UiTestSupport.context();

        execute(UiStep.open(UiTestSupport.APPLICATION, "/new").injectCorrelationId().build(), context);

        assertThat(this.driver.extraHeaders()).containsEntry(UiStepExecutor.CORRELATION_HEADER, context.scenarioContext().correlationId().value());
    }

    @Test
    @DisplayName("the opt-in is honoured on a later step too, not only on the one that opened the session")
    void correlationIdIsInjectedOnAStepThatDidNotOpenTheSession() {
        // Regression: the flag used to be read only while creating the session, so injectCorrelationId()
        // on any step after the first was a silent no-op — a knob that reads as set and does nothing.
        StepExecutionContext context = UiTestSupport.context();
        this.driver.present(SUBMIT, "Confirm");

        execute(UiStep.open(UiTestSupport.APPLICATION, "/new").build(), context);
        assertThat(this.driver.extraHeaders()).isEmpty();

        execute(UiStep.click(UiTestSupport.APPLICATION, SUBMIT).injectCorrelationId().build(), context);

        assertThat(this.driver.extraHeaders()).containsEntry(UiStepExecutor.CORRELATION_HEADER, context.scenarioContext().correlationId().value());
    }

    @Test
    @DisplayName("without the opt-in no header is added")
    void correlationIdIsNotInjectedByDefault() {
        execute(UiStep.open(UiTestSupport.APPLICATION, "/new").build(), UiTestSupport.context());

        assertThat(this.driver.extraHeaders()).isEmpty();
    }

    @Test
    @DisplayName("a step that is not a UiStep product, or an unknown ui.* type, is refused")
    void foreignStepsAreRefused() {
        assertThatThrownBy(() -> execute(new ForeignStep(), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("requires a GenericStep");
        assertThatThrownBy(() -> execute(new GenericStep("x", "ui.unknown", null, Map.of(UiStepParameters.APPLICATION, UiTestSupport.APPLICATION)), UiTestSupport.context()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unsupported UI step type");
    }

    private StepResult execute(ScenarioStep step, StepExecutionContext context) {
        return this.executor.execute(step, context);
    }

    @Test
    @DisplayName("a failing step captures a screenshot exactly once, and the PNG is written (UITG-S013)")
    void failingStepCapturesAScreenshot() {
        this.driver.present(STATUS, "Rejected");
        UiStepExecutor uiExecutor = new UiStepExecutor(
                (application, settings) -> this.driver,
                (alias, context) -> UiTestSupport.resolved(),
                Awaiter.create(),
                () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir));

        assertThatThrownBy(() -> uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(),
                UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(this.driver.screenshotCalls()).as("a failing UI step must request the screenshot exactly once").isEqualTo(1);
        assertThat(this.driver.screenshotFile()).as("the artefact must have been written").isNotNull();
        assertThat(Files.exists(this.driver.screenshotFile())).as("the screenshot file must exist on disk").isTrue();
    }

    @Test
    @DisplayName("a green step never captures a screenshot — no artefact, no effort (UITG-S013)")
    void successfulStepCapturesNothing() {
        this.driver.present(STATUS, "Accepted");
        UiStepExecutor uiExecutor = new UiStepExecutor(
                (application, settings) -> this.driver,
                (alias, context) -> UiTestSupport.resolved(),
                Awaiter.create(),
                () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir));

        StepResult result = uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(),
                UiTestSupport.context());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(this.driver.screenshotCalls()).as("a green step must not capture a screenshot").isZero();
        assertThat(this.driver.screenshotFile()).as("no screenshot file may exist after a green step").isNull();
        assertThat(this.driver.maskCalls()).as("a green step must not mask anything").isEmpty();
    }

    @Test
    @DisplayName("a failing step on a sensitive locator masks it once, THEN captures, and records the masked-zone count (UITG-S017)")
    void failingStepOnSensitiveLocatorMasksThenCaptures() {
        UiLocator status = STATUS.asSensitive();
        this.driver.present(status, "Rejected");
        UiStepExecutor uiExecutor = newExecutorWithArtifacts();

        try (LogCapture log = LogCapture.attached()) {
            assertThatThrownBy(() -> uiExecutor.execute(
                    UiStep.expect(UiTestSupport.APPLICATION, status).assertText("Accepted").build(),
                    UiTestSupport.context()))
                    .isInstanceOf(StandTestAssertionError.class);

            // The masked-zone count is a diagnostic datum: the executor records how many it actually stopped,
            // so observability (UITG-S017 acceptance #3) is asserted from the log, not merely returned.
            assertThat(log.text()).as("the executor must record the masked-zone count into the diagnostics/log")
                    .contains("Masked 1 of 1 sensitive zone(s)");
        }

        assertThat(this.driver.maskCalls()).as("the executor must mask the step's sensitive zone exactly once").hasSize(1);
        assertThat(this.driver.maskCalls().get(0)).as("the masked zone must be the step's sensitive locator").containsExactly(status);
        assertThat(this.driver.maskCounts()).containsExactly(1);
        assertThat(this.driver.screenshotCalls()).as("the capture must happen after masking").isEqualTo(1);
        int maskedIndex = this.driver.calls().indexOf("maskSensitive:1");
        int snapshotIndex = this.driver.calls().indexOf("captureScreenshot");
        assertThat(maskedIndex).as("maskSensitive must be issued before captureScreenshot").isLessThan(snapshotIndex);
    }

    @Test
    @DisplayName("on a failing step whose locator is NOT sensitive, masking is skipped and the count is recorded as zero (UITG-S017)")
    void failingStepOnPlainLocatorMakesNothingToMask() {
        this.driver.present(STATUS, "Rejected");
        UiStepExecutor uiExecutor = newExecutorWithArtifacts();

        assertThatThrownBy(() -> uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(),
                UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(this.driver.maskCalls()).as("no sensitive zone -> no masking call").isEmpty();
        assertThat(this.driver.calls()).as("the capture must not be preceded by any masking").doesNotContain("maskSensitive");
        assertThat(this.driver.screenshotCalls()).as("no sensitive zone still means a capture of the screen").isEqualTo(1);
    }

    @Test
    @DisplayName("a driver that cannot mask aborts the capture — missing screenshot is safer than one leaking a secret (UITG-S017)")
    void throwingMaskAbortsTheCapture() {
        UiLocator status = UiLocator.testId("status").asSensitive();
        this.driver.present(status, "Rejected");
        this.driver.failMaskWith(new IllegalStateException("the browser went away"));
        UiStepExecutor uiExecutor = newExecutorWithArtifacts();

        assertThatThrownBy(() -> uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, status).assertText("Accepted").build(),
                UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(this.driver.maskCalls()).hasSize(1);
        assertThat(this.driver.screenshotCalls()).as("a failed mask must prevent the screenshot, not best-effort it").isZero();
        assertThat(this.driver.screenshotFile()).as("no artefact may exist when masking could not stop the secret").isNull();
    }

    @Test
    @DisplayName("a masking failure is logged as a masking abort, not as a missed screenshot (UITG-S017 / MEDIUM-2)")
    void maskFailureIsLoggedDistinctlyFromCaptureFailure() {
        UiLocator status = UiLocator.testId("status").asSensitive();
        this.driver.present(status, "Rejected").failMaskWith(new IllegalStateException("driver cannot close the zone"));
        UiStepExecutor uiExecutor = newExecutorWithArtifacts();

        try (LogCapture log = LogCapture.attached()) {
            assertThatThrownBy(() -> uiExecutor.execute(
                    UiStep.expect(UiTestSupport.APPLICATION, status).assertText("Accepted").build(),
                    UiTestSupport.context()))
                    .isInstanceOf(StandTestAssertionError.class);
            assertThat(log.text())
                    .as("a masking failure must name the masking and the abort, not blame the capture")
                    .contains("Could not mask")
                    .contains("aborted")
                    .doesNotContain("Could not capture a failure screenshot");
        }
        assertThat(this.driver.screenshotCalls()).as("the masking abort must not even attempt the capture").isZero();
    }

    @Test
    @DisplayName("a capture failure is logged as a capture failure, distinctly from a masking abort (UITG-S017 / MEDIUM-2)")
    void captureFailureIsLoggedDistinctlyFromMaskingAbort() {
        // NullSnapshotDriver's screenshot throws UnsupportedOperationException, and its snapshot returns null
        // (a broken driver, an infrastructure failure in the executor's classification). No sensitive zone is
        // declared, so masking is skipped and the failure lands on the capture itself.
        UiStepExecutor nullSnapshots = new UiStepExecutor(
                (application, settings) -> new NullSnapshotDriver(),
                (alias, context) -> UiTestSupport.resolved(),
                Awaiter.create(),
                () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir));

        try (LogCapture log = LogCapture.attached()) {
            assertThatThrownBy(() -> nullSnapshots.execute(
                    UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertVisible().build(),
                    UiTestSupport.context()))
                    .isInstanceOf(StandTestException.class);
            assertThat(log.text()).as("a capture failure must name the capture, not a masking run that never happened")
                    .contains("Could not capture a failure screenshot")
                    .doesNotContain("Could not mask");
        }
    }

    @Test
    @DisplayName("a failing step on an on-failure application captures a trace AFTER the screenshot, formatted right (UITG-S016)")
    void failingStepWithTraceEnabledCapturesTraceAfterMask() {
        UiLocator status = STATUS.asSensitive();
        this.driver.present(status, "Rejected").withTrace();
        UiStepExecutor uiExecutor = newExecutorWithArtifacts(UiTestSupport.resolved(UiTraceMode.ON_FAILURE));

        assertThatThrownBy(() -> uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, status).assertText("Accepted").build(),
                UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(this.driver.screenshotCalls()).as("the screenshot must still be requested once").isEqualTo(1);
        assertThat(this.driver.traceCalls()).as("a trace must be requested once on the failing, opted-in run").isEqualTo(1);

        // The security ordering (UITG-S017/S016): the trace must be sealed strictly after the mask, so it can
        // never outrun the maskSensitive it would argue with.
        int masked = this.driver.calls().indexOf("maskSensitive:1");
        int screenshot = this.driver.calls().indexOf("captureScreenshot");
        int trace = this.driver.calls().indexOf("captureTrace");
        assertThat(masked).as("maskSensitive must precede the screenshot").isLessThan(screenshot);
        assertThat(screenshot).as("the screenshot must precede the trace").isLessThan(trace);
    }

    @Test
    @DisplayName("a failing step on an OFF application records no trace — the flag gates it off (UITG-S016)")
    void failingStepWithTraceOffCapturesScreenshotOnly() {
        this.driver.present(STATUS, "Rejected");
        UiStepExecutor uiExecutor = newExecutorWithArtifacts(UiTestSupport.resolved());

        assertThatThrownBy(() -> uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(),
                UiTestSupport.context()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(this.driver.screenshotCalls()).as("the screenshot is not gated by the trace flag").isEqualTo(1);
        assertThat(this.driver.traceCalls()).as("an OFF application must never request a trace").isZero();
    }

    @Test
    @DisplayName("a trace miss on the failure path is logged and never replaces the step's own failure (UITG-S016)")
    void throwingTraceIsLoggedAndKeepsScreenshotAndStepFailure() {
        UiLocator status = STATUS.asSensitive();
        this.driver.present(status, "Rejected").withTrace().failTraceWith(new IllegalStateException("trace export failed"));
        UiStepExecutor uiExecutor = newExecutorWithArtifacts(UiTestSupport.resolved(UiTraceMode.ON_FAILURE));

        try (LogCapture log = LogCapture.attached()) {
            assertThatThrownBy(() -> uiExecutor.execute(
                    UiStep.expect(UiTestSupport.APPLICATION, status).assertText("Accepted").build(),
                    UiTestSupport.context()))
                    .as("a trace miss must never mask the step's own failure")
                    .isInstanceOf(StandTestAssertionError.class);
            assertThat(log.text()).as("the trace miss must be named in the log, not silently swallowed")
                    .contains("Could not capture the trace");
        }
        assertThat(this.driver.screenshotFile()).as("the screenshot must survive a trace miss").isNotNull();
    }

    @Test
    @DisplayName("a failing step carries the page's network as a TEXT attachment, beside the screenshot (UITG-S015)")
    void failingStepCarriesNetworkOnItsStepEvent() {
        // The network story is a textual artefact, exactly like the console (UITG-S014): it must ride the
        // text channel, which the Allure sink runs through the secret masker (SEC-05). Whatever a body or
        // header value still carried after the driver's own masking is redacted again in the sink.
        UiStepExecutor uiExecutor = newExecutorWithArtifacts();
        this.driver.present(STATUS, "Rejected")
                .network("GET /api/status 200", "POST /api/submit 204", "GET /api/dashboard 500");

        Throwable thrown = catchThrowable(() -> uiExecutor.execute(
                UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(),
                UiTestSupport.context()));

        assertThat(thrown).isInstanceOf(UiAssertionFailure.class);
        List<Attachment> attachments = ((UiAssertionFailure) thrown).failureAttachments();
        assertThat(attachments).as("the failing step must attach the network story beside the screenshot")
                .anySatisfy(attachment -> {
                    assertThat(attachment.name()).isEqualTo("ui-network");
                    assertThat(attachment.mediaType()).isEqualTo("text/plain");
                    assertThat(attachment.isBinary()).isFalse();
                    assertThat(attachment.content())
                            .contains("POST /api/submit 204")
                            .doesNotContain("Authorization")
                            .doesNotContain("Cookie");
                });
    }

    @Test
    @DisplayName("a failing step whose page made no requests attaches no network block — empty is not an artefact (UITG-S015)")
    void failingStepWithEmptyNetworkAttachesNothing() {
        // The negative scenario: the driver observed no requests, so there is no network story to attach —
        // the failing step carries exactly the screenshot, not an empty "ui-network" block.
        this.driver.present(STATUS, "Rejected");

        Throwable failure = catchThrowable(() -> newExecutorWithArtifacts().execute(
                UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertText("Accepted").build(),
                UiTestSupport.context()));

        assertThat(failure).isInstanceOf(UiAssertionFailure.class);
        List<Attachment> attachments = ((UiAssertionFailure) failure).failureAttachments();
        assertThat(attachments)
                .as("without a network story the failing step carries only the screenshot")
                .allMatch(attachment -> !"ui-network".equals(attachment.name()));
    }

    @Test
    @DisplayName("a network read that fails is logged and never replaces the step's own failure (UITG-S015)")
    void throwingNetworkIsLoggedAndKeepsScreenshotAndStepFailure() {
        // A driver whose screen is fine but whose network log is gone: the executor must process the
        // screenshot, note the missing network story at WARN and still surface the step's own failure.
        UiStepExecutor uiExecutor = new UiStepExecutor(
                (application, settings) -> new FailingNetworkDriver(new IllegalStateException("network log unavailable")),
                (alias, context) -> UiTestSupport.resolved(),
                Awaiter.create(),
                () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir));

        try (LogCapture log = LogCapture.attached()) {
            assertThatThrownBy(() -> uiExecutor.execute(
                    UiStep.expect(UiTestSupport.APPLICATION, STATUS).assertVisible().build(),
                    UiTestSupport.context()))
                    .as("a network miss must never mask the step's own failure")
                    .isInstanceOf(StandTestException.class);
            assertThat(log.text()).as("the network miss must be named in the log, not silently swallowed")
                    .contains("Could not read the page's network requests");
        }
    }

    /**
     * An executor whose run settings put artefacts under this test's {@code tempDir}, and which resolves the
     * application to the given one — used by the UITG-S013/016/017 failure-path tests.
     */
    private UiStepExecutor newExecutorWithArtifacts(ResolvedUiApplication resolved) {
        return new UiStepExecutor(
                (application, settings) -> this.driver,
                (alias, context) -> resolved,
                Awaiter.create(),
                () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir));
    }

    /**
     * An executor whose run settings put artefacts under this test's {@code tempDir}, so the UITG-S013/017
     * failure-path tests can assert on what was actually written.
     */
    private UiStepExecutor newExecutorWithArtifacts() {
        return new UiStepExecutor(
                (application, settings) -> this.driver,
                (alias, context) -> UiTestSupport.resolved(),
                Awaiter.create(),
                () -> new UiRunSettings(true, UiRunSettings.DEFAULT_BROWSER, Duration.ofSeconds(2), Duration.ofSeconds(2), this.tempDir));
    }

    /** A driver whose probe throws, to pin how the executor classifies it. */
    private static final class FailingSnapshotDriver extends NullSnapshotDriver {

        private final RuntimeException failure;

        FailingSnapshotDriver(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public ElementSnapshot snapshot(UiLocator locator, java.util.Collection<String> attributes, Duration probeTimeout) {
            throw this.failure;
        }
    }

    /** A driver that breaks the snapshot contract, to prove the executor notices. */
    private static class NullSnapshotDriver implements UiDriver {

        @Override
        public void navigate(String relativePath, Duration timeout) {
        }

        @Override
        public ElementSnapshot snapshot(UiLocator locator, java.util.Collection<String> attributes, Duration probeTimeout) {
            return null;
        }

        @Override
        public void click(UiLocator locator, Duration timeout) {
        }

        @Override
        public void fill(UiLocator locator, String value, Duration timeout) {
        }

        @Override
        public void setExtraHeader(String name, String value) {
        }

        @Override
        public void saveStorageState(java.nio.file.Path target) {
            throw new UnsupportedOperationException("this driver never signs in");
        }

        @Override
        public String currentUrl() {
            return "http://localhost/";
        }

        @Override
        public Path captureScreenshot(Path directory, Duration timeout) {
            throw new UnsupportedOperationException("this test driver never captures a screenshot");
        }

        @Override
        public int maskSensitive(java.util.Collection<UiLocator> sensitiveLocators, Duration timeout) {
            throw new UnsupportedOperationException("this test driver never masks");
        }

        @Override
        public Path captureTrace(Path directory, Duration timeout) {
            return null;
        }

        @Override
        public List<String> consoleMessages() {
            return List.of();
        }

        @Override
        public List<String> networkRequests() {
            return List.of();
        }

        @Override
        public void close() {
        }
    }

    /**
     * A driver whose screenshot works but whose {@link #networkRequests()} throws — the seam for proving that
     * a network-log miss on the failure path is a best-effort artefact, keeps the screenshot and never
     * replaces the step's own failure.
     */
    private static final class FailingNetworkDriver implements UiDriver {

        private final RuntimeException failure;

        FailingNetworkDriver(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void navigate(String relativePath, Duration timeout) {
        }

        @Override
        public ElementSnapshot snapshot(UiLocator locator, java.util.Collection<String> attributes, Duration probeTimeout) {
            return null;
        }

        @Override
        public void click(UiLocator locator, Duration timeout) {
        }

        @Override
        public void fill(UiLocator locator, String value, Duration timeout) {
        }

        @Override
        public void setExtraHeader(String name, String value) {
        }

        @Override
        public void saveStorageState(java.nio.file.Path target) {
            throw new UnsupportedOperationException("this driver never signs in");
        }

        @Override
        public String currentUrl() {
            return "http://localhost/";
        }

        @Override
        public Path captureScreenshot(Path directory, Duration timeout) {
            try {
                Path target = directory.resolve("fake-screenshot-" + System.nanoTime() + ".png");
                Files.write(target, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
                return target;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("could not write the fake screenshot", failure);
            }
        }

        @Override
        public int maskSensitive(java.util.Collection<UiLocator> sensitiveLocators, Duration timeout) {
            return 0;
        }

        @Override
        public Path captureTrace(Path directory, Duration timeout) {
            return null;
        }

        @Override
        public List<String> consoleMessages() {
            return List.of();
        }

        @Override
        public List<String> networkRequests() {
            throw this.failure;
        }

        @Override
        public void close() {
        }
    }

    /** A step that did not come from {@link UiStep}. */
    private record ForeignStep() implements ScenarioStep {

        @Override
        public String id() {
            return "foreign";
        }

        @Override
        public String type() {
            return "ui.open";
        }

        @Override
        public String description() {
            return null;
        }
    }
}
