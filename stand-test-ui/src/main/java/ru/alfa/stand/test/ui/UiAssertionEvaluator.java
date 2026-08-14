package ru.alfa.stand.test.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ru.alfa.stand.test.core.assertion.AssertionMatchers;

/**
 * Evaluates {@link UiAssertion}s against an {@link ElementSnapshot}.
 *
 * <p>It owns no comparison logic: every decision is delegated to the core {@code AssertionMatchers}, the
 * same evaluator the REST, Kafka, DB and gRPC adapters use. A fifth private copy of "what equals means"
 * is precisely what this project has already spent effort removing.
 *
 * <p>The single entry point returns the first mismatch as a message, or null when everything holds — the
 * same shape the REST adapter uses, so that the poll condition of {@code ui.expectEventually} and the
 * thrown failure of {@code ui.expect} cannot describe the world differently.
 */
final class UiAssertionEvaluator {

    private static final String MASK = "<masked>";

    private UiAssertionEvaluator() {
    }

    static String firstMismatch(List<UiAssertion> assertions, UiLocator locator, ElementSnapshot snapshot) {
        for (UiAssertion assertion : assertions) {
            Observed observed = observe(assertion, snapshot);
            if (!AssertionMatchers.matches(assertion.matcher(), assertion.expectedValue(), observed.present(), observed.value())) {
                return describeMismatch(assertion, locator, snapshot, observed);
            }
        }
        return null;
    }

    /**
     * The attribute names the driver has to read for these assertions and captures — the driver is asked
     * for exactly what is needed rather than for the whole attribute set.
     */
    static Set<String> attributeNames(List<UiAssertion> assertions, List<UiCapture> captures) {
        Set<String> names = new LinkedHashSet<>();
        for (UiAssertion assertion : assertions) {
            if (assertion.property() == UiProperty.ATTRIBUTE) {
                names.add(assertion.attribute());
            }
        }
        for (UiCapture capture : captures) {
            if (capture.source() == UiCaptureSource.ATTRIBUTE) {
                names.add(capture.attribute());
            }
        }
        return names;
    }

    private static Observed observe(UiAssertion assertion, ElementSnapshot snapshot) {
        return switch (assertion.property()) {
            case TEXT -> new Observed(snapshot.present(), snapshot.text());
            case VALUE -> new Observed(snapshot.present(), snapshot.value());
            case ATTRIBUTE -> {
                String value = snapshot.attribute(assertion.attribute());
                yield new Observed(snapshot.present() && value != null, value);
            }
            case VISIBLE -> new Observed(true, snapshot.visible());
            case ENABLED -> new Observed(true, snapshot.enabled());
        };
    }

    /**
     * Renders the mismatch. On a locator marked {@link UiLocator#sensitive()} neither the expected nor the
     * observed value is printed: the whole point of the mark is that this element's content must not reach
     * a report, a log or CI output. Everything else about the failure — which element, which property,
     * which matcher — is still there, so the message stays actionable.
     */
    private static String describeMismatch(UiAssertion assertion, UiLocator locator, ElementSnapshot snapshot, Observed observed) {
        boolean masked = locator.sensitive();
        StringBuilder message = new StringBuilder("UI assertion failed on ").append(locator.describe())
                .append(": expected ").append(masked ? maskedDescribe(assertion) : assertion.describe());
        if (!snapshot.present()) {
            message.append(" but the element was not found on the page");
        } else if (!observed.present()) {
            message.append(" but the value was not present on the element");
        } else {
            message.append(" but got ").append(masked ? MASK : "<" + observed.value() + ">");
        }
        return message.toString();
    }

    private static String maskedDescribe(UiAssertion assertion) {
        String name = (assertion.property() == UiProperty.ATTRIBUTE) ? assertion.property() + "[" + assertion.attribute() + "]" : assertion.property().toString();
        return name + " " + assertion.matcher() + " " + MASK;
    }

    private record Observed(boolean present, Object value) {
    }
}
