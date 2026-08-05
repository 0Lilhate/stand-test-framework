package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.ui.ElementSnapshot;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiElementNotActionableException;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiRunSettings;

/**
 * The driver against a real browser and a real page. Tagged {@code browser}: it runs in the module's
 * {@code browserTest} task, which needs the Chromium binaries and fails loudly without them rather than
 * passing vacuously.
 */
@Tag("browser")
class PlaywrightUiDriverBrowserTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private LocalUiTestApplication application;

    private UiDriver driver;

    @BeforeEach
    void openBrowser() {
        this.application = new LocalUiTestApplication();
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null),
                new UiRunSettings(headless(), "chromium", TIMEOUT, TIMEOUT));
    }

    @AfterEach
    void closeBrowser() {
        if (this.driver != null) {
            this.driver.close();
        }
        if (this.application != null) {
            this.application.close();
        }
    }

    @Test
    @DisplayName("every locator strategy addresses the element it claims to")
    void everyLocatorStrategyResolves() {
        this.driver.navigate("/applications/new", TIMEOUT);

        assertThat(snapshot(UiLocator.testId("submit")).text()).isEqualTo("Confirm");
        assertThat(snapshot(UiLocator.role("button", "Confirm")).text()).isEqualTo("Confirm");
        assertThat(snapshot(UiLocator.label("Amount")).present()).isTrue();
        assertThat(snapshot(UiLocator.text("New application")).text()).isEqualTo("New application");
        assertThat(snapshot(UiLocator.css("#submit")).text()).isEqualTo("Confirm");
    }

    @Test
    @DisplayName("a snapshot reports visibility, enabled state, text, input value and the attributes asked for")
    void snapshotReportsTheElementState() {
        this.driver.navigate("/applications/new", TIMEOUT);
        this.driver.fill(UiLocator.testId("amount"), "100000", TIMEOUT);

        ElementSnapshot amount = this.driver.snapshot(UiLocator.testId("amount"), Set.of("data-testid"), TIMEOUT);
        ElementSnapshot cancel = this.driver.snapshot(UiLocator.testId("cancel"), Set.of(), TIMEOUT);

        assertThat(amount.present()).isTrue();
        assertThat(amount.visible()).isTrue();
        assertThat(amount.enabled()).isTrue();
        assertThat(amount.value()).isEqualTo("100000");
        assertThat(amount.attribute("data-testid")).isEqualTo("amount");
        assertThat(cancel.enabled()).as("a disabled control must read as disabled").isFalse();
    }

    @Test
    @DisplayName("an element that is not on the page is absent, not an exception — absence is data")
    void missingElementIsAbsentNotAnError() {
        this.driver.navigate("/applications/new", TIMEOUT);

        ElementSnapshot missing = this.driver.snapshot(UiLocator.testId("nothing-here"), Set.of(), Duration.ofMillis(200));

        assertThat(missing).isEqualTo(ElementSnapshot.absent());
    }

    @Test
    @DisplayName("a hidden element is present but not visible")
    void hiddenElementIsPresentButNotVisible() {
        this.driver.navigate("/applications/new", TIMEOUT);

        ElementSnapshot number = this.driver.snapshot(UiLocator.testId("number"), Set.of(), Duration.ofMillis(500));

        assertThat(number.present()).isTrue();
        assertThat(number.visible()).isFalse();
    }

    @Test
    @DisplayName("clicking an element that is not there is 'not actionable', which the executor reads as a failed expectation")
    void clickingAMissingElementIsNotActionable() {
        this.driver.navigate("/applications/new", TIMEOUT);

        assertThatThrownBy(() -> this.driver.click(UiLocator.testId("nothing-here"), Duration.ofMillis(300)))
                .isInstanceOf(UiElementNotActionableException.class)
                .hasMessageContaining("not clickable");
    }

    @Test
    @DisplayName("navigating to an address nobody answers is infrastructure breakage, not a failed expectation")
    void navigationToADeadPortIsInfrastructure() {
        UiDriver deadEnd = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", "http://127.0.0.1:1", null),
                new UiRunSettings(headless(), "chromium", TIMEOUT, Duration.ofSeconds(3)));
        try {
            assertThatThrownBy(() -> deadEnd.navigate("/applications/new", Duration.ofSeconds(3)))
                    .isInstanceOf(StandTestException.class)
                    .isNotInstanceOf(UiElementNotActionableException.class);
        } finally {
            deadEnd.close();
        }
    }

    @Test
    @DisplayName("the page's own requests carry the header the SDK injected")
    void extraHeaderReachesTheServer() {
        this.driver.setExtraHeader("X-Correlation-Id", "corr-42");
        this.driver.navigate("/applications/new", TIMEOUT);
        this.driver.click(UiLocator.testId("submit"), TIMEOUT);
        waitForSubmission();

        assertThat(this.application.submissions()).isNotEmpty();
        assertThat(this.application.submissions().get(0)).containsEntry("x-correlation-id", "corr-42");
    }

    @Test
    @DisplayName("a locator that matches several elements is refused by name, not by Playwright's strict-mode wording")
    void ambiguousLocatorIsRefusedWithAUsefulMessage() {
        this.driver.navigate("/applications/new", TIMEOUT);

        assertThatThrownBy(() -> this.driver.snapshot(UiLocator.css("button"), Set.of(), Duration.ofMillis(500)))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("CSS(button)")
                .hasMessageContaining("matched 2 elements");
    }

    @Test
    @DisplayName("the driver reports the address it is on, for the step's diagnostics")
    void currentUrlIsReported() {
        this.driver.navigate("/applications/new", TIMEOUT);

        assertThat(this.driver.currentUrl()).isEqualTo(this.application.baseUrl() + "/applications/new");
    }

    @Test
    @DisplayName("the build really forwards the headed/headless switch into the test JVM — the toggle is wiring, not documentation")
    void headlessSwitchIsForwardedByTheBuild() {
        // Without this the toggle could be a README promise: the browserTest task sets the property, and if
        // it ever stopped, every run would silently be headless and nobody would notice until debugging.
        String forwarded = System.getProperty(UiRunSettings.HEADLESS_PROPERTY);

        assertThat(forwarded).as("the browserTest task must pass %s into the test JVM", UiRunSettings.HEADLESS_PROPERTY).isNotNull();
        assertThat(UiRunSettings.fromSystemProperties().headless()).isEqualTo(Boolean.parseBoolean(forwarded));
    }

    private ElementSnapshot snapshot(UiLocator locator) {
        return this.driver.snapshot(locator, Set.of(), TIMEOUT);
    }

    /**
     * Waits for the page's asynchronous POST by observing the page itself — the status text the callback
     * sets — rather than by sleeping.
     */
    private void waitForSubmission() {
        ru.alfa.stand.test.await.Awaiter.create().await(
                ru.alfa.stand.test.await.AwaitPolicy.builder("submission recorded").timeout(TIMEOUT).pollInterval(Duration.ofMillis(50)).build(),
                () -> List.copyOf(this.application.submissions()),
                recorded -> !recorded.isEmpty())
                .orElseThrow();
    }

    private static boolean headless() {
        return !"false".equals(System.getProperty(UiRunSettings.HEADLESS_PROPERTY));
    }
}
