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

    /**
     * Masks the given sensitive zones in the current DOM, so a subsequent screenshot does not expose them.
     *
     * <p>Called by the {@code UiStepExecutor} immediately before {@link #captureScreenshot} on a failing
     * step: the order is the whole point (more visible with a leaky driver), and a driver that cannot mask —
     * or refuses to, because the browser is already gone — must say so by throwing, so the executor then
     * <em>aborts</em> the capture rather than produce an artefact that leaks a secret (UITG-S017, SEC-05).
     *
     * <p>The binding is declarative, not destructive: the mask is remembered by the driver and applied to
     * the screenshot the next {@link #captureScreenshot} takes, without altering the live DOM. The
     * locators passed are already reduced to the failing step's sensitive zone.
     *
     * @param sensitiveLocators the {@link UiLocator#sensitive() sensitive} locators of the elements to mask
     * @param timeout the bound on the masking
     * @return the number of zones actually masked; an element that has vanished since it was observed is not
     *     counted, and that is a diagnostics datum, not a failure
     */
    int maskSensitive(java.util.Collection<UiLocator> sensitiveLocators, java.time.Duration timeout);

    /**
     * Captures a screenshot of the current page into the given directory, as the <em>failure
     * artefact</em> of a broken UI step.
     *
     * <p>The screenshot is taken exactly once on a failing step and never on a green one (plan/ADR-UI-005:
     * "на зелёном прогоне артефакты не снимаются"). The directory is the run's artefacts directory and is
     * already known to exist; the driver picks a unique file name inside it, writes the PNG and returns its
     * path. Nothing here touches a report — the driver only produces the file; deciding whether and how to
     * attach it belongs to the {@code UiStepExecutor}.
     *
     * <p>Declared rather than defaulted on purpose: a driver that cannot screenshot — or refuses to at the
     * moment it is asked (the browser already closed) — must say so by throwing, so the failure path is
     * honest and the executor can log the miss and keep the original step failure. The caller must not
     * treat a thrown capture as a substitute for the step's own failure.
     *
     * @param directory the artefacts directory to write the PNG into (already exists)
     * @param timeout the bound on the capture
     * @return the path of the written PNG
     */
    Path captureScreenshot(Path directory, Duration timeout);

    /**
     * Suspends trace recording around a stretch of work that types a credential, so the secret never enters
     * the recording at all (SEC-05).
     *
     * <p>This exists because masking cannot reach a trace. {@link #maskSensitive} paints over a
     * <em>screenshot</em>, and the recording is started with snapshots and screenshots disabled, so no frame
     * of the screen is kept — but a trace also records the <em>parameters of the actions it saw</em>, and
     * {@link #fill} is an action whose parameter is the value typed. Marking the locator
     * {@link UiLocator#asSensitive() sensitive} does not help there: it drives the screenshot mask and
     * nothing else. Measured, not assumed — a filled value was found verbatim in the {@code trace.trace}
     * entry of an exported ZIP, with snapshots and screenshots already off.
     *
     * <p>So a credential must not be typed while the recorder is running, and the sign-in brackets its fills
     * with this pair. Everything recorded before the suspension is dropped along with it; in the UI branch
     * the sign-in is the first step of the scenario, so what is lost is at most the navigation to the login
     * screen, and what a reader needs — the run after sign-in — is kept.
     *
     * <p><strong>Fail-closed.</strong> A driver that cannot suspend must not let the secret be recorded
     * anyway: it gives up the artefact instead, so a later {@link #captureTrace} answers {@code null}. Losing
     * a trace costs a debugging session; leaking a password costs a rotation. Never throws — the sign-in must
     * not fail because housekeeping did.
     *
     * <p>Defaulted to a no-op: a driver that records nothing has nothing to suspend, and every existing
     * implementation stays source- and binary-compatible.
     */
    default void suspendTracing() {
    }

    /**
     * Resumes trace recording after {@link #suspendTracing}, beginning a fresh recording that the failure
     * path exports. Idempotent, best-effort and never throwing, for the same reasons; a driver that failed to
     * suspend stays suspended rather than resuming into a compromised recording.
     */
    default void resumeTracing() {
    }

    @Override
    void close();

    /**
     * Captures the browser <em>trace</em> of the failing run into the given directory, as a second failure
     * artifact alongside the screenshot.
     *
     * <p>A trace replays the run's actions, their call log and the page's console in the Trace Viewer, so it
     * is the heaviest of the failure artefacts and is recorded only when the application's registry opts in
     * ({@code trace: on-failure}). Called by the {@code UiStepExecutor} on a failing step, strictly after
     * the screenshot: the ordering "mask the sensitive zones, capture" (UITG-S17, SEC-05) holds for the
     * screenshot before the trace is sealed.
     *
     * <p><strong>SEC-05 bound:</strong> a trace is <em>not</em> covered by {@link #maskSensitive} — the mask
     * exists only for the screenshot that follows it, and the ZIP's bytes never pass through the report's
     * text masker. For that reason the recording is started with snapshots disabled (see
     * {@code PlaywrightDriverFactory}), so the trace never carries a DOM clone of a frame that held a typed
     * password or other rendered secret. That has a measured price, and it is not a detail: with snapshots
     * off the Trace Viewer's <em>Network</em> tab stays empty, so the trace is not the network artefact —
     * the network story of a failing step is the {@code ui-network} text attachment (UITG-S015). This bound
     * is load-bearing, not editorial — a driver that opts into screen snapshots here would reopen SEC-05.
     *
     * <p>Unlike {@link #captureScreenshot}, returning {@code null} is <em>not</em> a fault: it is the driver
     * saying it had no recording enabled for this run, and the executor must not treat it as an error any
     * more than it treats "the browser was already gone" as one. A real failure to write the ZIP, once
     * recording was enabled, still throws, so the executor can log the miss and keep the step's own failure.
     *
     * @param directory the artefacts directory to write the {@code .zip} into (already exists)
     * @param timeout the bound on the capture
     * @return the path of the written trace ZIP, or {@code null} when this driver recorded no trace
     */
    Path captureTrace(Path directory, Duration timeout);

    /**
     * The console messages the page has surfed since the browsing context was opened, as a textual
     * failure artefact.
     *
     * <p>A frontend error is often visible first in the console, before it does anything wrong on the
     * screen, so a failing step attaches what the page logged (UITG-S014). The driver records every
     * {@code console} message — from error to log — while it is open and hands them back as text
     * (never {@code null}); the caller decides whether there is anything worth attaching. Exactly because
     * this is a <em>textual</em> artefact it rides the text attachment channel, which the Allure sink runs
     * through the secret masker — the same mask that guards an exception message keeps a secret that a
     * page accidentally logged out of a report (SEC-05, UITG-S015's sibling for the console).
     *
     * <p>Read-copy semantics: the returned list is a snapshot, so a test can assert it without racing the
     * listener that keeps appending. A driver must never return {@code null} — an empty log is the honest
     * "the page logged nothing". A page that could not be probed (the browser already closed) is an empty
     * log, not an error: the executor treats it as a missed artefact, like a screenshot the browser could
     * no longer grab.
     *
     * <p><strong>Contract for implementors: this call must deliver what the browser has already reported.</strong>
     * "Observed so far" means observed by the browser, not "whatever the driver happened to be handed". A
     * driver whose automation library only dispatches events while the owning thread is inside one of its
     * calls — Playwright for Java is one — must pump that queue here, or a caller that merely reads this
     * buffer will miss lines the page emitted seconds earlier. Waiting longer does not fix such a gap; it is
     * precisely the not-calling that withholds the events, so an await polling this method would poll an
     * eternally stale list. Measured on the Playwright driver, where it decided whether a green run was green.
     *
     * @return the console lines observed so far, newest-last, possibly empty
     */
    java.util.List<String> consoleMessages();

    /**
     * The page's network requests observed since the browsing context was opened, as a textual
     * failure artefact.
     *
     * <p>Whether a request reached the backend, and with what code, is the first thing a red UI run's
     * report should be able to answer (UITG-S015). The driver records the <em>method, path and status</em>
     * of every request the page makes while it is open — observation only, never interception or
     * re-routing, which stays out of scope (BR-33) — and hands them back as text (never {@code null});
     * the caller decides whether there is anything worth attaching.
     *
     * <p>SEC-05: the request bodies and response bodies are <strong>never</strong> recorded — a body may
     * carry personal data, and in wave 1 the artefact deliberately keeps to method/path/status — and the
     * credential-equivalent {@code Authorization} and {@code Cookie} headers are masked at the point of a
     * record, on the driver, so they never reach a report even before the text channel runs through the
     * secret masker.
     *
     * <p>Read-copy semantics: the returned list is a snapshot, so a test can assert it without racing the
     * listener that keeps appending. A driver must never return {@code null} — the page that made no
     * requests is the honest empty list. A page that could not be probed (the browser already closed) is
     * an empty list, not an error: the executor treats it as a missed artefact, like a screenshot the
     * browser could no longer grab.
     *
     * <p><strong>Same contract for implementors as {@link #consoleMessages()}:</strong> this call must deliver
     * the exchanges the browser has already completed, pumping the automation library's event queue when that
     * library only dispatches during its own calls. Without it a response can be missing from the story while
     * the server has long since served it — and no timeout recovers it, because waiting is exactly what
     * fails to dispatch.
     *
     * @return the observed requests, in the order the responses arrived, possibly empty
     */
    java.util.List<String> networkRequests();
}
