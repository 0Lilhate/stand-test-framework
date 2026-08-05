package ru.alfa.stand.test.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
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
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.ui.playwright.PlaywrightDriverFactory;

/**
 * UI {@link StepExecutor}: the single point of real browser IO to a stand.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} (registered in {@code META-INF/services}); the class
 * is public with a public no-arg constructor for that reason alone and is <strong>not</strong> part of
 * the module's supported surface — write scenarios against {@link UiStep}, not against this class.
 *
 * <p>It holds no per-run state. The browsing session is created lazily on the first UI step that really
 * runs and is kept in the run's resource scope, so a scenario rejected by the validator, or one that
 * fails before its first UI step, never pays for a browser, and two parallel runs never share one.
 *
 * <p>Failure semantics: an unmet expectation about the screen is a {@link StandTestAssertionError}; a
 * browser that would not start, a page that would not load, an unknown alias or any other driver error
 * is a {@link StandTestException}, which the runner records as broken rather than failed.
 */
public final class UiStepExecutor implements StepExecutor {

    /**
     * The header the SDK-owned correlation id is injected under. Wave 1 has no per-application
     * correlation configuration in the registry (unlike a REST service endpoint), so the SDK's
     * conventional header name is used; when the registry grows the field, this constant gives way to it.
     *
     * <p>Not public: this class is outside the supported surface, so a public constant on it would be a
     * contract nobody meant to give. The header name is documented in the module README.
     */
    static final String CORRELATION_HEADER = "X-Correlation-Id";

    private static final Logger LOG = LoggerFactory.getLogger(UiStepExecutor.class);

    private final UiDriverFactory driverFactory;

    private final UiApplicationResolver applicationResolver;

    private final Awaiter awaiter;

    private final Supplier<UiRunSettings> settings;

    private final UnaryOperator<String> referenceLookup;

    private final UiLoginService loginService;

    private final UiAccountPools accountPools;

    /**
     * Creates the executor the {@code ServiceLoader} instantiates: a Playwright-backed driver, the
     * registry-backed application resolver, and settings read from system properties.
     */
    public UiStepExecutor() {
        this(new PlaywrightDriverFactory(), new EnvironmentUiApplicationResolver(), Awaiter.create(), UiRunSettings::fromSystemProperties);
    }

    /**
     * Creates the executor with explicit collaborators — the seam that lets the whole step model be
     * tested without a browser.
     *
     * @param driverFactory opens the browsing session
     * @param applicationResolver resolves an application alias against the environment registry
     */
    public UiStepExecutor(UiDriverFactory driverFactory, UiApplicationResolver applicationResolver) {
        this(driverFactory, applicationResolver, Awaiter.create(), UiRunSettings::fromSystemProperties);
    }

    /**
     * Creates the executor with explicit collaborators, including the await engine and the run settings.
     *
     * @param driverFactory opens the browsing session
     * @param applicationResolver resolves an application alias against the environment registry
     * @param awaiter drives {@code ui.expectEventually} polling
     * @param settings supplies the run settings when a session is opened
     */
    public UiStepExecutor(UiDriverFactory driverFactory, UiApplicationResolver applicationResolver, Awaiter awaiter, Supplier<UiRunSettings> settings) {
        this(driverFactory, applicationResolver, awaiter, settings, System::getenv);
    }

    /**
     * Creates the executor with explicit collaborators, including the lookup that turns a credential
     * reference into its value — the last seam the sign-in machinery needs in order to be tested without
     * either a browser or real environment variables.
     *
     * @param driverFactory opens the browsing session
     * @param applicationResolver resolves an application alias against the environment registry
     * @param awaiter drives {@code ui.expectEventually} polling and the sign-in wait
     * @param settings supplies the run settings when a session is opened
     * @param referenceLookup resolves a reference name (an environment variable) to its value, or null
     */
    public UiStepExecutor(
            UiDriverFactory driverFactory,
            UiApplicationResolver applicationResolver,
            Awaiter awaiter,
            Supplier<UiRunSettings> settings,
            UnaryOperator<String> referenceLookup) {
        this(driverFactory, applicationResolver, awaiter, settings, referenceLookup, UiAccountPools.shared());
    }

