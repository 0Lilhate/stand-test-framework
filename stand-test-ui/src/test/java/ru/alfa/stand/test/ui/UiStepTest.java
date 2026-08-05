package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

class UiStepTest {

    private static final UiLocator SUBMIT = UiLocator.testId("submit");

    @Test
    @DisplayName("each factory produces its own step type, with the alias under the mirrored parameter key")
    void factoriesProduceTheirOwnTypes() {
        assertThat(UiStep.open("app", "/new").build().type()).isEqualTo("ui.open");
        assertThat(UiStep.click("app", SUBMIT).build().type()).isEqualTo("ui.click");
        assertThat(UiStep.fill("app", SUBMIT, "x").build().type()).isEqualTo("ui.fill");
        assertThat(UiStep.expect("app", SUBMIT).assertVisible().build().type()).isEqualTo("ui.expect");
        assertThat(UiStep.expectEventually("app", SUBMIT).assertVisible().build().type()).isEqualTo("ui.expectEventually");
        assertThat(parameters(UiStep.open("app", "/new").build())).containsEntry(UiStepParameters.APPLICATION, "app");
    }

    @Test
    @DisplayName("building performs no IO and yields an immutable GenericStep")
    void buildIsLazy() {
        ScenarioStep step = UiStep.fill("app", SUBMIT, "value").id("fill-it").description("types a value").build();

        assertThat(step).isInstanceOf(GenericStep.class);
        assertThat(step.id()).isEqualTo("fill-it");
        assertThat(((GenericStep) step).description()).isEqualTo("types a value");
        assertThatThrownBy(() -> parameters(step).put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a step without an explicit id still gets a stable, readable one, naming what it acts on")
    void idDefaultsToTypeAndTarget() {
        assertThat(UiStep.click("client-portal", SUBMIT).build().id()).isEqualTo("ui.click TEST_ID(submit)");
        assertThat(UiStep.open("client-portal", "/applications/new").build().id()).isEqualTo("ui.open /applications/new");
    }

    @Test
    @DisplayName("filling several fields of one form yields distinct default ids — step ids must be unique or the scenario is rejected")
    void defaultIdsOfAFormAreDistinct() {
        // Regression: an id built from type + application alone collided on the second field, and the
        // scenario failed validation with STEP_ID_DUPLICATE before a browser was ever opened.
        List<String> ids = Stream.of(
                UiStep.fill("client-portal", UiLocator.label("Amount"), "1"),
                UiStep.fill("client-portal", UiLocator.label("Purpose"), "2"),
                UiStep.fill("client-portal", UiLocator.testId("account"), "3"),
                UiStep.click("client-portal", SUBMIT))
                .map(step -> step.build().id())
                .toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the locator, assertions and captures are written under keys mirrored from the core wire contract")
    void parametersUseTheMirroredKeys() {
        ScenarioStep step = UiStep.expectEventually("app", SUBMIT)
                .assertText("Accepted")
                .capture("number", UiLocator.testId("number"))
                .withinSeconds(5)
                .pollInterval(Duration.ofMillis(50))
                .injectCorrelationId()
                .build();

        Map<String, Object> parameters = parameters(step);
        assertThat(parameters).containsEntry(UiStepParameters.TIMEOUT_MILLIS, 5_000L)
                .containsEntry(UiStepParameters.POLL_INTERVAL_MILLIS, 50L)
                .containsEntry(UiStepParameters.INJECT_CORRELATION_ID, true);
        assertThat(UiStepParameters.locator(parameters)).isEqualTo(SUBMIT);
        assertThat(UiStepParameters.assertions(parameters)).containsExactly(new UiAssertion(UiProperty.TEXT, "Accepted", AssertionMatcher.EQUALS));
        assertThat(UiStepParameters.captures(parameters)).containsExactly(new UiCapture("number", UiLocator.testId("number")));
    }

    @Test
    @DisplayName("the assertion sugar maps onto the core matchers")
    void assertionSugarMapsOntoCoreMatchers() {
        List<UiAssertion> assertions = UiStepParameters.assertions(parameters(UiStep.expect("app", SUBMIT)
                .assertVisible()
                .assertEnabled(false)
                .assertTextContains("part")
                .assertTextMatches("[A-Z]+")
                .assertValue("42")
                .assertAttribute("data-state", "ready")
                .build()));

        assertThat(assertions).containsExactly(
                new UiAssertion(UiProperty.VISIBLE, true, AssertionMatcher.EQUALS),
                new UiAssertion(UiProperty.ENABLED, false, AssertionMatcher.EQUALS),
                new UiAssertion(UiProperty.TEXT, "part", AssertionMatcher.CONTAINS),
                new UiAssertion(UiProperty.TEXT, "[A-Z]+", AssertionMatcher.MATCHES),
                new UiAssertion(UiProperty.VALUE, "42", AssertionMatcher.EQUALS),
                new UiAssertion(UiProperty.ATTRIBUTE, "data-state", "ready", AssertionMatcher.EQUALS));
    }

    @Test
    @DisplayName("a boolean property rejects any matcher other than EQUALS, at build time")
    void booleanPropertyRejectsNonEqualsMatcher() {
        assertThatThrownBy(() -> UiStep.expect("app", SUBMIT).assertProperty(UiProperty.VISIBLE, AssertionMatcher.CONTAINS, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supports only the EQUALS matcher");
    }

    @Test
    @DisplayName("an expect step without a single assertion is refused: it could never fail")
    void expectRequiresAtLeastOneAssertion() {
        assertThatThrownBy(() -> UiStep.expect("app", SUBMIT).build()).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires at least one assertion");
        assertThatThrownBy(() -> UiStep.expectEventually("app", SUBMIT).build()).isInstanceOf(IllegalStateException.class).hasMessageContaining("requires at least one assertion");
    }

    @Test
    @DisplayName("within / pollInterval are refused on a step that does not poll")
    void withinIsRejectedOnNonPollingStep() {
        assertThatThrownBy(() -> UiStep.click("app", SUBMIT).withinSeconds(5).build()).isInstanceOf(IllegalStateException.class).hasMessageContaining("within");
        assertThatThrownBy(() -> UiStep.expect("app", SUBMIT).assertVisible().pollInterval(Duration.ofMillis(10)).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pollInterval");
        assertThatCode(() -> UiStep.expectEventually("app", SUBMIT).assertVisible().withinSeconds(5).build()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a poll interval longer than the wait is refused at build time: one probe and then a wait for the interval is not what within(...) said")
    void pollIntervalMustFitTheTimeout() {
        assertThatThrownBy(() -> UiStep.expectEventually("app", SUBMIT).assertVisible().within(Duration.ofMillis(500)).pollInterval(Duration.ofSeconds(60)).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed the step's timeout");
        // Against the DEFAULT timeout too — the step that declares no within(...) still has one.
        assertThatThrownBy(() -> UiStep.expectEventually("app", SUBMIT).assertVisible().pollInterval(Duration.ofHours(1)).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed the step's timeout");
        assertThatCode(() -> UiStep.expectEventually("app", SUBMIT).assertVisible().within(Duration.ofSeconds(2)).pollInterval(Duration.ofSeconds(2)).build())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ui.login carries the alias, the role and both bounded waits under the mirrored wire keys, and nothing else")
    void loginStepCarriesRoleAndBoundedWaits() {
        GenericStep step = (GenericStep) UiStep.login("client-portal")
                .id("login")
                .role("manager")
                .within(Duration.ofSeconds(20))
                .accountTimeout(Duration.ofSeconds(30))
                .build();

        assertThat(step.type()).isEqualTo("ui.login");
        assertThat(step.parameters())
                .containsEntry(StepParameterKeys.APPLICATION, "client-portal")
                .containsEntry(StepParameterKeys.ROLE, "manager")
                .containsEntry(StepParameterKeys.TIMEOUT_MILLIS, 20_000L)
                .containsEntry(StepParameterKeys.ACCOUNT_TIMEOUT_MILLIS, 30_000L)
                .doesNotContainKey(StepParameterKeys.LOCATOR);
    }

    @Test
    @DisplayName("the sign-in knobs belong to the sign-in step, and a step that does not sign in refuses them at build time")
    void signInKnobsBelongToTheLoginStep() {
        assertThatThrownBy(() -> UiStep.click("app", SUBMIT).role("manager"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("role(...)");
        assertThatThrownBy(() -> UiStep.open("app", "/new").accountTimeout(Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accountTimeout(...)");
        assertThatThrownBy(() -> UiStep.login("app").accountTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> UiStep.login("app").pollInterval(Duration.ofMillis(10)).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pollInterval");
        assertThatThrownBy(() -> UiStep.login("app").assertVisible().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assertions belong on");
    }

    @Test
    @DisplayName("two sign-ins in one scenario differ by role, so the generated ids differ too — step ids must be unique")
    void loginDefaultIdsCarryTheRole() {
        assertThat(UiStep.login("client-portal").role("client").build().id()).isEqualTo("ui.login client-portal as client");
        assertThat(UiStep.login("client-portal").role("manager").build().id()).isEqualTo("ui.login client-portal as manager");
        assertThat(UiStep.login("client-portal").build().id()).isEqualTo("ui.login client-portal");
    }

    @Test
    @DisplayName("assertions and captures are refused on an action step: a check gets its own step number in the report")
    void actionsCarryNoAssertionsOrCaptures() {
        assertThatThrownBy(() -> UiStep.click("app", SUBMIT).assertVisible().build()).isInstanceOf(IllegalStateException.class).hasMessageContaining("assertions belong on");
        assertThatThrownBy(() -> UiStep.click("app", SUBMIT).capture("x").build()).isInstanceOf(IllegalStateException.class).hasMessageContaining("captures");
    }

    @Test
    @DisplayName("no signature accepts an absolute address: the SDK addresses applications by alias only")
    void noParameterAcceptsAbsoluteUrl() {
        assertThatThrownBy(() -> UiStep.open("app", "https://portal.example.com/new")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be relative");
        assertThatThrownBy(() -> UiStep.open("app", "http://portal.example.com/new")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiStep.open("app", "//portal.example.com/new")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank alias, blank path, blank id and blank variable name are refused")
    void blankInputsAreRefused() {
        assertThatThrownBy(() -> UiStep.open("", "/new")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiStep.open("app", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiStep.click("app", SUBMIT).id("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UiStep.expect("app", SUBMIT).assertVisible().capture("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("capture() without a locator is refused on a step that has none of its own")
    void captureWithoutLocatorIsRefusedWhereThereIsNoLocator() {
        assertThatThrownBy(() -> UiStep.open("app", "/new").capture("x")).isInstanceOf(IllegalStateException.class).hasMessageContaining("does not have");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(ScenarioStep step) {
        return (Map<String, Object>) ((GenericStep) step).parameters();
    }
}
