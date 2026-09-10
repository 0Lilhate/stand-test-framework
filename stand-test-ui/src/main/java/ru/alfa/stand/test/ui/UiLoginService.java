package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.exception.DiagnosticAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Signs a browsing session in, by the scheme the application declares.
 *
 * <p>Two schemes reach a browser, and they are deliberately different things rather than one configurable
 * thing:
 *
 * <ul>
 *   <li>{@link UiAuthScheme#FORM} fills the application's login form every time. It exercises the sign-in
 *   the way a user performs it, and it keeps nothing on disk.</li>
 *   <li>{@link UiAuthScheme#STORAGE_STATE} restores a session saved for this account, checks that it is
 *   still alive, and only falls back to the form when it is not. This is what makes applications behind an
 *   MFA or CAPTCHA screen automatable at all — the session is produced once, by a person or an external
 *   process, and the SDK only uses it (BR-30).</li>
 * </ul>
 *
 * <p>Everything credential-shaped is resolved as late as possible and never stored: the account carries
 * <em>references</em>, the values exist for the duration of one form fill, and every call that touches them
 * runs through {@link UiSecrets} so that a driver echoing what it was asked to type cannot publish it into
 * a report. The locators of the login and password fields are marked sensitive, so an assertion or a
 * diagnostic about those elements masks their content too.
 *
 * <p>Failure classification follows the module's contract. Credentials the application rejected are an
 * assertion about the screen and fail the test; a browser that would not start, a session file that cannot
 * be written, an unresolvable reference or a declared challenge nobody can answer are infrastructure and
 * are recorded as broken.
 */
final class UiLoginService {

    private static final Logger LOG = LoggerFactory.getLogger(UiLoginService.class);

    /** Interval between probes while waiting for the signed-in marker. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(UiStepParameters.DEFAULT_POLL_INTERVAL_MILLIS);

    private final Awaiter awaiter;

    private final UnaryOperator<String> referenceLookup;

    private final Supplier<List<UiLoginChallengeHandler>> challengeHandlers;

    UiLoginService(Awaiter awaiter, UnaryOperator<String> referenceLookup, Supplier<List<UiLoginChallengeHandler>> challengeHandlers) {
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
        this.referenceLookup = Objects.requireNonNull(referenceLookup, "referenceLookup must not be null");
        this.challengeHandlers = Objects.requireNonNull(challengeHandlers, "challengeHandlers must not be null");
    }

    /**
     * Signs the session in as the leased account.
     *
     * @param session the browsing session (already opened, and already restored from a saved state when
     *     the scheme asked for one)
     * @param account the leased account, carrying credential references
     * @param settings the run settings
     * @param timeout the bound on the sign-in itself
     * @return what was done, for the step diagnostics
     */
    UiLoginOutcome signIn(UiSession session, LeasedAccount account, UiRunSettings settings, Duration timeout, StorageStateStore store,
            Path stateFile) {
        UiAuthConfig auth = session.application().auth();
        String alias = session.application().alias();
        UiLoginFormConfig form = auth.login();
        UiLocator signedIn = UiLocatorExpressions.parse(form.signedInLocator(), "signed-in-locator", alias);
        // Reuse is decided per ACCOUNT, not per session: a live session proves only that SOMEBODY is signed
        // in. Asking merely "was a state restored, and is it alive?" would let a second sign-in in the same
        // run inherit the first account's session and report it under the second account's name — a report
        // that names the wrong user is worse than a slow one.
        boolean restoredForThisAccount = stateFile.equals(session.restoredFrom());

        if (restoredForThisAccount && restoredSessionIsAlive(session, signedIn, settings, timeout)) {
            LOG.debug("Reused the saved browser session of account '{}' for application '{}'", account.accountId(), alias);
            session.signedInAs(account.accountId());
            return new UiLoginOutcome(account.accountId(), account.role(), true, stateFile);
        }
        if (restoredForThisAccount) {
            // A well-formed but dead session: the file is discarded rather than left to make every later run
            // pay for the same doomed attempt.
            LOG.info("The saved browser session of account '{}' for application '{}' is no longer valid — "
                    + "discarding it and signing in again",
                    account.accountId(), alias);
            store.delete(session.restoredFrom());
        }
        signInByForm(session, auth, form, account, settings, timeout, signedIn, stateFile);
        session.signedInAs(account.accountId());
        if (auth.scheme() != UiAuthScheme.STORAGE_STATE) {
            return new UiLoginOutcome(account.accountId(), account.role(), false, null);
        }
        store.prepareFor(stateFile);
        UiDriverCalls.run(() -> session.driver().saveStorageState(stateFile), "save the browser session of account '" + account.accountId()
                + "'");
        LOG.debug("Saved the browser session of account '{}' for application '{}'", account.accountId(), alias);
        return new UiLoginOutcome(account.accountId(), account.role(), false, stateFile);
    }

    /**
     * Whether the restored session is still signed in.
     *
     * <p>Bounded by the <em>action</em> timeout rather than the step timeout, and deliberately so: an
     * expired session must be discovered quickly so the form fallback still fits in the step's budget.
     * Spending the whole budget proving a session is dead would leave nothing to sign in with.
     */
    private boolean restoredSessionIsAlive(UiSession session, UiLocator signedIn, UiRunSettings settings, Duration timeout) {
        Duration probeWindow = (timeout.compareTo(settings.actionTimeout()) < 0) ? timeout : settings.actionTimeout();
        UiDriverCalls.run(() -> session.driver().navigate("/", settings.navigationTimeout()),
                "open the application root to check the restored session");
        return awaitSignedIn(session, signedIn, probeWindow, "ui.login session check").satisfied();
    }

    private void signInByForm(
            UiSession session,
            UiAuthConfig auth,
            UiLoginFormConfig form,
            LeasedAccount account,
            UiRunSettings settings,
            Duration timeout,
            UiLocator signedIn,
            Path stateFile) {
        String alias = session.application().alias();
        requireFillableForm(session, auth, form, account, stateFile);
        // Checked before a credential is even resolved: refusing early keeps a secret out of a run that was
        // never going to succeed.
        UiLoginChallengeHandler handler = challengeHandler(auth.challenge(), alias);
        long startedAt = System.nanoTime();
        if (form.path() != null) {
            UiDriverCalls.run(() -> session.driver().navigate(form.path(), settings.navigationTimeout()),
                    "open the sign-in page of application '" + alias + "'");
        }
        fillCredentials(session, form, account, settings, alias);
        UiDriverCalls.run(
                () -> session.driver()
                        .click(UiLocatorExpressions.parse(form.submitLocator(), "submit-locator", alias), settings.actionTimeout()),
                "submit the sign-in form of application '" + alias + "'");
        if (handler != null) {
            // "What is LEFT of the budget", as the request's contract says — not the whole of it a second
            // time. A handler given the full timeout after the form has already been filled would let one
            // step run for twice what its author declared.
            handler.resolve(new UiLoginChallengeRequest(auth.challenge(), alias, account.role(), account.accountId(), session.driver(),
                    remaining(timeout, startedAt)));
        }
        AwaitResult<Boolean> result = awaitSignedIn(session, signedIn, timeout, "ui.login " + alias);
        if (!result.satisfied()) {
            // An application that did not sign the account in is a statement about the product — most often
            // "these credentials were rejected" — so it fails the test rather than breaking the run. The
            // message names the account and never the credentials.
            // The account id and role reach the report as rows; the credential variable NAMES stay in the
            // message only, where they already are. Neither is a value, and neither ever will be.
            throw new DiagnosticAssertionError("Signing in to UI application '" + alias + "' as account '" + account.accountId()
                    + "' (role '" + account.role()
                    + "') did not complete within " + timeout + ": " + signedIn.describe()
                    + " never appeared. The credentials from " + account.usernameRef() + " / " + account.passwordRef()
                    + " were rejected, or the sign-in needs a step this configuration does not describe.",
                    result.timeoutDiagnostics()
                            .withAttribute("ui.application", alias)
                            .withAttribute("ui.login.accountId", account.accountId())
                            .withAttribute("ui.login.role", account.role())
                            .toMap());
        }
    }

    /**
     * Resolves the credentials and types them, with every call that sees a secret wrapped so that a driver
     * echoing the typed value cannot publish it. The field locators are marked sensitive, so anything the
     * evaluator later says about those elements is masked as well.
     */
    private void fillCredentials(UiSession session, UiLoginFormConfig form, LeasedAccount account, UiRunSettings settings, String alias) {
        UiCredentials credentials = resolve(account, alias);
        List<String> secrets = credentials.values();
        UiLocator username = UiLocatorExpressions.parse(form.usernameLocator(), "username-locator", alias).asSensitive();
        UiLocator password = UiLocatorExpressions.parse(form.passwordLocator(), "password-locator", alias).asSensitive();
        LOG.debug("Signing in to application '{}' as account '{}' (role '{}')", alias, account.accountId(), account.role());
        // The recorder is stopped for exactly as long as the credentials are being typed (SEC-05). A trace
        // records the PARAMETERS of the actions it sees, and a fill's parameter is the value — so a run with
        // `trace: on-failure` would otherwise seal the technical account's password into a ZIP that is
        // attached to a report, and the file channel cannot mask bytes the way the text channel masks
        // strings. Neither asSensitive() nor UiSecrets reaches that: the first paints over a screenshot, the
        // second sanitises an exception message. Restored in a finally, so a rejected credential — the very
        // case that ends in a failure artefact — does not leave the run permanently unrecorded.
        session.driver().suspendTracing();
        try {
            UiSecrets.guard(
                    () -> UiDriverCalls.run(() -> session.driver().fill(username, credentials.username(), settings.actionTimeout()),
                            "fill the login field of application '" + alias + "'"),
                    secrets,
                    "fill the login field of application '" + alias + "'");
            UiSecrets.guard(
                    () -> UiDriverCalls.run(() -> session.driver().fill(password, credentials.password(), settings.actionTimeout()),
                            "fill the password field of application '" + alias + "'"),
                    secrets,
                    "fill the password field of application '" + alias + "'");
        } finally {
            session.driver().resumeTracing();
        }
    }

    /**
     * What is left of a budget, never less than one poll interval: a handler or a wait handed a zero or
     * negative duration would fail on arithmetic rather than on the thing it was waiting for.
     */
    private static Duration remaining(Duration budget, long startedAt) {
        Duration left = budget.minusNanos(System.nanoTime() - startedAt);
        return (left.compareTo(POLL_INTERVAL) < 0) ? POLL_INTERVAL : left;
    }

    private AwaitResult<Boolean> awaitSignedIn(UiSession session, UiLocator signedIn, Duration timeout, String description) {
        Duration pollInterval = (timeout.compareTo(POLL_INTERVAL) < 0) ? timeout : POLL_INTERVAL;
        AwaitPolicy policy = AwaitPolicy.builder(description + " " + signedIn.describe())
                .timeout(timeout)
                .pollInterval(pollInterval)
                // A driver error while probing is infrastructure, not a not-yet-signed-in screen: it aborts
                // the wait instead of being retried until the timeout hides it.
                .ignoreExceptions(false)
                .build();
        return this.awaiter.await(
                policy,
                () -> {
                    ElementSnapshot snapshot = UiDriverCalls.call(() -> session.driver().snapshot(signedIn, Set.of(), pollInterval),
                            "observe " + signedIn.describe());
                    return snapshot != null && snapshot.present() && snapshot.visible();
                },
                Boolean.TRUE::equals);
    }

    /**
     * Resolves one credential reference. A reference that does not resolve is a configuration problem, not a
     * product one, and the message names the variable — never a value, and never a hint of one.
     */
    private UiCredentials resolve(LeasedAccount account, String alias) {
        return new UiCredentials(
                required(account.usernameRef(), "login", account, alias),
                required(account.passwordRef(), "password", account, alias));
    }

    private String required(String reference, String what, LeasedAccount account, String alias) {
        String value = SecretReferences.resolve(reference, this.referenceLookup);
        if (value == null || value.isEmpty()) {
            throw new StandTestException("The " + what + " of test account '" + account.accountId() + "' of UI application '" + alias
                    + "' did not resolve: variable '" + reference + "' is not set."
                    + " Account credentials live in environment variables and never in configuration, so this variable must be "
                    + "present wherever the tests run.");
        }
        return value;
    }

    private void requireFillableForm(UiSession session, UiAuthConfig auth, UiLoginFormConfig form, LeasedAccount account, Path stateFile) {
        if (form.fillable()) {
            return;
        }
        String alias = session.application().alias();
        throw new StandTestException("UI application '" + alias + "' signs in with scheme " + auth.scheme()
                + " and has no usable saved session for account '" + account.accountId()
                        + "', but it declares no login form to fall back to."
                + " Prepare the session once outside the SDK and save it as " + stateFile
                + ", or add login.username-locator / password-locator / submit-locator so the SDK can sign in itself.");
    }

    /**
     * Finds the handler for a declared sign-in challenge, or refuses.
     *
     * <p>The SDK ships no MFA / OTP / CAPTCHA bypass — that is external gate G-1, an organisational
     * decision about the second factor rather than a library feature. What it does is refuse honestly and
     * early, naming both the extension point and the alternative, instead of filling a form and then timing
     * out on a screen it was never going to pass.
     *
     * @return the handler, or null when the application declares no challenge
     */
    private UiLoginChallengeHandler challengeHandler(UiLoginChallenge challenge, String alias) {
        if (challenge == UiLoginChallenge.NONE) {
            return null;
        }
        for (UiLoginChallengeHandler handler : this.challengeHandlers.get()) {
            if (handler.supports(challenge)) {
                LOG.debug("Resolving the {} challenge of application '{}' with {}", challenge, alias, handler.getClass().getName());
                return handler;
            }
        }
        throw new StandTestException("UI application '" + alias + "' declares the sign-in challenge " + challenge
                + ", and no " + UiLoginChallengeHandler.class.getSimpleName() + " on the test classpath supports it."
                + " The SDK deliberately ships no MFA/OTP/CAPTCHA bypass: whether and how a second factor may be relaxed for test accounts "
                + "is decided outside it (gate G-1)."
                + " Either register a handler in META-INF/services/" + UiLoginChallengeHandler.class.getName()
                + ", or declare auth.scheme: storage-state for this application and prepare the session once outside the SDK.");
    }
}
