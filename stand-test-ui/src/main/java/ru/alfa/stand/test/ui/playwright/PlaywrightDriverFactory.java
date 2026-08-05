package ru.alfa.stand.test.ui.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import ru.alfa.stand.test.core.environment.ViewportProfile;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiDriverFactory;
import ru.alfa.stand.test.ui.UiRunSettings;

/**
 * Opens a Playwright-backed {@link UiDriver}: a browser launched for this run, and a fresh browsing
 * context inside it.
 *
 * <p>Everything that decides <em>how</em> the browser runs — headless or headed, which engine, at which
 * viewport — arrives as configuration ({@link UiRunSettings}, the registry's viewport profile) and never
 * from the scenario, so the same scenario runs unchanged on CI and on a developer's screen.
 */
public final class PlaywrightDriverFactory implements UiDriverFactory {

    /**
     * Creates the factory.
     */
    public PlaywrightDriverFactory() {
    }

    @Override
    public UiDriver open(ResolvedUiApplication application, UiRunSettings settings) {
        return open(application, settings, null);
    }

    @Override
    public UiDriver open(ResolvedUiApplication application, UiRunSettings settings, Path storageState) {
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        Playwright playwright = createPlaywright();
        try {
            Browser browser = browserType(playwright, settings.browser())
                    .launch(new BrowserType.LaunchOptions().setHeadless(settings.headless()));
            BrowserContext context = browser.newContext(contextOptions(application.viewport(), storageState));
            context.setDefaultTimeout((double) settings.actionTimeout().toMillis());
            return new PlaywrightUiDriver(playwright, browser, context, application);
        } catch (RuntimeException failure) {
            playwright.close();
            if (failure instanceof StandTestException classified) {
                throw classified;
            }
            throw new StandTestException("Could not start the '" + settings.browser() + "' browser for application '" + application.alias() + "': " + failure.getMessage(), failure);
        }
    }

    private static Playwright createPlaywright() {
        try {
            return Playwright.create();
        } catch (PlaywrightException unavailable) {
            throw new StandTestException(
                    "Playwright could not start. The browser binaries are installed on first use and cached outside the build; "
                            + "run './gradlew :stand-test-ui:installPlaywrightBrowsers' (or point PLAYWRIGHT_DOWNLOAD_HOST at an internal mirror in a closed network). Cause: "
                            + unavailable.getMessage(),
                    unavailable);
        }
    }

    /**
     * A saved session can only be applied when the context is created — Playwright has no supported way to
     * inject cookies into a live one. That is the whole reason the sign-in step opens the session itself
     * instead of reusing whichever context an earlier step happened to open.
     */
    private static Browser.NewContextOptions contextOptions(ViewportProfile viewport, Path storageState) {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        if (viewport != null) {
            options.setViewportSize(viewport.width(), viewport.height());
        }
        if (storageState != null) {
            options.setStorageStatePath(storageState);
        }
        return options;
    }

    private static BrowserType browserType(Playwright playwright, String browser) {
        return switch (browser.toLowerCase(Locale.ROOT)) {
            case "chromium" -> playwright.chromium();
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> throw new StandTestException("Unknown browser '" + browser + "': set " + UiRunSettings.BROWSER_PROPERTY + " to chromium, firefox or webkit");
        };
    }
}
