package ru.alfa.stand.test.ui.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.exception.StandTestException;
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

    PlaywrightUiDriver(Playwright playwright, Browser browser, BrowserContext context, ResolvedUiApplication application) {
        this.playwright = Objects.requireNonNull(playwright, "playwright must not be null");
        this.browser = Objects.requireNonNull(browser, "browser must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.application = Objects.requireNonNull(application, "application must not be null");
        this.page = context.newPage();
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
    public void close() {
        // Reverse order of acquisition, and every step attempted even if an earlier one failed: a leaked
        // browser process outlives the JVM's usefulness on a CI agent.
        closeQuietly(this.page::close, "page");
        closeQuietly(this.context::close, "browser context");
        closeQuietly(this.browser::close, "browser");
        closeQuietly(this.playwright::close, "playwright");
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
