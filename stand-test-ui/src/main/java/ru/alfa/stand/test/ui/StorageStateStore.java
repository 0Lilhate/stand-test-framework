package ru.alfa.stand.test.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Where a browser session is kept between runs, and the rules for keeping it.
 *
 * <p><strong>Keyed by account, never by suite.</strong> The path is
 * {@code <artifacts>/storage-state/<environment>/<application>/<accountId>.json}, so two runs holding two
 * different accounts read and write two different files and cannot see each other's session. Keying the
 * state by the suite — one file per application — would make session reuse and run isolation contradict
 * each other, which is exactly the trap BR-29 names.
 *
 * <p><strong>The file is a secret.</strong> It holds live session cookies: anyone with the file is signed
 * in. It is therefore never attached to a report, never logged and never printed; only its path appears in
 * diagnostics, and it lives in the artefacts directory under the same retention rules as the rest of the
 * failure artefacts (SEC-09).
 *
 * <p>Reading is fail-safe rather than fail-closed, and deliberately so: a state file is a cache, not
 * configuration. A file that is missing, empty, unreadable or not a JSON object is deleted and treated as
 * absent, so a corrupted cache costs one sign-in instead of failing a run. What is <em>not</em> silently
 * tolerated is an expired-but-well-formed session; that is a run-time discovery, and the sign-in step
 * handles it explicitly.
 */
final class StorageStateStore {

    /** Directory under the artefacts root that holds every application's session states. */
    static final String DIRECTORY = "storage-state";

    private static final Logger LOG = LoggerFactory.getLogger(StorageStateStore.class);

    private static final Pattern UNSAFE_IN_FILE_NAME = Pattern.compile("[^A-Za-z0-9._-]+");

    private final Path root;

    StorageStateStore(Path artifactsDirectory) {
        this.root = Objects.requireNonNull(artifactsDirectory, "artifactsDirectory must not be null").resolve(DIRECTORY);
    }

    /**
     * The file this account's session lives in. A pure function of the artefacts directory, the environment,
     * the application and the account id — no state, no IO.
     *
     * <p>The environment is part of the path because the account pool is keyed by it: the same alias and the
     * same account id in {@code ift} and in {@code dev} are two independently leasable accounts on two
     * different stands. Leaving it out would let a DEV run restore an IFT session cookie — one file, two
     * meanings.
     *
     * @param environment the scenario environment
     * @param application the UI application alias
     * @param accountId the account the session belongs to
     * @return the state file path (which need not exist)
     */
    Path pathFor(String environment, String application, String accountId) {
        return this.root.resolve(safe(environment)).resolve(safe(application)).resolve(safe(accountId) + ".json");
    }

    /**
     * Whether the file holds a session worth trying, deleting it when it does not.
     *
     * @param stateFile the state file
     * @return true when the file exists and looks like a Playwright storage state
     */
    boolean usable(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return false;
        }
        String content;
        try {
            content = Files.readString(stateFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            LOG.debug("Discarding unreadable browser session state {}: {}", stateFile, unreadable.toString());
            delete(stateFile);
            return false;
        }
        if (content.isBlank() || !content.stripLeading().startsWith("{")) {
            LOG.debug("Discarding malformed browser session state {} (not a JSON object)", stateFile);
            delete(stateFile);
            return false;
        }
        return true;
    }

    /**
     * Removes a session state. Used when a session turns out to be expired or malformed: keeping a state
     * known to be dead would make every later run pay for a doomed reuse attempt before signing in.
     *
     * @param stateFile the state file
     */
    void delete(Path stateFile) {
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException failure) {
            // Best-effort: an undeletable cache file must not fail a run. The next attempt re-reads it,
            // finds it unusable again, and signs in — slower, but correct.
            LOG.warn("Could not delete the browser session state {}: {}", stateFile, failure.toString());
        }
    }

    /**
     * Creates the directory the state file will be written into.
     *
     * @param stateFile the state file
     * @throws StandTestException if the directory cannot be created
     */
    void prepareFor(Path stateFile) {
        Path parent = stateFile.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException failure) {
            throw new StandTestException("Could not create the directory for the browser session state at " + parent
                    + " — set " + UiRunSettings.ARTIFACTS_DIRECTORY_PROPERTY + " to a writable location", failure);
        }
    }

    /**
     * Turns an alias or an account id into one safe file-name component.
     *
     * <p>Both come from configuration rather than from a scenario, so this is defence in depth rather than
     * a boundary — but a component that reduced to {@code .} or {@code ..} would step out of the artefacts
     * directory, and the cost of refusing that is one comparison. Separators are already replaced, so what
     * remains is the all-dots case.
     */
    private static String safe(String component) {
        String sanitised = UNSAFE_IN_FILE_NAME.matcher(component.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
        if (sanitised.isEmpty() || sanitised.chars().allMatch(character -> character == '.')) {
            return "_";
        }
        return sanitised;
    }
}
