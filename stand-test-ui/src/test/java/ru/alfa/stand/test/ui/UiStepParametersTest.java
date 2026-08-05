package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

class UiStepParametersTest {

    private static final Set<String> NOT_WIRE_KEYS = Set.of(
            "TYPE_PREFIX", "OPEN_TYPE", "CLICK_TYPE", "FILL_TYPE", "EXPECT_TYPE", "EXPECT_EVENTUALLY_TYPE");

    @Test
    @DisplayName("every parameter key is a core StepParameterKeys constant, so the validator's key-based checks reach ui.* too")
    void stepParameterKeysAreMirrored() throws IllegalAccessException {
        Set<String> coreKeys = Arrays.stream(StepParameterKeys.class.getDeclaredFields())
                .filter(field -> field.getType() == String.class && Modifier.isStatic(field.getModifiers()))
                .map(UiStepParametersTest::value)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<Field> wireKeys = Arrays.stream(UiStepParameters.class.getDeclaredFields())
                .filter(field -> field.getType() == String.class && Modifier.isStatic(field.getModifiers()))
                .filter(field -> !NOT_WIRE_KEYS.contains(field.getName()))
                .toList();

        assertThat(wireKeys).as("the mirroring check must not be vacuous").isNotEmpty();
        for (Field field : wireKeys) {
            assertThat(coreKeys).as("UiStepParameters.%s must mirror a core StepParameterKeys constant", field.getName()).contains((String) field.get(null));
        }
    }

    @Test
    @DisplayName("the type prefix is the core one, and every step type sits under it")
    void stepTypesShareTheCorePrefix() {
        assertThat(UiStepParameters.TYPE_PREFIX).isEqualTo(StepParameterKeys.UI_PREFIX);
        assertThat(List.of(
                UiStepParameters.OPEN_TYPE,
                UiStepParameters.CLICK_TYPE,
                UiStepParameters.FILL_TYPE,
                UiStepParameters.EXPECT_TYPE,
                UiStepParameters.EXPECT_EVENTUALLY_TYPE,
                UiStepParameters.LOGIN_TYPE))
                .allMatch(type -> type.startsWith(StepParameterKeys.UI_PREFIX));
        // The sign-in type is the one core knows by name, because the validator has a rule about it — so it
        // must BE the core constant, not a second spelling of it.
        assertThat(UiStepParameters.LOGIN_TYPE).isSameAs(StepParameterKeys.UI_LOGIN_TYPE);
    }

    @Test
    @DisplayName("a locator survives the round trip through the parameter map")
    void locatorRoundTrips() {
        UiLocator role = UiLocator.role("button", "Confirm");

        assertThat(UiStepParameters.locator(Map.of(UiStepParameters.LOCATOR, UiStepParameters.writeLocator(role)))).isEqualTo(role);
        assertThat(UiStepParameters.locator(Map.of(UiStepParameters.LOCATOR, UiStepParameters.writeLocator(UiLocator.testId("x"))))).isEqualTo(UiLocator.testId("x"));
    }

    @Test
    @DisplayName("an assertion without an explicit matcher means EQUALS, as everywhere else in the SDK")
    void absentMatcherMeansEquals() {
        Map<String, Object> parameters = Map.of(UiStepParameters.ASSERTIONS, List.of(Map.of(
                UiStepParameters.PROPERTY, "TEXT",
                UiStepParameters.EXPECTED_VALUE, "Accepted")));

        assertThat(UiStepParameters.assertions(parameters)).containsExactly(new UiAssertion(UiProperty.TEXT, "Accepted", AssertionMatcher.EQUALS));
    }

    @Test
    @DisplayName("a malformed parameter map is a configuration error, reported as such")
    void malformedParametersAreRefused() {
        assertThatThrownBy(() -> UiStepParameters.locator(Map.of(UiStepParameters.LOCATOR, "not-a-map"))).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> UiStepParameters.assertions(Map.of(UiStepParameters.ASSERTIONS, "not-a-list"))).isInstanceOf(StandTestException.class);
        assertThatThrownBy(() -> UiStepParameters.assertions(Map.of(UiStepParameters.ASSERTIONS, List.of(Map.of(UiStepParameters.PROPERTY, "NOPE", UiStepParameters.EXPECTED_VALUE, "x")))))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unknown value");
        assertThatThrownBy(() -> UiStepParameters.positiveMillis(Map.of(UiStepParameters.TIMEOUT_MILLIS, -1L), UiStepParameters.TIMEOUT_MILLIS, 1L))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> UiStepParameters.requireString(Map.of(), UiStepParameters.APPLICATION)).isInstanceOf(StandTestException.class);
    }

    @Test
    @DisplayName("a capture survives the round trip, attribute and all")
    void captureRoundTrips() {
        UiCapture capture = new UiCapture("number", UiLocator.testId("number"), UiCaptureSource.ATTRIBUTE, "data-value");

        assertThat(UiStepParameters.captures(Map.of(UiStepParameters.CAPTURES, List.of(UiStepParameters.writeCapture(capture))))).containsExactly(capture);
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }
}
