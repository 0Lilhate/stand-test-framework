/**
 * The Playwright implementation of the UI driver seam — the <strong>only</strong> package in the SDK
 * allowed to import {@code com.microsoft.playwright}.
 *
 * <p>The confinement is pinned by an architecture test, not by convention: a Playwright type leaking
 * into the adapter's model or into another module would put a browser on the classpath of every
 * protocol test in every consuming project.
 *
 * <p>Nothing here is part of the supported public API. Consumers write scenarios against
 * {@code ru.alfa.stand.test.ui.UiStep}; the driver is reached only through
 * {@code ru.alfa.stand.test.ui.UiDriverFactory}.
 */
package ru.alfa.stand.test.ui.playwright;
