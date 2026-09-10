package ru.alfa.stand.test.ui;

import java.util.Locale;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The grammar of a locator written in configuration: {@code <strategy>=<value>}.
 *
 * <p>It exists because the environment registry has to name the elements of a sign-in form, and the
 * registry lives in {@code stand-test-core}, which has no locator type and must not grow one — a browser
 * vocabulary in core is exactly the dependency ADR-UI-001 forbids. So the registry carries a string, core
 * checks only its shape, and the grammar lives here, next to {@link UiLocator} itself.
 *
 * <table border="1">
 *   <caption>Accepted expressions</caption>
 *   <tr><th>Expression</th><th>Locator</th></tr>
 *   <tr><td>{@code testId=login-submit}</td><td>{@link UiLocator#testId(String)} — preferred</td></tr>
 *   <tr><td>{@code role=button:Sign in}</td><td>{@link UiLocator#role(String, String)}; the accessible name follows the first
 * colon</td></tr>
 *   <tr><td>{@code label=Password}</td><td>{@link UiLocator#label(String)}</td></tr>
 *   <tr><td>{@code text=Sign in}</td><td>{@link UiLocator#text(String)}</td></tr>
 *   <tr><td>{@code css=#login .submit}</td><td>{@link UiLocator#css(String)} — last resort, fragile</td></tr>
 * </table>
 *
 * <p>There is no {@code xpath=} spelling, for the same reason {@link LocatorStrategy} has no XPath
 * constant: the cheapest ban is having nowhere to put it.
 */
final class UiLocatorExpressions {

    private UiLocatorExpressions() {
    }

    /**
     * Parses a configured locator expression.
     *
     * @param expression the expression, as written in the registry
     * @param field the configuration field it came from, for the error message
     * @param application the application alias, for the error message
     * @return the locator
     * @throws StandTestException if the expression names no known strategy or carries no operand
     */
    static UiLocator parse(String expression, String field, String application) {
        if (expression == null || expression.isBlank()) {
            throw new StandTestException("UI application '" + application + "' declares no '" + field
                    + "' locator, which this sign-in needs");
        }
        String trimmed = expression.trim();
        int separator = trimmed.indexOf('=');
        if (separator <= 0 || separator == trimmed.length() - 1) {
            throw new StandTestException(problem(field, application)
                    + ": expected '<strategy>=<value>', for example 'testId=login-submit'");
        }
        String strategy = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        String operand = trimmed.substring(separator + 1).trim();
        if (operand.isEmpty()) {
            throw new StandTestException(problem(field, application) + ": the value after '=' is empty");
        }
        return switch (strategy) {
            case "testid" -> UiLocator.testId(operand);
            case "role" -> role(operand, field, application);
            case "label" -> UiLocator.label(operand);
            case "text" -> UiLocator.text(operand);
            case "css" -> UiLocator.css(operand);
            default -> throw new StandTestException(problem(field, application) + ": unknown strategy '" + strategy
                    + "' — use testId (preferred), role, label, text or css. There is no xpath, by design.");
        };
    }

    private static UiLocator role(String operand, String field, String application) {
        int separator = operand.indexOf(':');
        if (separator <= 0 || separator == operand.length() - 1) {
            throw new StandTestException(problem(field, application)
                    + ": a role locator is spelled 'role=<role>:<accessible name>', for example 'role=button:Sign in'");
        }
        return UiLocator.role(operand.substring(0, separator).trim(), operand.substring(separator + 1).trim());
    }

    /**
     * The offending expression is deliberately not echoed. A field spelled {@code password-locator} is the
     * one place in this configuration where a credential could be typed by mistake, and an error message
     * that printed it back would carry that mistake into the log, the report and CI output.
     */
    private static String problem(String field, String application) {
        return "The '" + field + "' locator of UI application '" + application + "' is not a valid locator expression";
    }
}
