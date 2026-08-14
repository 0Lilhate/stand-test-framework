package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiRunSettings;

/**
 * The SEC-05 regression that the trace channel needed and did not have: a value typed while the recorder is
 * suspended must not appear in the exported ZIP, and the run after the resume must still be recorded.
 *
 * <p>This is the leak that a review found by running rather than by reading. A Playwright trace stores the
 * PARAMETERS of the actions it observes, and {@code fill}'s parameter is the typed value — so with
 * {@code trace: on-failure} a sign-in sealed the technical account's password into a ZIP that is then
 * attached to a report. None of the three guards the module already had could reach it: {@code asSensitive()}
 * drives the screenshot mask, {@code UiSecrets} sanitises exception messages, and the file channel cannot run
 * bytes through the report's text masker. Disabling snapshots and screenshots — which the driver already did
 * — removes the screen clone, not the action log.
 *
 * <p>Both halves are asserted on purpose. A fix that simply stopped recording would pass the first assertion
 * and destroy the artefact UITG-S016 exists to produce, so the second one pins that the trace still carries
 * the work that followed the sign-in.
 */
@Tag("browser")
class PlaywrightUiDriverTraceSecretBrowserTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Shaped like a credential and unique enough that a hit cannot be a coincidence. */
    private static final String SECRET = "P4ssw0rd-SENTINEL-9137";

    /** Typed after the recorder resumes: the trace must still be a trace. */
    private static final String AFTER_SIGN_IN = "AFTER-RESUME-4471";

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
    @DisplayName("a value typed while tracing is suspended is absent from the exported trace, and what follows the resume is still recorded")
    void suspendedTracingKeepsACredentialOutOfTheZip() throws Exception {
        ResolvedUiApplication optedIn = new ResolvedUiApplication(
                "client-portal", this.application.baseUrl(), null, null, UiTraceMode.ON_FAILURE);
        this.driver = new PlaywrightDriverFactory().open(optedIn, new UiRunSettings(true, "chromium", TIMEOUT, TIMEOUT));

        this.driver.navigate("/applications/new", TIMEOUT);

        // Exactly the bracket UiLoginService puts around its credential fills.
        this.driver.suspendTracing();
        try {
            this.driver.fill(UiLocator.testId("amount").asSensitive(), SECRET, TIMEOUT);
        } finally {
            this.driver.resumeTracing();
        }
        this.driver.fill(UiLocator.testId("amount"), AFTER_SIGN_IN, TIMEOUT);

        Path trace = this.driver.captureTrace(this.tempDir, Duration.ofSeconds(20));
        assertThat(trace).as("an on-failure run must still export a trace after the bracket").isNotNull();

        List<String> entriesWithSecret = entriesContaining(trace, SECRET);
        assertThat(entriesWithSecret)
                .as("no entry of the trace ZIP may carry the typed credential — before the fix it sat in 'trace.trace'")
                .isEmpty();
        assertThat(entriesContaining(trace, AFTER_SIGN_IN))
                .as("the trace must still record the run after the resume; an empty trace would 'fix' the leak by "
                        + "destroying the artefact UITG-S016 exists to produce")
                .isNotEmpty();
    }

    /** The ZIP entries whose bytes contain the needle, read as UTF-8. */
    private static List<String> entriesContaining(Path zip, String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (new String(readAll(in), StandardCharsets.UTF_8).contains(needle)) {
                    hits.add(entry.getName());
                }
            }
        }
        return hits;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
