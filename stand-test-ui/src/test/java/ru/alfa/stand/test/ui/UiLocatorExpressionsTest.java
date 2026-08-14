package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class UiLocatorExpressionsTest {

    @Test
    @DisplayName("every strategy has a spelling, and a role locator carries its accessible name after the colon")
    void everyStrategyParses() {
        assertThat(UiLocatorExpressions.parse("testId=login-submit", "submit-locator", "portal")).isEqualTo(UiLocator.testId("login-submit"));
        assertThat(UiLocatorExpressions.parse("role=button:Sign in", "submit-locator", "portal")).isEqualTo(UiLocator.role("button", "Sign in"));
        assertThat(UiLocatorExpressions.parse("label=Password", "password-locator", "portal")).isEqualTo(UiLocator.label("Password"));
        assertThat(UiLocatorExpressions.parse("text=Sign in", "submit-locator", "portal")).isEqualTo(UiLocator.text("Sign in"));
        assertThat(UiLocatorExpressions.parse("css=#login .submit", "submit-locator", "portal")).isEqualTo(UiLocator.css("#login .submit"));
    }

    @Test
    @DisplayName("the strategy name is forgiving about case and separators, because a registry is written by hand")
    void strategyNamesAreNormalised() {
        assertThat(UiLocatorExpressions.parse("TESTID = login-submit", "submit-locator", "portal")).isEqualTo(UiLocator.testId("login-submit"));
        assertThat(UiLocatorExpressions.parse("test-id=login-submit", "submit-locator", "portal")).isEqualTo(UiLocator.testId("login-submit"));
        assertThat(UiLocatorExpressions.parse("test_id=login-submit", "submit-locator", "portal")).isEqualTo(UiLocator.testId("login-submit"));
    }

    @Test
    @DisplayName("there is no xpath spelling, by design — the cheapest ban is having nowhere to put it")
    void thereIsNoXPath() {
        assertThatThrownBy(() -> UiLocatorExpressions.parse("xpath=//button[1]", "submit-locator", "portal"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("no xpath");
    }

    @Test
    @DisplayName("an expression with no strategy prefix is refused, and its text is never echoed — it may be a mistyped credential")
    void malformedExpressionsAreRefusedWithoutEchoing() {
        String password = "P@ssw0rd-not-to-be-printed";

        assertThatThrownBy(() -> UiLocatorExpressions.parse(password, "password-locator", "client-portal"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("password-locator")
                .hasMessageContaining("client-portal")
                .hasMessageNotContaining(password);
        assertThatThrownBy(() -> UiLocatorExpressions.parse("testId=", "submit-locator", "portal"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("submit-locator");
        assertThatThrownBy(() -> UiLocatorExpressions.parse(null, "signed-in-locator", "portal"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("signed-in-locator");
    }

    @Test
    @DisplayName("a role locator without an accessible name is refused, with the spelling it should have had")
    void roleNeedsAnAccessibleName() {
        assertThatThrownBy(() -> UiLocatorExpressions.parse("role=button", "submit-locator", "portal"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("role=<role>:<accessible name>");
    }
}
