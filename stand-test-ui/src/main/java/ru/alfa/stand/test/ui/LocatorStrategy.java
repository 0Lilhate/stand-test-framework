package ru.alfa.stand.test.ui;

/**
 * How a {@link UiLocator} addresses an element on the page, in the priority order the SDK asks test
 * authors (and the generating agent) to follow: the earlier the constant, the more resilient the
 * locator is to markup drift.
 *
 * <p>There is deliberately no XPath constant. XPath adds no expressiveness over CSS and survives
 * layout drift far worse; leaving it out of the enum (and out of {@link UiLocator}'s factories) is the
 * cheapest possible ban — the same device by which the SDK forbids literal URLs: there is no parameter
 * to put one in.
 */
public enum LocatorStrategy {

    /** {@code data-testid} attribute — the only strategy that is not considered fragile. */
    TEST_ID,

    /** ARIA role plus accessible name. */
    ROLE,

    /** Form label text. */
    LABEL,

    /** Visible text content. */
    TEXT,

    /** CSS selector — the last resort; always fragile. */
    CSS
}