    /**
     * Creates the executor with an explicit account-pool registry. Package-private: the JVM-wide registry is
     * what makes leasing exclusive, so production has exactly one, and only this module's own tests — which
     * must not interfere with each other while proving that parallel runs do not — supply another.
     */
    UiStepExecutor(
            UiDriverFactory driverFactory,
            UiApplicationResolver applicationResolver,
            Awaiter awaiter,
            Supplier<UiRunSettings> settings,
            UnaryOperator<String> referenceLookup,
            UiAccountPools accountPools) {
        this.accountPools = Objects.requireNonNull(accountPools, "accountPools must not be null");
        this.driverFactory = Objects.requireNonNull(driverFactory, "driverFactory must not be null");
        this.applicationResolver = Objects.requireNonNull(applicationResolver, "applicationResolver must not be null");
        this.awaiter = Objects.requireNonNull(awaiter, "awaiter must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.referenceLookup = Objects.requireNonNull(referenceLookup, "referenceLookup must not be null");
        this.loginService = new UiLoginService(this.awaiter, this.referenceLookup, UiStepExecutor::discoverChallengeHandlers);
    }

    @Override
    public boolean supports(String stepType) {
        return stepType != null && stepType.startsWith(UiStepParameters.TYPE_PREFIX);
    }

    /**
     * Deliberately a no-op: a browser is expensive and a scenario may never reach its UI step. The
     * session opens on first real use instead.
     */
    @Override
    public void prepare(ScenarioStep step, StepExecutionContext context) {
        // Intentionally empty — see the Javadoc above.
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Objects.requireNonNull(step, "step must not be null");
        Objects.requireNonNull(context, "context must not be null");
        final Instant startedAt = Instant.now();
        Map<String, Object> parameters = parameters(step);
        String application = UiStepParameters.requireString(parameters, UiStepParameters.APPLICATION);
        UiRunSettings runSettings = this.settings.get();
        if (UiStepParameters.LOGIN_TYPE.equals(step.type())) {
            // Sign-in opens the session itself: a saved session can only be restored while the browsing
            // context is created, so it cannot ride on the lazy opening the other step types share.
            return login(step, parameters, application, runSettings, context, startedAt);
        }
        UiSession session = session(application, runSettings, context);
        injectCorrelationId(parameters, session, context);
        switch (step.type()) {
            case UiStepParameters.OPEN_TYPE -> open(parameters, session, runSettings);
            case UiStepParameters.CLICK_TYPE -> click(parameters, session, runSettings);
            case UiStepParameters.FILL_TYPE -> fill(parameters, session, runSettings, context);
            case UiStepParameters.EXPECT_TYPE -> expect(parameters, session, runSettings, context);
            case UiStepParameters.EXPECT_EVENTUALLY_TYPE -> expectEventually(parameters, session, runSettings, context);
            default -> throw new StandTestException("Unsupported UI step type: '" + step.type() + "'");
        }
        return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, diagnostics(parameters, session));
    }

    private void open(Map<String, Object> parameters, UiSession session, UiRunSettings settings) {
        String path = UiStepParameters.requireString(parameters, UiStepParameters.PATH);
        LOG.debug("UI open {}{}", session.application().alias(), path);
        driverCall(() -> session.driver().navigate(path, settings.navigationTimeout()), "navigate to '" + path + "'");
    }

    private void click(Map<String, Object> parameters, UiSession session, UiRunSettings settings) {
        UiLocator locator = UiStepParameters.locator(parameters);
        LOG.debug("UI click {}", locator.describe());
        driverCall(() -> session.driver().click(locator, settings.actionTimeout()), "click " + locator.describe());
    }

