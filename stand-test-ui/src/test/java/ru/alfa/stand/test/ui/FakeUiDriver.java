package ru.alfa.stand.test.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
