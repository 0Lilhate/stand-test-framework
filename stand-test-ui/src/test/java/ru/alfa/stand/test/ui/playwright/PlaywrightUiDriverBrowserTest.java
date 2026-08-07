package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @TempDir
    Path tempDir;

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

    @Test
    @DisplayName("a real page screenshot is a non-empty PNG written under the requested directory (UITG-S013)")
    void capsuleScreenshotWritesANonEmptyPng() throws Exception {
        this.driver.navigate("/applications/new", TIMEOUT);

        Path shot = this.driver.captureScreenshot(this.tempDir, TIMEOUT);

        assertThat(shot).as("the screenshot path must live under the requested directory").startsWith(this.tempDir);
        byte[] bytes = Files.readAllBytes(shot);
        // PNG starts with the fixed 8-byte signature; a real capture must be both signed and non-empty.
        assertThat(bytes.length).as("a real screenshot must be a non-empty PNG").isGreaterThan(8);
        assertThat(bytes[0]).isEqualTo((byte) 0x89);
        assertThat(bytes[1]).isEqualTo((byte) 'P');
        assertThat(bytes[2]).isEqualTo((byte) 'N');
        assertThat(bytes[3]).isEqualTo((byte) 'G');
    }

    @Test
    @DisplayName("a screenshot masks a sensitive zone — a typed password field is painted over, while an unmasked capture is not (UITG-S017)")
    void sensitiveZoneIsMaskedInTheScreenshot() throws Exception {
        // The unmasked capture first: proves the field really occupies the frame, so the later mask block is
        // not passing vacuously. A password input renders its value as dots, never as the plaintext, so the
        // observable is the opaque block the mask paints over the field, not the value itself.
        this.driver.navigate("/login", TIMEOUT);
        this.driver.fill(UiLocator.testId("login-password"), "some-value", TIMEOUT);
        Path leakyShot = this.driver.captureScreenshot(this.tempDir, TIMEOUT);
        assertThat(countPixels(Files.readAllBytes(leakyShot), 0xFF0000))
                .as("an unmasked screenshot must have no red mask block")
                .isZero();

        // A fresh page, freshly typed: now the sensitive locator is masked before the capture.
        this.driver.navigate("/login", TIMEOUT);
        this.driver.fill(UiLocator.testId("login-password"), "some-value", TIMEOUT);
        int masked = this.driver.maskSensitive(List.of(UiLocator.testId("login-password").asSensitive()), TIMEOUT);
        Path maskedShot = this.driver.captureScreenshot(this.tempDir, TIMEOUT);

        assertThat(masked).as("the sensitive zone was present, so it must be counted as masked").isEqualTo(1);
        assertThat(countPixels(Files.readAllBytes(maskedShot), 0xFF0000))
                .as("the masked screenshot must paint the password field over as an opaque block")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("a mask is consumed by the single artefact it was requested for — a later capture does not inherit it (UITG-T005)")
    void maskedZoneIsNotInheritedByTheNextCapture() throws Exception {
        this.driver.navigate("/login", TIMEOUT);
        this.driver.fill(UiLocator.testId("login-password"), "some-value", TIMEOUT);

        // One masking, then two captures of the SAME frame with no second maskSensitive between them.
        int masked = this.driver.maskSensitive(List.of(UiLocator.testId("login-password").asSensitive()), TIMEOUT);
        Path first = this.driver.captureScreenshot(this.tempDir, TIMEOUT);
        Path second = this.driver.captureScreenshot(this.tempDir, TIMEOUT);

        assertThat(masked).isEqualTo(1);
        assertThat(countPixels(Files.readAllBytes(first), 0xFF0000))
                .as("the artefact the mask was requested for carries it")
                .isGreaterThan(0);
        // The mask must not outlive the capture it was made for: otherwise the second artefact (a trace, a
        // later screenshot — S016) would silently inherit a zone it was never asked to protect.
        assertThat(countPixels(Files.readAllBytes(second), 0xFF0000))
                .as("a subsequent capture with no new maskSensitive must not inherit the stale mask")
                .isZero();
    }

    /**
     * Counts how many decoded pixels are exactly {@code color} (RGB, 24-bit). The login page is white with
     * black text and no imagery, so a nonzero count of pure opaque red can only come from the mask block —
     * a much stronger proof than searching for the value string (which a {@code type=password} input never
     * renders as text).
     */
    private static int countPixels(byte[] pngBytes, int color) throws IOException {
        byte[] rgb = Raster.decode(pngBytes);
        int count = 0;
        int wantRed = (color >> 16) & 0xFF;
        int wantGreen = (color >> 8) & 0xFF;
        int wantBlue = color & 0xFF;
        for (int i = 0; i + 2 < rgb.length; i += 3) {
            if (((rgb[i] & 0xFF) == wantRed) && ((rgb[i + 1] & 0xFF) == wantGreen) && ((rgb[i + 2] & 0xFF) == wantBlue)) {
                count++;
            }
        }
        return count;
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

    /**
     * The <em>pixels</em> of a screenshot, read with the JDK's own {@link ImageIO} (no dependency added):
     * a screenshot is zlib-compressed, so its mask block can only be asserted after decoding. Exposed as a
     * flat, interleaved RGB {@code byte[]} so the caller can count exact pixel colours directly.
     */
    private static final class Raster {

        private Raster() {
        }

        static byte[] decode(byte[] png) throws IOException {
            try (InputStream in = new java.io.ByteArrayInputStream(png)) {
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(in);
                if (image == null) {
                    throw new IOException("ImageIO did not decode the PNG screenshot");
                }
                // Screenshots are RGB(A); strip any alpha for a stable-byte comparison.
                java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(
                        image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = rgb.createGraphics();
                try {
                    g.drawImage(image, 0, 0, null);
                } finally {
                    g.dispose();
                }
                byte[] out = new byte[rgb.getWidth() * rgb.getHeight() * 3];
                int pos = 0;
                for (int y = 0; y < rgb.getHeight(); y++) {
                    for (int x = 0; x < rgb.getWidth(); x++) {
                        int argb = rgb.getRGB(x, y);
                        out[pos++] = (byte) ((argb >> 16) & 0xFF);
                        out[pos++] = (byte) ((argb >> 8) & 0xFF);
                        out[pos++] = (byte) (argb & 0xFF);
                    }
                }
                return out;
            }
        }
    }
}
