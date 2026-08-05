package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