    /**
     * Types a value into an element. When the locator is marked {@link UiLocator#sensitive()} the call goes
     * through the same leak guard the sign-in uses: {@code asSensitive()} is the author's declaration that
     * this element holds a secret, and a driver that echoes what it was asked to type would otherwise
     * publish it through an exception message — the very threat the mark exists to name.
     */
    private void fill(Map<String, Object> parameters, UiSession session, UiRunSettings settings, StepExecutionContext context) {
        UiLocator locator = UiStepParameters.locator(parameters);
        String value = context.resolver().resolve(UiStepParameters.requireString(parameters, UiStepParameters.VALUE));
        LOG.debug("UI fill {}", locator.describe());
        Runnable typing = () -> driverCall(() -> session.driver().fill(locator, value, settings.actionTimeout()), "fill " + locator.describe());
        if (locator.sensitive()) {
            UiSecrets.guard(typing, List.of(value), "fill " + locator.describe());
        } else {
            typing.run();
        }
    }

    private void expect(Map<String, Object> parameters, UiSession session, UiRunSettings settings, StepExecutionContext context) {
        UiLocator locator = UiStepParameters.locator(parameters);
        List<UiAssertion> assertions = UiStepParameters.assertions(parameters);
        List<UiCapture> captures = UiStepParameters.captures(parameters);
        ElementSnapshot snapshot = snapshot(session, locator, assertions, List.of(), settings.actionTimeout());
        String mismatch = UiAssertionEvaluator.firstMismatch(assertions, locator, snapshot);
        if (mismatch != null) {
            throw new StandTestAssertionError(mismatch);
        }
        applyCaptures(captures, session, settings, context);
    }

    private void expectEventually(Map<String, Object> parameters, UiSession session, UiRunSettings settings, StepExecutionContext context) {
        UiLocator locator = UiStepParameters.locator(parameters);
        List<UiAssertion> assertions = UiStepParameters.assertions(parameters);
        List<UiCapture> captures = UiStepParameters.captures(parameters);
        Duration timeout = Duration.ofMillis(UiStepParameters.positiveMillis(parameters, UiStepParameters.TIMEOUT_MILLIS, UiStepParameters.DEFAULT_TIMEOUT_MILLIS));
        Duration pollInterval = Duration.ofMillis(
                UiStepParameters.positiveMillis(parameters, UiStepParameters.POLL_INTERVAL_MILLIS, UiStepParameters.DEFAULT_POLL_INTERVAL_MILLIS));
        // Two nested waits would produce a meaningless time budget, so the split is explicit: the SDK's
        // await engine owns the polling, and a single probe is bounded by roughly one poll interval so it
        // cannot eat the step's budget. Playwright's own auto-waiting is used only for actionability.
        //
        // Clamped to the step's own timeout as defence in depth: the validator refuses an interval larger
        // than the wait, but a GenericStep assembled without a validation pass would otherwise make this
        // single probe outlive the step it belongs to — the opposite of what bounding it was for.
        Duration probeTimeout = (pollInterval.compareTo(timeout) > 0) ? timeout : pollInterval;
        AwaitPolicy policy = AwaitPolicy.builder("ui.expectEventually " + session.application().alias() + " " + locator.describe())
                .timeout(timeout)
                .pollInterval(pollInterval)
                // A driver error during polling is infrastructure, not a not-yet-satisfied expectation:
                // it aborts the wait instead of being retried until the timeout hides it.
                .ignoreExceptions(false)
                .build();
        AwaitResult<String> result = this.awaiter.await(
                policy,
                () -> UiAssertionEvaluator.firstMismatch(assertions, locator, snapshot(session, locator, assertions, List.of(), probeTimeout)),
                mismatch -> mismatch == null);
        result.orElseThrow(diagnostics -> new StandTestAssertionError(
                "ui.expectEventually on " + locator.describe() + " did not hold: " + diagnostics.summary()
                        + " (application=" + session.application().alias() + ")"));
        applyCaptures(captures, session, settings, context);
    }

