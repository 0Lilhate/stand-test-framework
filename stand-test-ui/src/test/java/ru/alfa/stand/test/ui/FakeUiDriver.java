package ru.alfa.stand.test.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A programmable {@link UiDriver} that never touches a browser: the seam that lets the executor be
 * tested exhaustively and deterministically.
 */
final class FakeUiDriver implements UiDriver {

    private final Map<UiLocator, List<ElementSnapshot>> snapshots = new LinkedHashMap<>();

    private final List<String> calls = new ArrayList<>();

    private final Map<String, String> extraHeaders = new LinkedHashMap<>();

    private String currentUrl = "http://localhost/";

    private RuntimeException clickFailure;

    private RuntimeException closeFailure;

    private RuntimeException fillFailure;

    private boolean closed;

    private Path savedStorageState;

    private UiLocator submitLocator;

    private UiLocator signedInLocator;

    private Runnable onClose = () -> { };

    private String fixedUrl;

    private Duration navigationCost = Duration.ZERO;

    private Runnable onNavigate = () -> { };

    private int screenshotCalls;

    /** The file the last {@link #captureScreenshot} wrote, if any. */
    private Path screenshotFile;

    /** Each {@link #maskSensitive} call's requested zones, in order. */
    private final List<Collection<UiLocator>> maskCalls = new ArrayList<>();

    /** Each {@link #maskSensitive} call's reported masked count, in order. */
    private final List<Integer> maskCounts = new ArrayList<>();

    /** When set, {@code maskSensitive} throws instead of masking. */
    private RuntimeException maskFailure;

    /** When true, the driver reports trace recording enabled and captures a trace. */
    private boolean traceEnabled;

    /** How many times {@code captureTrace} was called. */
    private int traceCalls;

    /** When set, {@code captureTrace} throws. */
    private RuntimeException traceFailure;

    /** The console lines the page is programmed to have logged, for the UITG-S014 console artefact tests. */
    private List<String> consoleMessages = List.of();

    /** The network exchanges the page is programmed to have made, for the UITG-S015 network artefact tests. */
    private List<String> networkRequests = List.of();

    /** Programs the page's console log, as something the page said over its lifetime. */
    FakeUiDriver console(String... lines) {
        this.consoleMessages = (lines == null) ? List.of() : List.copyOf(Arrays.asList(lines));
        return this;
    }

    /** Programs the page's network story, as exchanges the page made over its lifetime. */
    FakeUiDriver network(String... lines) {
        this.networkRequests = (lines == null) ? List.of() : List.copyOf(Arrays.asList(lines));
        return this;
    }

    FakeUiDriver present(UiLocator locator, String text) {
        return snapshot(locator, new ElementSnapshot(true, true, true, text, null, Map.of()));
    }

    FakeUiDriver snapshot(UiLocator locator, ElementSnapshot... sequence) {
        this.snapshots.put(locator, new ArrayList<>(List.of(sequence)));
        return this;
    }

    FakeUiDriver failClickWith(RuntimeException failure) {
        this.clickFailure = failure;
        return this;
    }

    FakeUiDriver failCloseWith(RuntimeException failure) {
        this.closeFailure = failure;
        return this;
    }

    /** Makes {@code fill} throw — the seam for proving that a driver echoing a credential cannot leak it. */
    FakeUiDriver failFillWith(RuntimeException failure) {
        this.fillFailure = failure;
        return this;
    }

    /**
     * Makes {@code maskSensitive} throw — the seam for proving that a driver which cannot mask aborts the
     * capture rather than produce an artefact that leaks a secret (UITG-S017 negative scenario).
     */
    FakeUiDriver failMaskWith(RuntimeException failure) {
        this.maskFailure = failure;
        return this;
    }

    /** Makes {@code captureTrace} throw — the seam for proving a trace miss is logged, not fatal. */
    FakeUiDriver failTraceWith(RuntimeException failure) {
        this.traceFailure = failure;
        return this;
    }

    /** Declares this run to record a trace and a video, as an {@code on-failure} application opted in. */
    FakeUiDriver withTrace() {
        this.traceEnabled = true;
        return this;
    }

