package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiRunSettings;

/**
 * The UITG-S014 console artefact capture against a real browser. Tagged {@code browser}, so it runs only
 * in the module's {@code browserTest} task. The heavy promise of the story is that a page's console — the
 * first place a frontend bug announces itself — reaches the failing step's report as a maskable textual
 * attachment, collected by the driver from the moment the page is open.
 */
@Tag("browser")
class PlaywrightUiDriverConsoleBrowserTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private LocalUiTestApplication application;

    private UiDriver driver;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startApplication() {
        this.application = new LocalUiTestApplication();
    }

    @AfterEach
    void closeBrowser() {
        if (this.driver != null) {
            this.driver.close();
            this.driver = null;
        }
        if (this.application != null) {
            this.application.close();
        }
    }

    @Test
    @DisplayName("a page that logs to the console is heard by the driver, as a textual list (UITG-S014)")
    void pageConsoleIsCapturedAsText() {
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null),
                runSettings());

        this.driver.navigate("/console-error", TIMEOUT);

        // The console is collected from the moment the page is created; the semantics is "everything the
        // page logged", newest-last. A real error line and a real warning are both kept, exactly what a
        // red run's report wants to show.
        assertThat(this.driver.consoleMessages()).as("the driver must surface the page's console lines")
                .anyMatch(line -> line.contains("could not resolve its address"))
                .anyMatch(line -> line.startsWith("error:"))
                .anyMatch(line -> line.startsWith("warning:") && line.contains("fallback"));
    }

    @Test
    @DisplayName("a page that logs nothing yields an empty console, and the empty list is not an error (UITG-S014)")
    void quietPageYieldsAnEmptyConsole() {
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null),
                runSettings());

        // The regular application page logs nothing to the console.
        this.driver.navigate("/applications/new", TIMEOUT);

        assertThat(this.driver.consoleMessages()).as("a quiet page must produce an empty console, never null")
                .isEmpty();
    }

    private UiRunSettings runSettings() {
        return new UiRunSettings(headless(), "chromium", TIMEOUT, TIMEOUT, this.tempDir);
    }

    private static boolean headless() {
        return Boolean.parseBoolean(System.getProperty(UiRunSettings.HEADLESS_PROPERTY, "true"));
    }
}