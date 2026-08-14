package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One application's browsing session for the duration of one scenario run: the driver, the resolved
 * application it was opened against, and — when the session was restored from a saved state — which file
 * it came from.
 *
 * <p>The session lives in the run's {@code ResourceScope}, never in a field of the executor. That is not
 * a stylistic preference: the executor is discovered once per JVM and shared by every concurrent run, so
 * a session held there would be shared state and parallel runs would see each other's pages. The scope,
 * by contrast, is created fresh per run and closed in the runner's {@code finally} on every outcome.
 *
 * <p>The two mutable pieces of bookkeeping — which account is signed in, and which state file the context
 * was created from — are safe to hold here for the same reason the driver is: a session belongs to exactly
 * one run, and a run is driven on one thread.
 */
final class UiSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(UiSession.class);

    private final String alias;

    private final ResolvedUiApplication application;

    private final UiDriver driver;

    private final Path restoredFrom;

    private String signedInAccountId;

    UiSession(ResolvedUiApplication application, UiDriver driver) {
        this(application, driver, null);
    }

    UiSession(ResolvedUiApplication application, UiDriver driver, Path restoredFrom) {
        this.application = Objects.requireNonNull(application, "application must not be null");
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
        this.alias = application.alias();
        this.restoredFrom = restoredFrom;
    }

    static String resourceKey(String applicationAlias) {
        return "ui.session:" + applicationAlias;
    }

    ResolvedUiApplication application() {
        return this.application;
    }

    UiDriver driver() {
        return this.driver;
    }

    /**
     * The saved state this session's browsing context was created from, or null when it started empty.
     * Only a context created with a state has one: cookies cannot be injected into a context that already
     * exists, which is what makes the order of {@code ui.login} relative to other UI steps matter.
     */
    Path restoredFrom() {
        return this.restoredFrom;
    }

    String signedInAccountId() {
        return this.signedInAccountId;
    }

    void signedInAs(String accountId) {
        this.signedInAccountId = accountId;
    }

    @Override
    public void close() {
        LOG.debug("Closing UI session for application '{}'", this.alias);
        this.driver.close();
    }
}
