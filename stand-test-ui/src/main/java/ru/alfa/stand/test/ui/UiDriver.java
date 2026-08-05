package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;

/**
 * The seam between the UI adapter and a browser: everything the executor needs, and nothing about
 * Playwright. It exists for the same reason {@code HttpCaller} exists in the REST adapter — so that the
 * step executor can be tested exhaustively without the real transport, and so that swapping the browser
 * backend (a grid, a different automation library) stays a local change.
 *
 * <p>A driver instance belongs to exactly one scenario run and is closed by the runner's resource scope.
 * Implementations need not be thread-safe: the SDK's invariant is one run, one thread.
 */
public interface UiDriver extends AutoCloseable {

    /**
     * Navigates to a path relative to the application's base URL.
     *
     * @param relativePath the relative path
     * @param timeout the bound on the navigation
     */
    void navigate(String relativePath, Duration timeout);

    /**
     * Observes an element. Must not throw because the element is missing — that is
     * {@link ElementSnapshot#absent()} — and must not wait longer than the probe timeout, which is the
     * budget of a single poll, not of the step.
     *
     * @param locator the element to observe
     * @param attributes the attribute names the caller needs read
     * @param probeTimeout the bound on this single observation
     * @return what was observed
     */
    ElementSnapshot snapshot(UiLocator locator, Collection<String> attributes, Duration probeTimeout);

    /**
     * Clicks an element, waiting up to the timeout for it to become actionable.
     *
     * @param locator the element to click
     * @param timeout the bound on the action
     */
    void click(UiLocator locator, Duration timeout);

    /**
     * Types a value into an element, waiting up to the timeout for it to become actionable.
     *
     * @param locator the element to type into
     * @param value the value to type
     * @param timeout the bound on the action
     */
    void fill(UiLocator locator, String value, Duration timeout);

    /**
     * Adds a header to every request the page makes from now on — how the SDK-owned correlation id
     * reaches the backend from a browser step.
     *
     * @param name the header name
     * @param value the header value
     */
    void setExtraHeader(String name, String value);

    /**
     * Writes the browsing session's state — cookies and origin storage — to a file, so a later run signing
     * in as the same account can restore it instead of passing the login form again.
     *
     * <p>The file is a secret: whoever holds it is signed in. Implementations write it and nothing else;
     * deciding where it goes, when it is discarded and that it never reaches a report belongs to
     * {@code StorageStateStore}.
     *
     * <p>Declared here rather than defaulted, on purpose: a driver that silently could not save a session
     * would turn "reuse the session" into "sign in every time" — a performance regression nobody would
     * notice for months. A driver that cannot do it should say so by throwing.
     *
     * @param target the file to write (its directory already exists)
     */
    void saveStorageState(Path target);

    /**
     * The address currently shown, for diagnostics.
     *
     * @return the current URL
     */
    String currentUrl();

    @Override
    void close();
}
