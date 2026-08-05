package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A {@link UiDriverFactory} that hands out {@link FakeUiDriver}s and records what it was asked for —
 * in particular whether a saved session was restored, which is the one thing a test cannot observe from
 * the driver alone.
 */
final class FakeUiDriverFactory implements UiDriverFactory {

    // Concurrent: one factory instance serves every run, exactly as the Playwright one does, and the
    // parallel suite opens sessions from several threads at once.
    private final List<FakeUiDriver> opened = new CopyOnWriteArrayList<>();

    private final List<Path> restoredFrom = Collections.synchronizedList(new ArrayList<>());

    private final Consumer<FakeUiDriver> programmer;

    private RuntimeException restoreFailure;

    private RuntimeException openFailure;

    FakeUiDriverFactory() {
        this(driver -> { });
    }

    FakeUiDriverFactory(Consumer<FakeUiDriver> programmer) {
        this.programmer = programmer;
    }

    @Override
    public UiDriver open(ResolvedUiApplication application, UiRunSettings settings) {
        return open(application, settings, null);
    }

    /**
     * Makes the next attempt to open a session <em>from a saved state</em> fail, once — how a test reproduces
     * a state file that passed the store's shape check and that the browser still refuses. One-shot on
     * purpose: the interesting behaviour is what the executor does on the retry.
     *
     * @param failure what the browser throws when handed the state
     * @return this factory
     */
    FakeUiDriverFactory refusingRestoredStateOnce(RuntimeException failure) {
        this.restoreFailure = failure;
        return this;
    }

    /**
     * Makes every attempt to open a session fail — a browser that will not start, which is the ordinary way
     * into the window between leasing an account and registering the lease in the run's resource scope.
     *
     * @param failure what opening throws
     * @return this factory
     */
    FakeUiDriverFactory failingToOpenWith(RuntimeException failure) {
        this.openFailure = failure;
        return this;
    }

    @Override
    public UiDriver open(ResolvedUiApplication application, UiRunSettings settings, Path storageState) {
        if (this.openFailure != null) {
            throw this.openFailure;
        }
        if (storageState != null && this.restoreFailure != null) {
            RuntimeException refusal = this.restoreFailure;
            this.restoreFailure = null;
            this.restoredFrom.add(storageState);
            throw refusal;
        }
        FakeUiDriver driver = new FakeUiDriver();
        this.programmer.accept(driver);
        this.opened.add(driver);
        this.restoredFrom.add(storageState);
        return driver;
    }

    FakeUiDriver only() {
        if (this.opened.size() != 1) {
            throw new IllegalStateException("expected exactly one opened driver, but " + this.opened.size() + " were opened");
        }
        return this.opened.get(0);
    }

    List<FakeUiDriver> opened() {
        return List.copyOf(this.opened);
    }

    List<Path> restoredFrom() {
        return new ArrayList<>(this.restoredFrom);
    }
}
