package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiLocatorTest {

    @Test
    @DisplayName("every factory records its strategy and operand")
    void factoriesRecordStrategyAndOperand() {
        assertThat(UiLocator.testId("submit")).isEqualTo(new UiLocator(LocatorStrategy.TEST_ID, "submit", null));
        assertThat(UiLocator.role("button", "Confirm")).isEqualTo(new UiLocator(LocatorStrategy.ROLE, "button", "Confirm"));
        assertThat(UiLocator.label("Amount")).isEqualTo(new UiLocator(LocatorStrategy.LABEL, "Amount", null));
        assertThat(UiLocator.text("Accepted")).isEqualTo(new UiLocator(LocatorStrategy.TEXT, "Accepted", null));
        assertThat(UiLocator.css("#submit")).isEqualTo(new UiLocator(LocatorStrategy.CSS, "#submit", null));
    }

    @Test
    @DisplayName("fragile() is false only for a test id — one definition, used by the report and by any static count")
    void fragileIsFalseOnlyForTestId() {
        assertThat(UiLocator.testId("submit").fragile()).isFalse();
        assertThat(UiLocator.role("button", "Confirm").fragile()).isTrue();
        assertThat(UiLocator.label("Amount").fragile()).isTrue();
        assertThat(UiLocator.text("Accepted").fragile()).isTrue();
        assertThat(UiLocator.css("#submit").fragile()).isTrue();
    }

    @Test
    @DisplayName("an accessible name is required for ROLE and refused for everything else")
    void accessibleNameBelongsToRoleOnly() {
        assertThatThrownBy(() -> new UiLocator(LocatorStrategy.ROLE, "button", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accessible name");
        assertThatThrownBy(() -> new UiLocator(LocatorStrategy.CSS, "#x", "Confirm")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only meaningful for a ROLE");
        assertThatThrownBy(() -> UiLocator.testId(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the sensitive mark is a copy, carries through the wire format, and does not disturb equality of the rest")
    void sensitiveMarkIsACopy() {
        UiLocator plain = UiLocator.label("Password");
        UiLocator marked = plain.asSensitive();

        assertThat(plain.sensitive()).isFalse();
        assertThat(marked.sensitive()).isTrue();
        assertThat(marked).isNotEqualTo(plain);
        assertThat(marked.strategy()).isEqualTo(plain.strategy());
        assertThat(marked.value()).isEqualTo(plain.value());
        assertThat(marked.asSensitive()).isEqualTo(marked);
        assertThat(UiStepParameters.locator(java.util.Map.of(UiStepParameters.LOCATOR, UiStepParameters.writeLocator(marked)))).isEqualTo(marked);
    }

    @Test
    @DisplayName("there is no way to write an XPath locator — no factory, no strategy")
    void noXPathFactoryExists() {
        assertThat(Arrays.stream(LocatorStrategy.values()).map(Enum::name)).doesNotContain("XPATH");
        assertThat(Arrays.stream(UiLocator.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .map(name -> name.toLowerCase(Locale.ROOT)))
                .as("no public member of UiLocator may offer XPath")
                .noneMatch(name -> name.contains("xpath"));
    }

    @Test
    @DisplayName("describe() renders the locator for logs and failure messages")
    void describeRendersTheLocator() {
        assertThat(UiLocator.testId("submit").describe()).isEqualTo("TEST_ID(submit)");
        assertThat(UiLocator.role("button", "Confirm").describe()).isEqualTo("ROLE(button, \"Confirm\")");
    }
}
