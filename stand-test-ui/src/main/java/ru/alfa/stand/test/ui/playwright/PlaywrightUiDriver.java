package ru.alfa.stand.test.ui.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.Tracing;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.ui.ElementSnapshot;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiElementNotActionableException;
import ru.alfa.stand.test.ui.UiLocator;

/**
 * The Playwright-backed {@link UiDriver}: one {@link Playwright} instance, one {@link Browser}, one
 * {@link BrowserContext} and one {@link Page}, all owned by a single scenario run and all closed
 * together.
 *
 * <p>The context is what makes runs isolated — its own cookies, storage and cache — and it is the object
 * the run's resource scope ultimately releases. Wave 1 deliberately launches a browser per run: slower
 * than pooling, but obviously correct and free of shared state, which is the right order in which to
 * acquire the two.
 */
final class PlaywrightUiDriver implements UiDriver {

    private static final Logger LOG = LoggerFactory.getLogger(PlaywrightUiDriver.class);

    private final Playwright playwright;

    private final Browser browser;

    private final BrowserContext context;

    private final Page page;

    private final ResolvedUiApplication application;

    /**
     * The locators to mask on the next {@link #captureScreenshot}. Recorded by {@link #maskSensitive} and
     * handed to Playwright as a screenshot-option mask; the DOM itself is left untouched (A06 in the plan).
     *
     * <p>A mask is consumed by the single {@link #captureScreenshot} it was requested for, then released:
     * the list is emptied as soon as the capture has been handed the zones, so a later capture on the same
     * driver (a trace, a second screenshot) never inherits a stale mask — masking lives exactly as long as
     * the artefact it was asked to protect, not until the next {@code maskSensitive} (UITG-T005, SEC-05).
     * There is no concurrent capture — the SDK's invariant is one run, one thread.
     */
    private final List<Locator> maskedLocators = new ArrayList<>();

    /**
     * Whether this run actually started recording, decided by the application's registry declaration
     * ({@code trace: on-failure}). Recording is enabled in the factory and only there — a run that never
     * opted in has never called {@code tracing().start()}, so {@code captureTrace} answers {@code null}
     * without asking the browser for a trace it never began (UITG-S016).
     */
    private final boolean traceEnabled;

    /**
     * Whether the run's trace has already been sealed or released. Playwright's {@code tracing().stop()} may
     * be called once: a second call throws, and a repeated failure in one session (unusual, but not
     * impossible) must not turn into one more loss of the original step failure. Sealing is therefore
     * idempotent — the trace is exported or released at most once, and a driver whose trace is already gone
     * answers {@code null} like one that never recorded.
     */
    private boolean traceStopped;

    /**
     * The page's console lines observed since this run's page was opened, newest-last (UITG-S014). A
     * frontend error is often visible in the console before it does anything visible, so the failing run
     * attaches what the page logged. Recorded on the driver (not the executor) because only the adapter
     * can see the page's console; surfaced through the driver-agnostic seam {@link UiDriver#consoleMessages}
     * so the executor and the failing step's report need to know nothing about Playwright.
     *
     * <p>Deliberately a {@link CopyOnWriteArrayList}: the listeners ({@code page.onConsoleMessage} etc.) run
     * on Playwright's event-dispatch path, which is not guaranteed to be the same thread as the run thread
     * that reads the snapshot in a failing step's {@link UiDriver#consoleMessages()}. Copy-on-write makes a
     * concurrent append and a concurrent {@code read} race-free, at the cost of one copy per append.
     */
    private final List<String> consoleMessages = new CopyOnWriteArrayList<>();

    /**
     * The page's network requests observed since this run's page was opened, in the order their responses
     * arrived, newest-last (UITG-S015). Whether a request reached the backend, and with what code, is the
     * first thing a red run's report should be able to answer, so the failing run attaches method/path/status
     * of everything the page asked for. Recorded on the driver (not the executor) because only the adapter
     * can see the page's traffic; surfaced through the driver-agnostic seam {@link UiDriver#networkRequests}
     * so the executor and the failing step's report need to know nothing about Playwright.
     *
     * <p>Observation only — {@code page.onResponse} — never {@code route}, which is interception and stays
     * out of scope (BR-33). Deliberately a {@link CopyOnWriteArrayList}, for the same reason as the console
     * log above: the response listener runs on Playwright's event path, which is not guaranteed to be the
     * run thread that reads the snapshot, and copy-on-write makes the concurrent append/read race-free.
     */
    private final List<String> networkRequests = new CopyOnWriteArrayList<>();