    private ElementSnapshot snapshot(UiSession session, UiLocator locator, List<UiAssertion> assertions, List<UiCapture> captures, Duration probeTimeout) {
        Set<String> attributes = UiAssertionEvaluator.attributeNames(assertions, captures);
        // Observing goes through the same classifier as acting: a driver that reports "the element never
        // became actionable" from a probe must be read as a failed expectation, and any other driver error
        // must carry the adapter's own message rather than the runner's generic "failed unexpectedly".
        ElementSnapshot snapshot = driverCall(() -> session.driver().snapshot(locator, attributes, probeTimeout), "observe " + locator.describe());
        if (snapshot == null) {
            throw new StandTestException("The UI driver returned no snapshot for " + locator.describe() + " — a driver must report absence as ElementSnapshot.absent()");
        }
        return snapshot;
    }

    private void applyCaptures(List<UiCapture> captures, UiSession session, UiRunSettings settings, StepExecutionContext context) {
        for (UiCapture capture : captures) {
            ElementSnapshot snapshot = snapshot(session, capture.locator(), List.of(), List.of(capture), settings.actionTimeout());
            if (!snapshot.present()) {
                throw new StandTestAssertionError("Cannot capture '" + capture.variableName() + "': element " + capture.locator().describe() + " was not found on the page");
            }
            String value = switch (capture.source()) {
                case TEXT -> snapshot.text();
                case VALUE -> snapshot.value();
                case ATTRIBUTE -> snapshot.attribute(capture.attribute());
            };
            if (value == null) {
                throw new StandTestAssertionError("Cannot capture '" + capture.variableName() + "': " + capture.source() + " of element "
                        + capture.locator().describe() + " is null");
            }
            context.variableStore().put(capture.variableName(), value);
        }
    }

    /**
     * Executes {@code ui.login}: lease an account of the requested role, open (or reuse) the browsing
     * session, and sign in by the scheme the registry declares.
     *
     * <p>The order is deliberate, and it is a compromise between two things that pull apart. The account is
     * leased <em>before</em> the browser starts, because the account decides which saved session may be
     * restored, and a session can only be restored while the browsing context is created — leasing first
     * also means an exhausted pool costs no browser at all. But the lease is <em>registered</em> in the
     * resource scope <em>after</em> the session, so that the scope's insertion-ordered close tears the
     * browser down before the account returns to the pool. Between the two points the lease is owned by this
     * method, and the {@code finally} returns it if anything in between throws.
     */
    private StepResult login(
            ScenarioStep step,
            Map<String, Object> parameters,
            String application,
            UiRunSettings runSettings,
            StepExecutionContext context,
            Instant startedAt) {
        String role = UiStepParameters.optionalString(parameters, UiStepParameters.ROLE);
        Duration timeout = Duration.ofMillis(UiStepParameters.positiveMillis(parameters, UiStepParameters.TIMEOUT_MILLIS, UiStepParameters.DEFAULT_TIMEOUT_MILLIS));
        Duration accountTimeout = Duration.ofMillis(
                UiStepParameters.positiveMillis(parameters, UiStepParameters.ACCOUNT_TIMEOUT_MILLIS, UiStepParameters.DEFAULT_ACCOUNT_TIMEOUT_MILLIS));

        String environment = context.scenarioContext().environment();
        UiSession opened = context.resourceScope().get(UiSession.resourceKey(application)).map(UiSession.class::cast).orElse(null);
        ResolvedUiApplication resolved = (opened != null) ? opened.application() : this.applicationResolver.resolve(application, context);
        UiAuthConfig auth = requireSignInConfigured(resolved);
        String leaseKey = leaseKey(environment, application, role);
        LeasedAccount account = leaseAccount(environment, resolved, auth, role, accountTimeout, context);
        boolean ownedByScope = context.resourceScope().contains(leaseKey);
        try {
            StorageStateStore store = new StorageStateStore(runSettings.artifactsDirectory());
            Path stateFile = store.pathFor(environment, application, account.accountId());
            UiSession session = (opened != null)
                    ? requireExistingSessionUsableBy(opened, auth, account, store, stateFile)
                    : openSessionDiscardingUnusableState(resolved, runSettings, context, store, restorableState(auth, store, stateFile));
            if (!ownedByScope) {
                context.resourceScope().register(leaseKey, account);
                ownedByScope = true;
            }
            injectCorrelationId(parameters, session, context);
            UiLoginOutcome outcome = this.loginService.signIn(session, account, runSettings, timeout, store, stateFile);
            return new StepResult(step.id(), step.type(), StepStatus.SUCCESS, startedAt, Instant.now(), null, loginDiagnostics(session, auth, account, outcome));
        } finally {
            if (!ownedByScope) {
                // The browser never opened, or the session refused this account: nobody else will ever close
                // this lease, so an account held by a run that never used it would be lost for the JVM's life.
                account.close();
            }
        }
    }