    /**
     * Behaves like an application that signs in: the element proving a live session appears only once the
     * given control has been clicked. Programming the transition rather than a fixed sequence of snapshots
     * is what makes the sign-in tests deterministic — the marker appears because the form was submitted,
     * not because a poll happened to be the third one.
     */
    FakeUiDriver signsInOn(UiLocator submit, UiLocator signedIn) {
        this.submitLocator = submit;
        this.signedInLocator = signedIn;
        return this;
    }

    Path savedStorageState() {
        return this.savedStorageState;
    }

    /**
     * Makes every navigation cost real time. The contention tests need a run to HOLD its account long enough
     * for a queued run's wait to be measurable: against an infinitely fast driver a whole scenario finishes
     * inside a millisecond, and "how long did this run queue" rounds to zero however real the queueing was.
     */
    FakeUiDriver costingPerNavigation(Duration cost) {
        this.navigationCost = cost;
        return this;
    }

    /**
     * Runs on every navigation — the seam a test uses to make two concurrent runs genuinely overlap, rather
     * than hope the scheduler interleaves them.
     */
    FakeUiDriver onNavigate(Runnable action) {
        this.onNavigate = action;
        return this;
    }

    /** Reports a fixed address, whatever the navigation — how a test gives the page a query string. */
    FakeUiDriver reportingUrl(String url) {
        this.fixedUrl = url;
        return this;
    }

    /** Runs while the driver is closing — the seam for observing what is and is not released yet. */
    FakeUiDriver onClose(Runnable action) {
        this.onClose = action;
        return this;
    }

    List<String> calls() {
        return List.copyOf(this.calls);
    }

    Map<String, String> extraHeaders() {
        return Map.copyOf(this.extraHeaders);
    }

    boolean closed() {
        return this.closed;
    }

    @Override
    public void navigate(String relativePath, Duration timeout) {
        this.calls.add("navigate:" + relativePath);
        this.onNavigate.run();
        sleep(this.navigationCost);
        if (this.fixedUrl == null) {
            this.currentUrl = "http://localhost" + relativePath;
        }
    }

    @Override
    public ElementSnapshot snapshot(UiLocator locator, Collection<String> attributes, Duration probeTimeout) {
        this.calls.add("snapshot:" + locator.describe());
        List<ElementSnapshot> sequence = this.snapshots.get(locator);
        if (sequence == null || sequence.isEmpty()) {
            return ElementSnapshot.absent();
        }
        // The last programmed snapshot is sticky, so a poll settles instead of running off the end.
        return (sequence.size() == 1) ? sequence.get(0) : sequence.remove(0);
    }

    @Override
    public void click(UiLocator locator, Duration timeout) {
        this.calls.add("click:" + locator.describe());
        if (this.clickFailure != null) {
            throw this.clickFailure;
        }
        if (locator.equals(this.submitLocator)) {
            present(this.signedInLocator, "Signed in");
        }
    }

    @Override
    public void fill(UiLocator locator, String value, Duration timeout) {
        this.calls.add("fill:" + locator.describe() + "=" + value);
        if (this.fillFailure != null) {
            throw this.fillFailure;
        }
    }

    @Override
    public void suspendTracing() {
        this.calls.add("suspendTracing");
    }

    @Override
    public void resumeTracing() {
        this.calls.add("resumeTracing");
    }

