package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
 * The UITG-S016 playwright-trace capture against a real browser. Tagged {@code browser}, so it runs only in
 * the module's {@code browserTest} task. The heavy part of the story is that the whole recording is gated by
 * the application's registry declaration: with {@code OFF} nothing is recorded, with {@code on-failure} a
 * recorded run seals its Playwright trace (a ZIP readable by the Trace Viewer) on the failure path.
 */
@Tag("browser")
class PlaywrightUiDriverTraceBrowserTest {

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
    @DisplayName("an on-failure application seals a non-empty ZIP trace that the Playwright Trace Viewer opens (UITG-S016)")
    void onFailureApplicationSealsATrace() throws Exception {
        ResolvedUiApplication optedIn = new ResolvedUiApplication(
                "client-portal", this.application.baseUrl(), null, null, UiTraceMode.ON_FAILURE);
        this.driver = new PlaywrightDriverFactory().open(optedIn, runSettings());

        this.driver.navigate("/applications/new", TIMEOUT);
        this.driver.fill(UiLocator.testId("amount"), "100000", TIMEOUT);
        this.driver.click(UiLocator.testId("submit"), TIMEOUT);
        waitWhilePending();

        // The trace is sealed deterministically on the failure path: tracing().stop(setPath) writes a ZIP
        // that the Playwright Trace Viewer opens. It is the container Playwright itself writes, so a sane
        // ZIP local-file-header magic is the honest, portable promise the viewer will read it.
        Path trace = this.driver.captureTrace(this.tempDir, Duration.ofSeconds(20));
        assertThat(trace).as("an on-failure run must export its trace").isNotNull();
        assertThat(Files.exists(trace)).as("the trace ZIP must exist on disk").isTrue();
        assertThat(Files.size(trace)).as("an exported trace must not be empty").isGreaterThan(0);
        assertThat(trace.getFileName().toString()).endsWith(".zip");
        assertThat(readFirstBytes(trace, 4)).as("a Playwright trace must start with the ZIP local-file-header magic")
                .containsExactly((byte) 'P', (byte) 'K', 0x03, 0x04);
    }

    @Test
    @DisplayName("the exported trace carries no DOM clone and no network story — the price of snapshots=false, measured on the artefact (UITG-F004)")
    void exportedTraceCarriesNoDomCloneAndNoNetworkStory() throws Exception {
        // What a trace CONTAINS was never measured: UITG-S016 pinned that a ZIP is written and that a
        // credential is not in it, and the manual run of UITG-F004 then found that the recording options the
        // factory chose for SEC-05 (snapshots=false) also empty the Trace Viewer's Network tab. Both halves
        // are asserted here on the artefact itself, because the two are the same decision seen from two
        // sides: no DOM clone is the security property, no network story is what it costs.
        ResolvedUiApplication optedIn = new ResolvedUiApplication(
                "client-portal", this.application.baseUrl(), null, null, UiTraceMode.ON_FAILURE);
        this.driver = new PlaywrightDriverFactory().open(optedIn, runSettings());

        this.driver.navigate("/applications/new", TIMEOUT);
        this.driver.fill(UiLocator.testId("amount"), "100000", TIMEOUT);
        this.driver.click(UiLocator.testId("submit"), TIMEOUT);
        waitWhilePending();

        // Non-vacuity: the run really did talk to the backend, so an empty trace.network below is a property
        // of the trace and not of an idle page. This is also the positive half of the statement the README
        // makes — the network story of a failing step travels in the ui-network TEXT attachment (UITG-S015).
        assertThat(this.driver.networkRequests()).as("the run must have observed network exchanges").isNotEmpty();

        Path trace = this.driver.captureTrace(this.tempDir, Duration.ofSeconds(20));
        assertThat(trace).as("an on-failure run must export its trace").isNotNull();
        try (ZipFile zip = new ZipFile(trace.toFile())) {
            List<String> entries = zip.stream().map(ZipEntry::getName).toList();
            assertThat(entries).as("the trace must carry its action timeline").contains("trace.trace");
            assertThat(entries)
                    .as("a resource entry is a DOM/asset clone of what the frame showed — exactly what snapshots=false exists to keep out of the artefact (SEC-05)")
                    .noneMatch(name -> name.startsWith("resources/"));
            ZipEntry network = zip.getEntry("trace.network");
            assertThat(network == null || network.getSize() == 0L)
                    .as("with snapshots off the Trace Viewer's Network tab stays empty — the trace is not the network artefact")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the default OFF mode records no trace, even when asked to capture (UITG-S016)")
    void offApplicationRecordsNoTrace() {
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null, null, UiTraceMode.OFF),
                runSettings());

        this.driver.navigate("/applications/new", TIMEOUT);

        assertThat(this.driver.captureTrace(this.tempDir, Duration.ofSeconds(20)))
                .as("an OFF application must export no trace — tracing was never started")
                .isNull();
    }

    @Test
    @DisplayName("on a green run the active trace is released on close without writing an artefact file (MEDIUM-6)")
    void greenRunReleasesTraceWithoutWritingAFile() throws Exception {
        // An on-failure application starts tracing at context creation, but a green run never calls
        // captureTrace; the trace buffer must be released on close without leaving a ZIP behind — the "no
        // artefact on a green step" rule (plan §17) holds even for the trace.
        ResolvedUiApplication optedIn = new ResolvedUiApplication(
                "client-portal", this.application.baseUrl(), null, null, UiTraceMode.ON_FAILURE);
        this.driver = new PlaywrightDriverFactory().open(optedIn, runSettings());

        this.driver.navigate("/applications/new", TIMEOUT);
        this.driver.fill(UiLocator.testId("amount"), "100000", TIMEOUT);

        // No captureTrace call — this is the green path. Closing releases the buffer without writing a file.
        this.driver.close();
        this.driver = null;

        assertThat(traceFilesUnder(this.tempDir)).as("a green run must not leave a trace ZIP in the artefacts directory").isEmpty();
    }

    private UiRunSettings runSettings() {
        return new UiRunSettings(headless(), "chromium", TIMEOUT, TIMEOUT, this.tempDir);
    }

    private void waitWhilePending() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        // A bounded poll is the honest wait for the local page's async submission (not a production sleep,
        // and the sole mechanism the other browser tests already rely on for the same page).
        while (System.currentTimeMillis() < deadline) {
            if (!this.application.submissions().isEmpty()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the local application did not record the submission in time");
    }

    private static byte[] readFirstBytes(Path file, int count) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(count);
        }
    }

    private static boolean headless() {
        return Boolean.parseBoolean(System.getProperty(UiRunSettings.HEADLESS_PROPERTY, "true"));
    }

    private static List<Path> traceFilesUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".zip")).toList();
        } catch (IOException failure) {
            return List.of();
        }
    }
}