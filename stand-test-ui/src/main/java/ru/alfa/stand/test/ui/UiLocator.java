package ru.alfa.stand.test.ui;

import java.util.Objects;

/**
 * An element address: a {@link LocatorStrategy} plus its operand (and, for {@link LocatorStrategy#ROLE},
 * the accessible name).
 *
 * <p>This type is public because it is meant to live as constants in the consumer's Page Objects. If it
 * were internal, locators would leak into test bodies and the Page Object pattern — the one mitigation
 * the SDK has against markup drift — would be unavailable.
 *
 * <p>Instances are created through the factories below; there is no XPath factory, by design
 * (see {@link LocatorStrategy}).
 *
 * @param strategy how the element is addressed
 * @param value the operand: test id, role name, label text, visible text or CSS selector
 * @param accessibleName the accessible name, only for {@link LocatorStrategy#ROLE}; null otherwise
 * @param sensitive whether the element holds a secret or personal data (see {@link #sensitive()})
 */
public record UiLocator(LocatorStrategy strategy, String value, String accessibleName, boolean sensitive) {

    /**
     * Validates the locator: the strategy and operand are mandatory, and an accessible name is
     * meaningful only for {@link LocatorStrategy#ROLE}.
     */
    public UiLocator {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("locator value must not be blank");
        }
        if (strategy == LocatorStrategy.ROLE) {
            if (accessibleName == null || accessibleName.isBlank()) {
                throw new IllegalArgumentException("a ROLE locator requires an accessible name");
            }
        } else if (accessibleName != null) {
            throw new IllegalArgumentException("an accessible name is only meaningful for a ROLE locator, but strategy was " + strategy);
        }
    }

    /**
     * Creates a non-sensitive locator.
     *
     * @param strategy how the element is addressed
     * @param value the operand
     * @param accessibleName the accessible name, only for a ROLE locator
     */
    public UiLocator(LocatorStrategy strategy, String value, String accessibleName) {
        this(strategy, value, accessibleName, false);
    }

    /**
     * Addresses the element by its {@code data-testid} — priority 1, the only non-fragile strategy.
     *
     * @param testId the {@code data-testid} attribute value
     * @return the locator
     */
    public static UiLocator testId(String testId) {
        return new UiLocator(LocatorStrategy.TEST_ID, testId, null);
    }

    /**
     * Addresses the element by ARIA role and accessible name — priority 2.
     *
     * @param role the ARIA role, for example {@code button} or {@code textbox}
     * @param name the accessible name
     * @return the locator
     */
    public static UiLocator role(String role, String name) {
        return new UiLocator(LocatorStrategy.ROLE, role, name);
    }

    /**
     * Addresses a form control by its label text — priority 3.
     *
     * @param labelText the label text
     * @return the locator
     */
    public static UiLocator label(String labelText) {
        return new UiLocator(LocatorStrategy.LABEL, labelText, null);
    }

    /**
     * Addresses the element by its visible text — priority 4.
     *
     * @param text the visible text
     * @return the locator
     */
    public static UiLocator text(String text) {
        return new UiLocator(LocatorStrategy.TEXT, text, null);
    }

    /**
     * Addresses the element by a CSS selector — the last resort, always {@link #fragile()}.
     *
     * @param selector the CSS selector
     * @return the locator
     */
    public static UiLocator css(String selector) {
        return new UiLocator(LocatorStrategy.CSS, selector, null);
    }

    /**
     * Marks the element as holding a secret or personal data: what it contains is masked wherever the SDK
     * would otherwise print it — today in assertion failure messages and await diagnostics, and, when
     * artefact capture arrives, in the DOM before a screenshot or trace is taken.
     *
     * <p>Masking the failure message is the point: an assertion on a password field would otherwise put
     * the password into the report, the log and the CI output, which is exactly what the SDK's
     * secret-reference discipline exists to prevent everywhere else.
     *
     * <p>Named {@code asSensitive} rather than {@code sensitive}: the record component of that name
     * already generates the {@code sensitive()} predicate, which reads symmetrically with
     * {@link #fragile()} and is what the evaluator asks. A record cannot have both.
     *
     * @return a copy of this locator marked sensitive
     */
    public UiLocator asSensitive() {
        return new UiLocator(this.strategy, this.value, this.accessibleName, true);
    }

    /**
     * Whether this locator is considered fragile, i.e. tied to something other than an explicit test id.
     *
     * <p>This is the single definition of fragility in the SDK: the generation report and any static
     * count of fragile locators must use this method rather than re-deriving the rule, or the two will
     * drift apart.
     *
     * @return true for every strategy except {@link LocatorStrategy#TEST_ID}
     */
    public boolean fragile() {
        return this.strategy != LocatorStrategy.TEST_ID;
    }

    /**
     * A short, log- and report-friendly rendering, for example {@code TEST_ID(submit)} or
     * {@code ROLE(button, "Confirm")}.
     *
     * @return the description
     */
    public String describe() {
        if (this.strategy == LocatorStrategy.ROLE) {
            return this.strategy + "(" + this.value + ", \"" + this.accessibleName + "\")";
        }
        return this.strategy + "(" + this.value + ")";
    }
}