    /**
     * Decides whether a sign-in that arrives after the application's session is already open may use it.
     *
     * <p>Two things can go wrong, and both are refused rather than papered over. A saved session can only be
     * restored while the browsing context is created, so a late {@code ui.login} would silently sign in the
     * slow way — a step that quietly becomes ten seconds slower is a defect nobody notices for months. And a
     * second account signing in to a context that still carries the first account's cookies would be saved
     * as the second account's session, producing a state file holding two identities.
     *
     * <p>When neither applies — no saved session to lose, same account, or a scheme that keeps nothing on
     * disk — the existing session is used as it is, which is what an ordinary re-authentication needs.
     */
    private static UiSession requireExistingSessionUsableBy(UiSession opened, UiAuthConfig auth, LeasedAccount account, StorageStateStore store, Path stateFile) {
        if (auth.scheme() != UiAuthScheme.STORAGE_STATE) {
            return opened;
        }
        String alias = opened.application().alias();
        String signedIn = opened.signedInAccountId();
        if (signedIn != null && !signedIn.equals(account.accountId())) {
            throw new StandTestException("UI application '" + alias + "' signs in with scheme STORAGE_STATE, and this run is already signed in as account '" + signedIn
                    + "'. Signing in as '" + account.accountId() + "' in the same browsing context would save a session state carrying both identities."
                    + " Use one scenario per account, or declare auth.scheme FORM for an application whose test really is 'sign in as somebody else'.");
        }
        if (store.usable(stateFile) && !stateFile.equals(opened.restoredFrom())) {
            throw new StandTestException("UI application '" + alias + "' signs in with scheme STORAGE_STATE and account '" + account.accountId()
                    + "' has a saved session, but a browsing session for this application was already opened by an earlier step,"
                    + " and a saved session can only be restored while the browser context is created. Move the ui.login step before the first ui.* step of this application.");
        }
        return opened;
    }

    private static Path restorableState(UiAuthConfig auth, StorageStateStore store, Path stateFile) {
        return (auth.scheme() == UiAuthScheme.STORAGE_STATE && store.usable(stateFile)) ? stateFile : null;
    }

    /**
     * Opens the browsing session, treating a saved state the browser refuses as the cache miss it is.
     *
     * <p>{@link StorageStateStore#usable(Path)} can only tell that a file <em>looks</em> like a session: the
     * module holds no JSON parser of its own, and the one library that could parse it is confined to the
     * driver package by design. A file well-formed enough to pass that check and still rejected when the
     * context is created — a half-written save, a state written by an incompatible version — would otherwise
     * break this account's every future run in exactly the same way, because nothing on the failure path
     * removes it. One account would be permanently unusable until somebody deleted a file by hand.
     *
     * <p>So the state is discarded and the session opened once more without it. That is the contract
     * {@link StorageStateStore} already documents for every other kind of unusable file — a corrupted cache
     * costs one sign-in, never a run — and this is the branch where it was not yet honoured. If the second
     * attempt fails too the browser itself is the problem: that failure is the one raised, carrying the first
     * as suppressed so the discarded state still appears in the diagnostics.
     */
    private UiSession openSessionDiscardingUnusableState(
            ResolvedUiApplication resolved,
            UiRunSettings runSettings,
            StepExecutionContext context,
            StorageStateStore store,
            Path restorable) {
        if (restorable == null) {
            return openSession(resolved, runSettings, context, null);
        }
        try {
            return openSession(resolved, runSettings, context, restorable);
        } catch (StandTestException refused) {
            LOG.info("The browser refused the saved session {} — discarding it and signing in again: {}", restorable, refused.getMessage());
            store.delete(restorable);
            try {
                return openSession(resolved, runSettings, context, null);
            } catch (StandTestException stillBroken) {
                stillBroken.addSuppressed(refused);
                throw stillBroken;
            }
        }
    }