    PlaywrightUiDriver(Playwright playwright, Browser browser, BrowserContext context, ResolvedUiApplication application) {
        this.playwright = Objects.requireNonNull(playwright, "playwright must not be null");
        this.browser = Objects.requireNonNull(browser, "browser must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.application = Objects.requireNonNull(application, "application must not be null");
        this.traceEnabled = (application.trace() == UiTraceMode.ON_FAILURE);
        this.page = context.newPage();
        // The console is recorded from the moment the page is created — "за время шага" in the story's
        // wording means everything the step surfaced before it went red, and the failure path attaches it.
        // Every level is kept: a warning the symptom of a bug is as relevant to a broken run as an error.
        this.page.onConsoleMessage(this::recordConsoleMessage);
        // The network story is recorded the same way — every response the page receives from the moment it
        // exists. Only method/path/status are kept and the credential headers are masked as the record is
        // built (UITG-S015, SEC-05); a body is never read, so a red run can show "it reached the backend
        // and got a 500" without ever copying what the payload carried.
        this.page.onResponse(this::recordResponse);
    }

    private void recordConsoleMessage(ConsoleMessage message) {
        if (message == null) {
            return;
        }
        // "type: text" is the same shape a developer reads in the browser's own console, and it costs the
        // reader nothing to scan; an empty text is still a message the page chose to log, so it is kept.
        this.consoleMessages.add(message.type() + ": " + message.text());
    }

    /**
     * Records a finished network exchange as the flat text line a failure report is read from:
     * {@code METHOD path status}. The path is the URL with its query and fragment dropped — a query string
     * can carry one-time tokens and other credential-equivalent material, and the value of the diagnostic is
     * <em>which</em> path, not its parameters. Only method/path/status are recorded at all (SEC-05): a body,
     * request or response, may carry personal data, so none of it ever reaches the list. The credential
     * headers {@code Authorization}/{@code Cookie} are <em>never read</em> — they are not part of the record
     * by construction, so a token cannot reach a report even before the text channel runs through the secret
     * masker. Nothing here ever reads a header or a body.
     *
     * @param response the completed response to record, or {@code null} for a defensive skip
     */
    private void recordResponse(Response response) {
        if (response == null) {
            return;
        }
        Request request = response.request();
        if (request == null) {
            return;
        }
        String method = request.method();
        if (method == null) {
            method = "";
        }
        this.networkRequests.add(method + " " + withoutQuery(request.url()) + " " + response.status());
    }

    /**
     * Drops the query string and fragment from a URL, keeping the path — the value of a network diagnostic.
     * A query string can carry a one-time token or a search term that is credential-equivalent (UITG-S015,
     * SEC-05), so the report names <em>which</em> resource was asked for, never its parameters.
     *
     * @param url the full URL as Playwright reported it, or {@code null} for a defensive empty path
     * @return the URL without its {@code ?…} and {@code #…} suffixes
     */
    private static String withoutQuery(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int cut = (query < 0) ? fragment : ((fragment < 0) ? query : Math.min(query, fragment));
        return (cut < 0) ? url : url.substring(0, cut);
    }

    @Override
    public void navigate(String relativePath, Duration timeout) {
        String url = this.application.urlFor(relativePath);
        try {
            this.page.navigate(url, new Page.NavigateOptions().setTimeout(millis(timeout)));
        } catch (TimeoutError timedOut) {
            throw new StandTestException("The page at '" + url + "' did not load within " + timeout, timedOut);
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not open '" + url + "': " + failure.getMessage(), failure);
        }
    }

    @Override
    public ElementSnapshot snapshot(UiLocator locator, Collection<String> attributes, Duration probeTimeout) {
        Locator element = PlaywrightLocators.locator(this.page, locator);
        double timeout = millis(probeTimeout);
        try {
            int matches = element.count();
            if (matches == 0) {
                return ElementSnapshot.absent();
            }
            if (matches > 1) {
                // Playwright would fail this on its own further down with a strict-mode error about a
                // resolved selector; saying it here names the locator the test author actually wrote.
                throw new StandTestException("Locator " + locator.describe() + " matched " + matches + " elements — a step must address exactly one; narrow the locator");
            }
            boolean visible = element.isVisible();
            boolean enabled = element.isEnabled();
            String text = element.textContent(new Locator.TextContentOptions().setTimeout(timeout));
            return new ElementSnapshot(true, visible, enabled, text, inputValue(element, timeout), readAttributes(element, attributes, timeout));
        } catch (TimeoutError timedOut) {
            // A probe that could not complete in its (short) budget is an observation of "not there yet",
            // not a broken driver: the await engine polls again, and the step's own timeout still bounds it.
            LOG.debug("UI probe of {} timed out after {}", locator.describe(), probeTimeout);
            return ElementSnapshot.absent();
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not observe " + locator.describe() + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public void click(UiLocator locator, Duration timeout) {
        Locator element = PlaywrightLocators.locator(this.page, locator);
        try {
            element.click(new Locator.ClickOptions().setTimeout(millis(timeout)));
        } catch (TimeoutError timedOut) {
            throw new UiElementNotActionableException("element " + locator.describe() + " was not clickable within " + timeout, timedOut);
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not click " + locator.describe() + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public void fill(UiLocator locator, String value, Duration timeout) {
        Locator element = PlaywrightLocators.locator(this.page, locator);
        try {
            element.fill(value, new Locator.FillOptions().setTimeout(millis(timeout)));
        } catch (TimeoutError timedOut) {
            throw new UiElementNotActionableException("element " + locator.describe() + " was not fillable within " + timeout, timedOut);
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not fill " + locator.describe() + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public void setExtraHeader(String name, String value) {
        this.context.setExtraHTTPHeaders(Map.of(name, value));
    }

    @Override
    public void saveStorageState(Path target) {
        try {
            this.context.storageState(new BrowserContext.StorageStateOptions().setPath(target));
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not save the browser session of application '" + this.application.alias() + "' to " + target + ": " + failure.getMessage(), failure);
        }
    }

    @Override
    public String currentUrl() {
        return this.page.url();
    }

    @Override
    public Path captureScreenshot(Path directory, Duration timeout) {
        // UUID, not System.nanoTime(): several runs may fail at the same instant in parallel within one
        // JVM (the module runs classes concurrently on a single pool), and a nanoTime collision would make
        // the second screenshot silently overwrite the first — one failure's artefact lost without an error.
        String name = "screenshot-" + UUID.randomUUID() + ".png";
        Path target = directory.resolve(name);
        try {
            // Playwright's screenshot has no per-call timeout of its own: it is a synchronous grab of the
            // current frame. The bound asked for is therefore not applied by Playwright — it is kept in the
            // contract because the seam stays symmetric with the other driver calls; the caller still bounds
            // the effort by calling this once, never in a retry loop. If the browser is already gone the call
            // throws below and the executor treats it as a missed artefact, not a masked step failure.
            //
            // The zones recorded by the preceding maskSensitive are drained into THIS screenshot only, then
            // released: a later capture on the same driver must never inherit a mask that was meant for the
            // one that consumed it (UITG-T005). The drain happens before the grab, so even a failed grab
            // leaves no stale mask for the next artefact.
            List<Locator> masks = new ArrayList<>(this.maskedLocators);
            this.maskedLocators.clear();
            Page.ScreenshotOptions options = new Page.ScreenshotOptions().setPath(target);
            if (!masks.isEmpty()) {
                // Opaque red: unmistakable in pixel inspection, and the masking contract is "painted over",
                // not "blurred" — a blur still leaks a value rendered in the frame (SEC-05, UITG-S017).
                options.setMask(masks).setMaskColor("#FF0000");
            }
            this.page.screenshot(options);
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not capture a screenshot for application '" + this.application.alias() + "': " + failure.getMessage(), failure);
        }
        return target;
    }

    @Override
    public Path captureTrace(Path directory, Duration timeout) {
        // A driver that never recorded — the application opted out, so tracing().start() was never called —
        // answers null: an absent artefact is a normal outcome, not an error (UITG-S016). stop() writes the
        // ZIP accumulated since context creation and frees the recording buffer, so it is called exactly
        // once, guarded by the traceStopped flag.
        if (!this.traceEnabled || this.traceStopped) {
            return null;
        }
        this.traceStopped = true;
        String name = "trace-" + UUID.randomUUID() + ".zip";
        try {
            this.context.tracing().stop(new Tracing.StopOptions().setPath(directory.resolve(name)));
            return directory.resolve(name);
        } catch (PlaywrightException failure) {
            throw new StandTestException("Could not export the browser trace for application '" + this.application.alias() + "': " + failure.getMessage(), failure);
        }
    }

    @Override
    public List<String> consoleMessages() {
        // A defensive copy: the returned snapshot is handed to the failing step's report, which must never
        // observe the driver's growing buffer mutating (one run, one thread — but the copy keeps the
        // contract honest and the artefact stable).
        return List.copyOf(this.consoleMessages);
    }

    @Override
    public List<String> networkRequests() {
        // A defensive copy, for the same reason the console list is copied: the snapshot reaches the
        // failing step's report and must stay stable while the listener keeps appending (one run, one
        // thread — but the copy keeps the "never return null, never leak a mutating buffer" contract).
        return List.copyOf(this.networkRequests);
    }

    @Override
    public int maskSensitive(Collection<UiLocator> sensitiveLocators, Duration timeout) {
        this.maskedLocators.clear();
        int masked = 0;
        for (UiLocator locator : sensitiveLocators) {
            Locator element = PlaywrightLocators.locator(this.page, locator);
            this.maskedLocators.add(element);
            // A zone is counted only while it is really on the page: one that has vanished since it was
            // observed (count == 0) contributes nothing and is reported as a diagnostics datum, not an error.
            // A browser already gone makes count() throw, which the executor reads as "abort the capture".
            if (element.count() > 0) {
                masked++;
            }
        }
        return masked;
    }

    @Override
    public void close() {
        // Reverse order of acquisition, and every step attempted even if an earlier one failed: a leaked
        // browser process outlives the JVM's usefulness on a CI agent.
        releaseActiveTrace();
        closeQuietly(this.page::close, "page");
        closeQuietly(this.context::close, "browser context");
        closeQuietly(this.browser::close, "browser");
        closeQuietly(this.playwright::close, "playwright");
    }

    /**
     * Stops tracing when a trace was started but never captured — the green-run case of an
     * {@code on-failure} application, or a failure on a non-UI step. {@code tracing().stop()} with no path
     * merely ends the recording and releases the buffer that Playwright has been accumulating since
     * context creation; it writes no file, so a green run leaves no artefact (the "no artefact on a green
     * step" rule, plan §17), and it must be called at most once, which {@code traceStopped} guarantees. A
     * trace already exported by {@link #captureTrace} is not touched again.
     */
    private void releaseActiveTrace() {
        if (this.traceEnabled && !this.traceStopped) {
            this.traceStopped = true;
            try {
                this.context.tracing().stop();
            } catch (PlaywrightException alreadyGone) {
                // The browser is closing anyway; a failed release is not a reason to harden the close path.
                LOG.warn("Could not release the browser trace for application '{}': {}", this.application.alias(), alreadyGone.getMessage());
            }
        }
    }

    private static String inputValue(Locator element, double timeout) {
        try {
            return element.inputValue(new Locator.InputValueOptions().setTimeout(timeout));
        } catch (PlaywrightException notAnInput) {
            // Asking a <div> for its value is a legitimate outcome, not an error: there is none.
            return null;
        }
    }

    private static Map<String, String> readAttributes(Locator element, Collection<String> attributes, double timeout) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> read = new LinkedHashMap<>();
        for (String name : attributes) {
            String value = element.getAttribute(name, new Locator.GetAttributeOptions().setTimeout(timeout));
            if (value != null) {
                read.put(name, value);
            }
        }
        return read;
    }

    private static void closeQuietly(Runnable close, String what) {
        try {
            close.run();
        } catch (RuntimeException failure) {
            LOG.warn("Failed to close the {} of a UI session: {}", what, failure.toString());
        }
    }

    private static double millis(Duration duration) {
        return (double) duration.toMillis();
    }
}
