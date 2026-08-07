package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiRunSettings;

/**
 * The UITG-S015 network artefact capture against a real browser. Tagged {@code browser}, so it runs only
 * in the module's {@code browserTest} task. The heavy promise of the story is that whether a request
 * reached the backend, and with what code, reaches the failing step's report as a maskable textual
 * attachment — method, path and status, never headers or bodies.
 */
@Tag("browser")
class PlaywrightUiDriverNetworkBrowserTest {

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
    @DisplayName("the driver surfaces the page's requests as method/path/status text — the network story (UITG-S015)")
    void networkStoryIsCapturedAsText() {
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null),
                runSettings());

        // Navigate, then click the button whose callback POSTs to /api/submit. Both the GET of the page and
        // the POST of the submit round-trip must appear in the driver's observed network story.
        this.driver.navigate("/applications/new", TIMEOUT);
        this.driver.click(UiLocator.testId("submit"), TIMEOUT);
        waitFor(story -> story.stream().anyMatch(line -> line.contains("api/submit")));

        List<String> story = this.driver.networkRequests();
        assertThat(story).as("the network story must record the GET of the page")
                .anyMatch(line -> line.startsWith("GET") && line.contains("/applications/new") && line.endsWith("200"));
        assertThat(story).as("the network story must record the POST to /api/submit with its status")
                .anyMatch(line -> line.startsWith("POST") && line.contains("/api/submit") && line.endsWith("204"));
    }

    @Test
    @DisplayName("credential headers never appear in the network story, even when the page sends them (UITG-S015, SEC-05)")
    void credentialHeadersAreNeverReported() {
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null),
                runSettings());

        // The page itself is made to carry a credential-equivalent header on every request; the driver must
        // still report only method/path/status, never the header name or its value. A top-level navigation
        // carries the header without a CORS preflight, so the record for the page really travelled with it.
        this.driver.setExtraHeader("Authorization", "Bearer s3cret-token");
        this.driver.navigate("/applications/new", TIMEOUT);
        waitFor(story -> story.stream().anyMatch(line -> line.contains("/applications/new")));

        assertThat(this.driver.networkRequests())
                .as("the GET that carried the header is still exported as method/path/status")
                .anyMatch(line -> line.startsWith("GET") && line.contains("/applications/new") && line.endsWith("200"))
                .as("no credential header name or value reaches the report")
                .noneMatch(line -> line.contains("Authorization"))
                .noneMatch(line -> line.contains("Bearer"))
                .noneMatch(line -> line.contains("s3cret-token"));
    }

    private void waitFor(Predicate<List<String>> settled) {
        Awaiter.create().await(
                AwaitPolicy.builder("network recorded").timeout(TIMEOUT).pollInterval(Duration.ofMillis(50)).build(),
                () -> List.copyOf(this.driver.networkRequests()),
                settled)
                .orElseThrow();
    }

    private UiRunSettings runSettings() {
        return new UiRunSettings(headless(), "chromium", TIMEOUT, TIMEOUT, this.tempDir);
    }

    private static boolean headless() {
        return Boolean.parseBoolean(System.getProperty(UiRunSettings.HEADLESS_PROPERTY, "true"));
    }
}