    private static String leaseKey(String environment, String application, String role) {
        return "ui.account:" + environment + ":" + application + ":" + ((role == null) ? "*" : role);
    }

    /**
     * Leases a test account, registering the lease in the run's resource scope so the runner returns it in
     * its {@code finally} — on a green run, on a failed one and on one that threw. There is no second
     * release path to forget, which is the same reason the browser is closed that way.
     *
     * <p>A run that signs in twice as the same role keeps the account it already holds: the scope is keyed
     * by application and role, so a second {@code ui.login} does not queue behind the pool for an account
     * this very run is holding.
     */
    private LeasedAccount leaseAccount(String environment, ResolvedUiApplication application, UiAuthConfig auth, String role, Duration accountTimeout, StepExecutionContext context) {
        LeasedAccount held = context.resourceScope().get(leaseKey(environment, application.alias(), role)).map(LeasedAccount.class::cast).orElse(null);
        if (held != null) {
            return held;
        }
        String poolRef = auth.credentialsPoolRef();
        String roster = SecretReferences.resolve(poolRef, this.referenceLookup);
        List<UiAccount> accounts = AccountRoster.parse(roster, application.alias(), poolRef, auth.discoveryAccountRef());
        AccountPool pool = this.accountPools.forApplication(environment, application.alias(), accounts);
        return pool.lease(application.alias(), role, accountTimeout);
    }

    /**
     * Refuses a sign-in the registry cannot describe, before anything is leased or started.
     *
     * <p>{@code SSO} is present in the configuration vocabulary and not implemented here: an identity
     * provider's redirect flow depends on a configuration the SDK does not have, and it sits behind the
     * same external gate as the MFA question. Saying so is better than not having the value at all — a
     * registry can be written ahead of the SDK, and the refusal names the version rather than the spelling.
     */
    private static UiAuthConfig requireSignInConfigured(ResolvedUiApplication application) {
        UiAuthConfig auth = application.auth();
        if (auth == null || auth.scheme() == UiAuthScheme.NONE) {
            throw new StandTestException("UI application '" + application.alias() + "' declares no sign-in (auth is absent or its scheme is NONE),"
                    + " so there is nothing for ui.login to do — remove the step, or declare an auth section with scheme FORM or STORAGE_STATE.");
        }
        if (auth.scheme() == UiAuthScheme.SSO) {
            throw new StandTestException("UI application '" + application.alias() + "' declares auth.scheme SSO, which this SDK version does not implement:"
                    + " an identity-provider redirect flow is configured per application and sits behind the same external gate as the MFA question (G-1)."
                    + " Use scheme FORM, or STORAGE_STATE with a session prepared once outside the SDK.");
        }
        return auth;
    }

