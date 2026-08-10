package ru.alfa.stand.test.ui;

import java.io.IOException;
import java.nio.file.Files;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.event.Attachment;
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
 * is a {@link StandTestException}, which the runner records as broken rather than failed. On a failing
 * step — and only then — the executor captures a screenshot into the run's artefacts directory and
 * carries it ({@link UiAssertionFailure}/{@link UiInfrastructureFailure}) to the reporting step so the
 * report shows what was on the screen when it went red (UITG-S013).
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

    /**
     * The bound on a single failure screenshot. Playwright has no per-call screenshot timeout, so this is
     * the contract's bound while the capture is attempted once, never in a retry loop; a driver that can
     * honour a bound uses it.
     */
    private static final Duration SCREENSHOT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The bound on a single trace capture. Trace export rewinds and zips a whole run — not a frame
     * grab like a screenshot — so it is given a longer budget than the screenshot; a driver keeps the bound
     * in its contract the same way {@code SCREENSHOT_TIMEOUT} is kept, as the upper effort of one attempt.
     */
    private static final Duration TRACE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * The role given to a directly-named account when the application declares no roles at all. A
     * {@link UiAccount} cannot exist without one, and a sign-in that names no role leases whatever the pool
     * holds, so the value is never matched against anything a scenario writes.
     */
    private static final String DIRECT_ACCOUNT_ROLE = "default";

    private static final Logger LOG = LoggerFactory.getLogger(UiStepExecutor.class);

    private final UiDriverFactory driverFactory;

    private final UiApplicationResolver applicationResolver;

    private final Awaiter awaiter;

    private final Supplier<UiRunSettings> settings;

    private final UnaryOperator<String> referenceLookup;

    private final UiLoginService loginService;

    private final UiAccountPools accountPools;

    /**
     * Guard that the run-artefact retention is swept once per executor instead of once per opened session.
     *
     * <p>The executor is a JVM-wide singleton shared by parallel runs, and the artefacts directory is the
     * same for every run in a JVM (it comes from a system property, resolved once). So the correct point of
     * the sweep is "the first run that actually opens a UI session", performed once — a second open must not
     * rescan history a concurrent run may be mid-way into writing (UITG-S018; the sweep is idempotent and
     * skips {@code storage-state}, both of which make even a rare double-trigger harmless). Because the
     * directory is fixed per JVM, keeping the flag global across runs (rather than per-run) is correct and
     * intentional: a later run in the same JVM sweeps nothing new, which is exactly the intent.
     *
     * <p>The one accepted limitation follows from the same fact: the flag is not reset if a run were to
     * reconfigure the artefacts directory mid-JVM (not possible through system properties today), so a
     * directory switched mid-process would not be swept on its first use. Accepted — the configuration
     * surface is a startup-time system property, and a fresh JVM sweeps its own directory.
     */
    private final AtomicBoolean retentionSwept = new AtomicBoolean();

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
        // application is declared here and assigned inside the try so the failure path can still capture a
        // screenshot against it; a failure before it is assigned (a foreign step, a parameter read that
        // throws) has no application and therefore no screenshot to capture.
        String application = null;
        UiRunSettings runSettings = this.settings.get();
        // The failing step's sensitive zones, resolved while the parameters are parsed and passed to the
        // failure path so its screenshot can mask them before the artefact is taken (UITG-S017, SEC-05).
        // Declared before the try and filled inside because parsing parameters may itself throw (a foreign
        // step): a failure before assignment has no application and therefore gets no masking either.
        // Mutable because a ui.login step contributes its form's credential fields (see login()); the outer
        // catch reads whatever was collected by the time the step threw.
        List<UiLocator> sensitiveZones = new ArrayList<>();
        try {
            Map<String, Object> parameters = parameters(step);
            sensitiveZones(parameters, sensitiveZones);
            if (UiStepParameters.LOGIN_TYPE.equals(step.type())) {
                // Sign-in opens the session by itself: a saved session can only be restored while the
                // browsing context is created, so it cannot ride on the route other step types share.
                application = UiStepParameters.requireString(parameters, UiStepParameters.APPLICATION);
                return login(step, parameters, application, runSettings, context, startedAt, sensitiveZones);
            }
            application = UiStepParameters.requireString(parameters, UiStepParameters.APPLICATION);
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
        } catch (StandTestAssertionError assertion) {
            UiEvidence evidence = captureFailure(application, runSettings.artifactsDirectory(), context, sensitiveZones);
            throw new UiAssertionFailure(assertion.getMessage(), assertion, evidence);
        } catch (StandTestException infrastructure) {
            UiEvidence evidence = captureFailure(application, runSettings.artifactsDirectory(), context, sensitiveZones);
            throw new UiInfrastructureFailure(infrastructure.getMessage(), infrastructure, evidence);
        }
    }

    /**
     * Captures a screenshot of the current page into the run's artefacts directory, for attachment to a
     * failing step's report. Called exactly on the failure path and never on a green one.
     *
     * <p>Before the screenshot, every {@link UiLocator#sensitive() sensitive} zone of the failing step is
     * masked through the driver, so a value already rendered in the frame (a typed password) is never
     * exposed by the artefact (UITG-S017, SEC-05). Masking is not best-effort the way the capture is: if
     * it throws, the screenshot is <em>aborted</em> — a missing screenshot is safer than one leaking a
     * secret. The number of zones actually closed is recorded as a diagnostic datum, both in the log and,
     * via the returned evidence, in the failing step's reportable diagnostics.
     *
     * <p>The browser session, if one is open, is looked up by its resource key; a failure before the
     * session was registered (an invalid parameter, a refused alias, a foreign step) is a perfectly
     * reasonable "no screenshot", because there is no browser to capture. Any failure here — the browser
     * already closed, a directory that cannot be created — must never replace the step's own failure
     * (plan §17: reporting is a side-channel), so the capture is best-effort: a thrown capture is logged
     * at WARN and the step proceeds with no evidence.
     *
     * @param application the application alias of the failing step, or null when it was never resolved
     * @param context the run context, which carries the open session
     * @param sensitiveZones the failing step's sensitive locators to mask before the capture
     * @return the evidence gathered on the failure path (screenshot attachment and masked-zone count),
     *     or {@link UiEvidence#EMPTY} when nothing could be captured
     */
    private UiEvidence captureFailure(String application, Path artifactsDirectory, StepExecutionContext context, List<UiLocator> sensitiveZones) {
        if (application == null) {
            return UiEvidence.EMPTY;
        }
        try {
            UiSession session = context.resourceScope().get(UiSession.resourceKey(application)).map(UiSession.class::cast).orElse(null);
            if (session == null) {
                return UiEvidence.EMPTY;
            }
            Path directory = artifactsDirectory;
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }
            int maskedZones = 0;
            if (!sensitiveZones.isEmpty()) {
                try {
                    maskedZones = session.driver().maskSensitive(sensitiveZones, SCREENSHOT_TIMEOUT);
                } catch (RuntimeException maskFailure) {
                    // SEC-05 abort, not a missed capture: a zone the driver could not mask must not be
                    // captured, because a screenshot of a secret is worse than no screenshot at all. The
                    // log names the masking failure, so the distinction is visible to whoever reads a
                    // broken red run without a picture. The element-vanished case is not here — maskSensitive
                    // reports a lower count for it, not a throw (UITG-S017 / MEDIUM-2).
                    LOG.warn("Could not mask the sensitive zones of the failing step for application '{}' — the failure "
                            + "screenshot is aborted rather than risk exposing a secret: {}", application, maskFailure.toString());
                    return UiEvidence.EMPTY;
                }
                // Observability (UITG-S017): how many of the step's sensitive zones were actually stopped
                // before the capture — an element that vanished mid-masking is a datum, not a failure. The
                // count is carried into the failing step's diagnostics (UiEvidence), not only logged.
                LOG.info("Masked {} of {} sensitive zone(s) for application '{}' before capturing the failure screenshot",
                        maskedZones, sensitiveZones.size(), application);
            }
            Path screenshot = session.driver().captureScreenshot(directory, SCREENSHOT_TIMEOUT);
            List<Attachment> attachments = new ArrayList<>();
            if (screenshot != null) {
                attachments.add(Attachment.ofFile("ui-screenshot", "image/png", screenshot));
            }
            // The trace is the second (heavier) failure artefact, recorded only when the application's
            // registry declaration opts in, and sealed strictly after the masking above — a trace must never
            // precede the maskSensitive it would argue with (UITG-S017, SEC-05; UITG-S016). It is best-effort
            // like the screenshot: a missed or absent trace is logged, never allowed to replace the step's
            // own failure.
            if (session.application().trace() == UiTraceMode.ON_FAILURE) {
                try {
                    Path trace = session.driver().captureTrace(directory, TRACE_TIMEOUT);
                    if (trace != null) {
                        attachments.add(Attachment.ofFile("ui-trace", "application/zip", trace));
                    }
                } catch (RuntimeException traceFailure) {
                    LOG.warn("Could not capture the trace for application '{}': {}", application, traceFailure.toString());
                }
            }
            // The console is the third (textual) failure artefact: a frontend error often announces itself
            // in the console before anything visibly breaks, so the report gets what the page logged (UITG-S014).
            // It rides the TEXT channel, which the Allure sink runs through the secret masker (SEC-05) — a
            // secret a page accidentally logged is redacted the same way an exception message is. Best-effort
            // like the trace: a log read that fails must never replace the step's own failure. The negative
            // scenario — an empty console — attaches nothing at all, rather than an empty block (the check is
            // "any non-blank line", not "the list is non-empty").
            try {
                String console = joinedLines(session.driver().consoleMessages());
                if (!console.isBlank()) {
                    attachments.add(Attachment.of("ui-console", "text/plain", console));
                }
            } catch (RuntimeException consoleFailure) {
                LOG.warn("Could not read the browser console for application '{}': {}", application, consoleFailure.toString());
            }
            // The network story is the fourth failure artefact (UITG-S015): whether a request reached the
            // backend, and with what code, is the first thing to answer on a red run. The driver has already
            // reduced each line to method/path/status and masked the credential headers, and — like the
            // console — the block rides the TEXT channel, so whatever still slipped through runs through the
            // secret masker in the sink (SEC-05). Best-effort like the trace: a read that fails must never
            // replace the step's own failure. The negative scenario — no requests observed — attaches nothing,
            // rather than an empty block.
            try {
                String network = joinedLines(session.driver().networkRequests());
                if (!network.isBlank()) {
                    attachments.add(Attachment.of("ui-network", "text/plain", network));
                }
            } catch (RuntimeException networkFailure) {
                LOG.warn("Could not read the page's network requests for application '{}': {}", application, networkFailure.toString());
            }
            return new UiEvidence(attachments, maskedZones);
        } catch (IOException | RuntimeException failure) {
            // A capture that could not complete (directory, the browser gone mid-grab) must not hide the
            // step's own failure. Masking failures are handled earlier and return EMPTY already, so this
            // branch is about the capture itself, not about a secret the mask failed to cover.
            LOG.warn("Could not capture a failure screenshot for '{}': {}", application, failure.toString());
            return UiEvidence.EMPTY;
        }
    }

    /**
     * Renders a driver's observed lines as one text block for the report, blank lines dropped.
     *
     * <p>The shape is deliberately flat — one line per observation, in the order the page produced them — so
     * a reader scans the block as they would the browser's own console (UITG-S014) or its network story
     * (UITG-S015, whose lines are already method/path/status and carry no headers or bodies).
     *
     * @param lines the driver's lines, already newest-last, possibly empty
     * @return the joined block, or an empty string when there is nothing to attach
     */
    private static String joinedLines(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    /**
     * Collects the {@link UiLocator#sensitive() sensitive} locators of the failing step into {@code zones}:
     * the step's own locator when it is marked sensitive, plus any sensitive capture locator. These are the
     * fields the failure path masks before a screenshot. Order is preserved; each distinct zone appears once.
     *
     * @param parameters the step's wire parameters
     * @param zones the mutable collection the step's sensitive zones are appended to
     */
    private static void sensitiveZones(Map<String, Object> parameters, List<UiLocator> zones) {
        Object locator = parameters.get(UiStepParameters.LOCATOR);
        if (locator instanceof Map<?, ?>) {
            UiLocator parsed = UiStepParameters.locator(parameters);
            if (parsed.sensitive()) {
                zones.add(parsed);
            }
        }
        for (UiCapture capture : UiStepParameters.captures(parameters)) {
            UiLocator loc = capture.locator();
            if (loc.sensitive() && !zones.contains(loc)) {
                zones.add(loc);
            }
        }
    }

    /**
     * Adds the sign-in form's credential fields to the failing step's sensitive zones, so a {@code ui.login}
     * that fills the form and then fails captures a screenshot with the typed password painted over.
     *
     * <p>The step's own parameters know only the alias; the form's locators live in the registry's auth
     * config. They are parsed exactly the way {@link UiLoginService} parses them — the same registry
     * spelling, the same {@code sensitive} mark — so the mask the failure path applies covers the same
     * fields the sign-in typed into (UITG-S017, SEC-05).
     *
     * @param auth the application's resolved auth config, whose {@code login} section names the form fields
     * @param alias the application alias, for the locator-expression error message
     * @param zones the mutable collection the form's username and password fields are appended to
     */
    private static void addLoginFormZones(UiAuthConfig auth, String alias, List<UiLocator> zones) {
        if (auth == null || auth.login() == null) {
            return;
        }
        UiLoginFormConfig form = auth.login();
        if (!form.fillable()) {
            return;
        }
        UiLocator username = UiLocatorExpressions.parse(form.usernameLocator(), "username-locator", alias).asSensitive();
        UiLocator password = UiLocatorExpressions.parse(form.passwordLocator(), "password-locator", alias).asSensitive();
        if (!zones.contains(username)) {
            zones.add(username);
        }
        if (!zones.contains(password)) {
            zones.add(password);
        }
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
            Instant startedAt,
            List<UiLocator> sensitiveZones) {
        String role = UiStepParameters.optionalString(parameters, UiStepParameters.ROLE);
        Duration timeout = Duration.ofMillis(UiStepParameters.positiveMillis(parameters, UiStepParameters.TIMEOUT_MILLIS, UiStepParameters.DEFAULT_TIMEOUT_MILLIS));
        Duration accountTimeout = Duration.ofMillis(
                UiStepParameters.positiveMillis(parameters, UiStepParameters.ACCOUNT_TIMEOUT_MILLIS, UiStepParameters.DEFAULT_ACCOUNT_TIMEOUT_MILLIS));

        String environment = context.scenarioContext().environment();
        UiSession opened = context.resourceScope().get(UiSession.resourceKey(application)).map(UiSession.class::cast).orElse(null);
        ResolvedUiApplication resolved = (opened != null) ? opened.application() : this.applicationResolver.resolve(application, context);
        UiAuthConfig auth = requireSignInConfigured(resolved);
        // The sign-in form's credential fields are sensitive by definition, but they live in the registry's
        // auth config, not in the step's own parameters — the step carries only the application alias. Fold
        // them into the failing step's sensitive zones here, so a ui.login that fills the form and then
        // fails (credentials rejected) captures a screenshot with the typed password painted over (UITG-S017,
        // SEC-05, acceptance: "скриншот формы входа содержит поле пароля закрашенным").
        addLoginFormZones(auth, resolved.alias(), sensitiveZones);
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
     * slow way — a step that quietly becomes ten seconds slower is a defect on nobody notices for months. And
     * a second account signing in to a context that still carries the first account's cookies would be saved
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
     * module holds no JSON parser of its own, and the parser that could read it is confined to the driver
     * package by design. So the state is discarded and the session opened once more without it. That is the
     * contract {@link StorageStateStore} already documents for every other kind of unusable file — a
     * corrupted cache costs one sign-in, never a run — and this is the branch where it was not yet honoured.
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
        List<UiAccount> accounts = accountsOf(application, auth);
        AccountPool pool = this.accountPools.forApplication(environment, application.alias(), accounts);
        return pool.lease(application.alias(), role, accountTimeout);
    }

    /**
     * The accounts an application offers: a roster behind {@code credentials-pool-ref}, or the one account
     * it names directly.
     *
     * <p>The direct pair covers <strong>every declared role</strong>, and one account per role is what that
     * means in the pool's vocabulary: there is nothing to choose between, so a scenario asking for any
     * declared role must be answered. Each entry keeps its own {@code accountId} — the pool leases by role,
     * and the browser session is stored per account, so one shared id would let a run holding the pair as
     * {@code client} block the same pair as {@code manager}.
     *
     * <p>The references are passed through untouched; {@link UiLoginService} resolves them at the moment the
     * form is filled, exactly as it resolves the ones a roster produced. That is why the {@code ${VAR:value}}
     * spelling works here without a line of its own.
     */
    private List<UiAccount> accountsOf(ResolvedUiApplication application, UiAuthConfig auth) {
        if (!auth.hasDirectCredentials()) {
            String poolRef = auth.credentialsPoolRef();
            String roster = SecretReferences.resolve(poolRef, this.referenceLookup);
            return AccountRoster.parse(roster, application.alias(), poolRef, auth.discoveryAccountRef());
        }
        String alias = application.alias();
        if (auth.roles().isEmpty()) {
            return List.of(new UiAccount(alias + "-account", DIRECT_ACCOUNT_ROLE, auth.credentialsUsername(), auth.credentialsPassword()));
        }
        List<UiAccount> accounts = new ArrayList<>(auth.roles().size());
        for (String declared : auth.roles()) {
            accounts.add(new UiAccount(alias + "-" + declared, declared, auth.credentialsUsername(), auth.credentialsPassword()));
        }
        return List.copyOf(accounts);
    }

    /**
     * Refuses a sign-in the registry cannot describe, before anything is leased or started.
     *
     * <p>{@code SSO} is present in the configuration vocabulary and not implemented here: an identity
     * provider's redirect flow depends on a configuration the SDK does not have, and it sits behind the
     * same external gate as the MFA question (G-1). Saying so is better than not having the value at all —
     * a registry can be written ahead of the SDK, and the refusal names the version rather than the spelling.
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
        // The run's housekeeping: the first run that reaches a real browser sweeps the artefacts directory
        // older than the retention (UITG-S018, SEC-09). Best-effort by construction (see RunArtifactRetention)
        // and never applies to storage-state; an open must not start with its own history.
        if (this.retentionSwept.compareAndSet(false, true)) {
            new RunArtifactRetention(runSettings.artifactRetention()).sweep(runSettings.artifactsDirectory());
        }
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
     * would make {@code injectCorrelationId} on any later step a silent no-op — a knob that reads as set
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
