package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Opens a driver for one application, for one scenario run.
 *
 * <p>This is the seam the lifecycle decision hangs on: browser-per-run today, a pooled browser with
 * per-run contexts later, or a connection to a remote grid — each is a different factory and none of
 * them changes a single type of the public API or a single executor test.
 */
@FunctionalInterface
public interface UiDriverFactory {

    /**
     * Opens a fresh, isolated browsing session: its own cookies, storage and cache.
     *
     * @param application the resolved application (base URL, viewport)
     * @param settings the run settings (headless, browser, timeouts)
     * @return the driver, owned by the caller and closed through the run's resource scope
     */
    UiDriver open(ResolvedUiApplication application, UiRunSettings settings);

    /**
     * Opens a browsing session restoring a previously saved storage state — how a run signs in as an
     * account that already has a live session instead of passing the login form again.
     *
     * <p>The state must be applied when the browsing context is <em>created</em>; there is no supported way
     * to inject cookies into a context that already exists. That is why the sign-in step opens the session
     * itself rather than reusing one an earlier step happened to open, and why this is a separate method
     * rather than a setter.
     *
     * <p>Defaulted so that a factory written before session reuse existed still compiles and still works
     * for every run that does not reuse a session; asked to restore one, it says clearly that it cannot
     * rather than silently starting a signed-out browser.
     *
     * @param application the resolved application (base URL, viewport)
     * @param settings the run settings (headless, browser, timeouts)
     * @param storageState the state file to restore, or null for a fresh session
     * @return the driver, owned by the caller and closed through the run's resource scope
     */
    default UiDriver open(ResolvedUiApplication application, UiRunSettings settings, Path storageState) {
        if (storageState != null) {
            throw new StandTestException("The UI driver factory " + getClass().getName() + " cannot restore a browser storage state,"
                    + " so the session of application '" + application.alias() + "' cannot be reused. Implement the three-argument open(...) on it,"
                    + " or use auth.scheme FORM without session reuse.");
        }
        return open(application, settings);
    }
}