    private static Map<String, Object> loginDiagnostics(UiSession session, UiAuthConfig auth, LeasedAccount account, UiLoginOutcome outcome) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("ui.application", session.application().alias());
        String url = currentUrl(session);
        if (url != null) {
            // The query string is dropped here and nowhere else in the adapter. This is the one step whose
            // URL is read moments after a login form was submitted, and a form served with method="GET", or
            // a post-sign-in redirect carrying a one-time token, puts credential-equivalent material in it.
            // Which page the run landed on is the diagnostic value; the parameters are not.
            diagnostics.put("ui.url", withoutQuery(url));
        }
        diagnostics.put("ui.login.scheme", auth.scheme().name());
        diagnostics.put("ui.login.role", outcome.role());
        diagnostics.put("ui.login.account", outcome.accountId());
        diagnostics.put("ui.login.sessionReused", outcome.sessionReused());
        diagnostics.put("ui.account.waitMillis", account.waited().toMillis());
        if (outcome.storageState() != null) {
            // The path, never the file: it holds live cookies and is never attached to a report.
            diagnostics.put("ui.login.storageState", outcome.storageState().toString());
        }
        return diagnostics;
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        int cut = (query < 0) ? fragment : ((fragment < 0) ? query : Math.min(query, fragment));
        return (cut < 0) ? url : url.substring(0, cut);
    }

    private static List<UiLoginChallengeHandler> discoverChallengeHandlers() {
        List<UiLoginChallengeHandler> handlers = new ArrayList<>();
        ServiceLoader.load(UiLoginChallengeHandler.class).forEach(handlers::add);
        return handlers;
    }

    private UiSession session(String application, UiRunSettings runSettings, StepExecutionContext context) {
        String key = UiSession.resourceKey(application);
        return context.resourceScope().get(key)
                .map(UiSession.class::cast)
                .orElseGet(() -> openSession(this.applicationResolver.resolve(application, context), runSettings, context, null));
    }

    private UiSession openSession(ResolvedUiApplication resolved, UiRunSettings runSettings, StepExecutionContext context, Path storageState) {
        String application = resolved.alias();
        LOG.debug("Opening UI session for application '{}' ({}, headless={}, restoredSession={})", application, runSettings.browser(), runSettings.headless(), storageState != null);
        UiDriver driver = driverCall(() -> this.driverFactory.open(resolved, runSettings, storageState), "open a browsing session for application '" + application + "'");
        if (driver == null) {
            throw new StandTestException("The UI driver factory returned no driver for application '" + application + "'");
        }
        UiSession session = new UiSession(resolved, driver, storageState);
        context.resourceScope().register(UiSession.resourceKey(application), session);
        return session;
    }

    /**
     * Applies the step's correlation-id opt-in.
     *
     * <p>Applied on every step that asks, not only on the one that happened to open the session: the
     * session is opened lazily by whichever UI step runs first, so binding the opt-in to session creation
     * would make {@code injectCorrelationId()} on any later step a silent no-op — a knob that reads as set
     * and does nothing. Setting the header again is harmless; the value is the same for the whole run.
     */
    private static void injectCorrelationId(Map<String, Object> parameters, UiSession session, StepExecutionContext context) {
        if (!UiStepParameters.injectCorrelationIdFlag(parameters).orElse(false)) {
            return;
        }
        driverCall(
                () -> session.driver().setExtraHeader(CORRELATION_HEADER, context.scenarioContext().correlationId().value()),
                "inject the correlation id header");
    }

    private static Map<String, Object> diagnostics(Map<String, Object> parameters, UiSession session) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("ui.application", session.application().alias());
        String url = currentUrl(session);
        if (url != null) {
            diagnostics.put("ui.url", url);
        }
        Object locator = parameters.get(UiStepParameters.LOCATOR);
        if (locator instanceof Map<?, ?>) {
            UiLocator parsed = UiStepParameters.locator(parameters);
            diagnostics.put("ui.locator", parsed.describe());
            diagnostics.put("ui.locator.strategy", parsed.strategy().name());
        }
        return diagnostics;
    }

    private static String currentUrl(UiSession session) {
        try {
            return session.driver().currentUrl();
        } catch (RuntimeException unavailable) {
            // Diagnostics must never be the reason a green step turns red.
            LOG.debug("Could not read the current URL for diagnostics: {}", unavailable.toString());
            return null;
        }
    }

    private static Map<String, Object> parameters(ScenarioStep step) {
        if (step instanceof GenericStep generic) {
            return generic.parameters();
        }
        throw new StandTestException("UiStepExecutor requires a GenericStep produced by UiStep, but got: " + step.getClass().getName());
    }

    private static void driverCall(Runnable call, String what) {
        UiDriverCalls.run(call, what);
    }

    /**
     * The single point where the browser's exceptions are classified, shared with the sign-in step so the
     * two cannot classify the same browser error differently — see {@link UiDriverCalls}.
     */
    private static <T> T driverCall(Supplier<T> call, String what) {
        return UiDriverCalls.call(call, what);
    }
}