    @Override
    public void saveStorageState(Path target) {
        this.calls.add("saveStorageState:" + target);
        this.savedStorageState = target;
        try {
            Files.writeString(target, "{\"cookies\":[],\"origins\":[]}", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the fake storage state to " + target, failure);
        }
    }

    @Override
    public void setExtraHeader(String name, String value) {
        this.extraHeaders.put(name, value);
    }

    @Override
    public String currentUrl() {
        return (this.fixedUrl == null) ? this.currentUrl : this.fixedUrl;
    }

    /**
     * Records the capture (so a test can assert it happened exactly once — or, on a green step, never)
     * and writes a real, non-empty, PNG-magic file: the executor's contract is about the artefact, and a
     * test that asserts on the file's content needs it to be a believable PNG, not a zero-byte stub.
     */
    @Override
    public Path captureScreenshot(Path directory, Duration timeout) {
        this.calls.add("captureScreenshot");
        this.screenshotCalls++;
        try {
            Path target = directory.resolve("fake-screenshot-" + System.nanoTime() + ".png");
            Files.write(target, PNG_SIGNATURE);
            this.screenshotFile = target;
            return target;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the fake screenshot to " + directory, failure);
        }
    }

    /** How many times {@code captureScreenshot} has been called this session. */
    int screenshotCalls() {
        return this.screenshotCalls;
    }

    /**
     * Records the trace capture (once per failing run when recording was declared) and writes a real,
     * non-empty, ZIP-magic file so a content check recognises it. A {@link #failTraceWith} failure is thrown
     * before any file is written, which is what an executor that keeps the step's own failure must contain.
     */
    @Override
    public Path captureTrace(Path directory, Duration timeout) {
        this.traceCalls++;
        this.calls.add("captureTrace");
        if (this.traceFailure != null) {
            throw this.traceFailure;
        }
        if (!this.traceEnabled) {
            return null;
        }
        try {
            Path target = directory.resolve("fake-trace-" + System.nanoTime() + ".zip");
            Files.write(target, ZIP_SIGNATURE);
            return target;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the fake trace to " + directory, failure);
        }
    }

    /** How many times {@code captureTrace} was called this session. */
    int traceCalls() {
        return this.traceCalls;
    }

    /**
     * Returns the programmed console log. The seam a UITG-S014 test uses to prove that a failing step's
     * report carries the console as a text attachment when there is something to carry, and nothing when
     * there is not.
     */
    @Override
    public List<String> consoleMessages() {
        return List.copyOf(this.consoleMessages);
    }

    /**
     * Returns the programmed network story. The seam a UITG-S015 test uses to prove that a failing step's
     * report carries the page's requests as a text attachment when there is something to carry, and nothing
     * when there is not.
     */
    @Override
    public List<String> networkRequests() {
        return List.copyOf(this.networkRequests);
    }

    /**
     * Records the request (so a test can assert it happened, that it happened before the capture, and with
     * which zones) and reports as masked every zone it was asked to mask. A {@link #failMaskWith} failure
     * is thrown here, before any capture, which is what an executor that "masks then captures, or not at
     * all" must honour.
     */
    @Override
    public int maskSensitive(Collection<UiLocator> sensitiveLocators, Duration timeout) {
        List<UiLocator> captured = (sensitiveLocators == null) ? List.of() : List.copyOf(sensitiveLocators);
        this.calls.add("maskSensitive:" + captured.size());
        this.maskCalls.add(captured);
        if (this.maskFailure != null) {
            throw this.maskFailure;
        }
        this.maskCounts.add(captured.size());
        return captured.size();
    }

    /** The zones each {@code maskSensitive} call was asked to close, in order. */
    List<Collection<UiLocator>> maskCalls() {
        return List.copyOf(this.maskCalls);
    }

    /** The masked-zone counts each {@code maskSensitive} call reported, in order. */
    List<Integer> maskCounts() {
        return List.copyOf(this.maskCounts);
    }

    /** The file the last capture wrote, or null when no capture has been requested. */
    Path screenshotFile() {
        return this.screenshotFile;
    }

    /** The PNG magic bytes, so a fake screenshot is recognised as image/png by a content check. */
    private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** The ZIP magic bytes, so a fake trace is recognised as application/zip by a content check. */
    private static final byte[] ZIP_SIGNATURE = new byte[]{'P', 'K', 0x03, 0x04};

    private static void sleep(Duration cost) {
        if (cost.isZero() || cost.isNegative()) {
            return;
        }
        try {
            Thread.sleep(cost.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.calls.add("close");
        this.onClose.run();
        if (this.closeFailure != null) {
            throw this.closeFailure;
        }
    }
}
