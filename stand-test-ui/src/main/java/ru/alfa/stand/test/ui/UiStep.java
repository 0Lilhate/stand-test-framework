package ru.alfa.stand.test.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * The lazy builder for UI steps — the single entry point a test author (or the generating agent) writes,
 * and the direct analogue of {@code RestStep} / {@code DbStep} / {@code KafkaStep} / {@code GrpcStep}.
 *
 * <p>Building performs no IO whatsoever: it assembles an immutable {@link ScenarioStep} that the runner
 * validates and then executes. An eager fluent call would bypass the pre-flight guardrails, which is why
 * the SDK's builders are lazy by rule.
 *
 * <p>The application is always named by its registry alias and the path is always relative. There is no
 * parameter that accepts an absolute URL — the same "no place to put it" ban the other adapters use.
 *
 * <pre>{@code
 * UiStep.open("client-portal", "/applications/new").id("open-form").assertVisible().build();
 * UiStep.fill("client-portal", AMOUNT, "${amount}").id("fill-amount").build();
 * UiStep.click("client-portal", SUBMIT).id("submit").build();
 * UiStep.expectEventually("client-portal", STATUS).id("await-status").assertText("Accepted").withinSeconds(20).build();
 * }</pre>
 */
public final class UiStep {

    /** Rejection shared by every factory that addresses an element. */
    private static final String LOCATOR_REQUIRED = "locator must not be null";

    /** Rejection shared by every assertion factory. */
    private static final String EXPECTED_REQUIRED = "expected must not be null";

    private final String type;

    private final String application;

    private final UiLocator locator;

    private final String path;

    private final String value;

    private final List<UiAssertion> assertions = new ArrayList<>();

    private final List<UiCapture> captures = new ArrayList<>();

    private String id;

    private String description;

    private String role;

    private Boolean injectCorrelationId;

    private Long timeoutMillis;

    private Long pollIntervalMillis;

    private Long accountTimeoutMillis;

    private UiStep(String type, String application, UiLocator locator, String path, String value) {
        this.type = type;
        this.application = requireNonBlank(application, "application");
        this.locator = locator;
        this.path = path;
        this.value = value;
    }

    /**
     * {@code ui.open} — opens the application at a path relative to its registered base URL.
     *
     * @param application the UI application alias, whitelisted in the environment registry
     * @param path the relative path, for example {@code /applications/new}
     * @return the builder
     */
    public static UiStep open(String application, String path) {
        return new UiStep(UiStepParameters.OPEN_TYPE, application, null, requireRelativePath(path), null);
    }

    /**
     * {@code ui.click} — clicks the located element.
     *
     * @param application the UI application alias
     * @param locator the element to click
     * @return the builder
     */
    public static UiStep click(String application, UiLocator locator) {
        return new UiStep(UiStepParameters.CLICK_TYPE, application, Objects.requireNonNull(locator, LOCATOR_REQUIRED), null,
                null);
    }

    /**
     * {@code ui.fill} — types a value into the located element. The value may contain {@code ${var}}
     * placeholders, resolved at execution time by the same resolver a REST body goes through.
     *
     * @param application the UI application alias
     * @param locator the element to type into
     * @param value the value to type
     * @return the builder
     */
    public static UiStep fill(String application, UiLocator locator, String value) {
        return new UiStep(
                UiStepParameters.FILL_TYPE,
                application,
                Objects.requireNonNull(locator, LOCATOR_REQUIRED),
                null,
                Objects.requireNonNull(value, "value must not be null"));
    }

    /**
     * {@code ui.expect} — asserts the located element's properties once, without waiting.
     *
     * @param application the UI application alias
     * @param locator the element to inspect
     * @return the builder
     */
    public static UiStep expect(String application, UiLocator locator) {
        return new UiStep(UiStepParameters.EXPECT_TYPE, application, Objects.requireNonNull(locator, LOCATOR_REQUIRED), null,
                null);
    }

    /**
     * {@code ui.expectEventually} — polls the located element until every assertion holds or the
     * bounded timeout expires. This is the SDK's only sanctioned way to wait for the UI; there is no
     * sleep anywhere in the adapter.
     *
     * @param application the UI application alias
     * @param locator the element to poll
     * @return the builder
     */
    public static UiStep expectEventually(String application, UiLocator locator) {
        return new UiStep(UiStepParameters.EXPECT_EVENTUALLY_TYPE, application,
                Objects.requireNonNull(locator, LOCATOR_REQUIRED), null, null);
    }

    /**
     * {@code ui.login} — signs in to the application as a test account leased from its pool.
     *
     * <p>Signing in is a step, not an implicit side effect of opening a session, for three reasons that all
     * show up in a report: it gets its own number and its own layer ({@code Step [1/6] 'login' (ui.login)}),
     * its failure is classified on its own (rejected credentials are a failure, an unreachable identity
     * provider is not), and its duration is measured on its own.
     *
     * <p>Put it <strong>before</strong> the first {@code ui.open} of the same application. A saved session
     * can only be restored while the browsing context is being created, so a sign-in that arrives after a
     * page is already open can no longer reuse one — it says so rather than silently signing in the slow
     * way.
     *
     * <pre>{@code
     * UiStep.login("client-portal").id("login").role("client").build();
     * }</pre>
     *
     * @param application the UI application alias, whitelisted in the environment registry
     * @return the builder
     */
    public static UiStep login(String application) {
        return new UiStep(UiStepParameters.LOGIN_TYPE, application, null, null, null);
    }

    /**
     * Sets the step id used in reports, logs and failure messages.
     *
     * @param id the step id
     * @return this builder
     */
    public UiStep id(String id) {
        this.id = requireNonBlank(id, "id");
        return this;
    }

    /**
     * Sets the human-readable description shown in the report next to the step.
     *
     * @param description the description
     * @return this builder
     */
    public UiStep description(String description) {
        this.description = requireNonBlank(description, "description");
        return this;
    }

    /**
     * Asserts that the element is visible.
     *
     * @return this builder
     */
    public UiStep assertVisible() {
        return assertVisible(true);
    }

    /**
     * Asserts the element's visibility.
     *
     * @param visible the expected visibility
     * @return this builder
     */
    public UiStep assertVisible(boolean visible) {
        return addAssertion(new UiAssertion(UiProperty.VISIBLE, visible, AssertionMatcher.EQUALS));
    }

    /**
     * Asserts whether the element is enabled.
     *
     * @param enabled the expected enabled state
     * @return this builder
     */
    public UiStep assertEnabled(boolean enabled) {
        return addAssertion(new UiAssertion(UiProperty.ENABLED, enabled, AssertionMatcher.EQUALS));
    }

    /**
     * Asserts the element's text equals the expected value.
     *
     * @param expected the expected text
     * @return this builder
     */
    public UiStep assertText(String expected) {
        return addAssertion(new UiAssertion(UiProperty.TEXT, Objects.requireNonNull(expected, EXPECTED_REQUIRED),
                AssertionMatcher.EQUALS));
    }

    /**
     * Asserts the element's text contains the expected fragment.
     *
     * @param expected the expected fragment
     * @return this builder
     */
    public UiStep assertTextContains(String expected) {
        return addAssertion(new UiAssertion(UiProperty.TEXT, Objects.requireNonNull(expected, EXPECTED_REQUIRED),
                AssertionMatcher.CONTAINS));
    }

    /**
     * Asserts the element's text matches the regular expression.
     *
     * @param regex the regular expression
     * @return this builder
     */
    public UiStep assertTextMatches(String regex) {
        return addAssertion(new UiAssertion(UiProperty.TEXT, Objects.requireNonNull(regex, "regex must not be null"),
                AssertionMatcher.MATCHES));
    }

    /**
     * Asserts the value of an input element equals the expected value.
     *
     * @param expected the expected value
     * @return this builder
     */
    public UiStep assertValue(String expected) {
        return addAssertion(new UiAssertion(UiProperty.VALUE, Objects.requireNonNull(expected, EXPECTED_REQUIRED),
                AssertionMatcher.EQUALS));
    }

    /**
     * Asserts an attribute of the element equals the expected value.
     *
     * @param name the attribute name
     * @param expected the expected attribute value
     * @return this builder
     */
    public UiStep assertAttribute(String name, String expected) {
        return addAssertion(new UiAssertion(
                UiProperty.ATTRIBUTE,
                requireNonBlank(name, "attribute name"),
                Objects.requireNonNull(expected, EXPECTED_REQUIRED),
                AssertionMatcher.EQUALS));
    }

    /**
     * The extension point behind the sugar above: any property with any core matcher, so a new kind of
     * check does not need a new method (and a new SDK release).
     *
     * <p>Boolean properties accept only {@code EQUALS}; anything else fails here rather than at run time.
     *
     * @param property the property under test
     * @param matcher how expected and actual are compared
     * @param expected the expected value
     * @return this builder
     */
    public UiStep assertProperty(UiProperty property, AssertionMatcher matcher, Object expected) {
        return addAssertion(new UiAssertion(property, expected, matcher));
    }

    /**
     * Captures the step locator's text into a variable, visible to every later step as
     * {@code ${variableName}}.
     *
     * @param variableName the variable name
     * @return this builder
     */
    public UiStep capture(String variableName) {
        if (this.locator == null) {
            throw new IllegalStateException("capture(variableName) reads the step's own locator, which " + this.type
                    + " does not have — use capture(variableName, from)");
        }
        return capture(variableName, this.locator);
    }

    /**
     * Captures the text of another element into a variable.
     *
     * @param variableName the variable name
     * @param from the element to read
     * @return this builder
     */
    public UiStep capture(String variableName, UiLocator from) {
        return capture(variableName, from, UiCaptureSource.TEXT);
    }

    /**
     * Captures a value from another element into a variable.
     *
     * @param variableName the variable name
     * @param from the element to read
     * @param source which part of the element is read
     * @return this builder
     */
    public UiStep capture(String variableName, UiLocator from, UiCaptureSource source) {
        this.captures.add(new UiCapture(variableName, from, source));
        return this;
    }

    /**
     * Captures an attribute of another element into a variable.
     *
     * @param variableName the variable name
     * @param from the element to read
     * @param attribute the attribute name
     * @return this builder
     */
    public UiStep captureAttribute(String variableName, UiLocator from, String attribute) {
        this.captures.add(new UiCapture(variableName, from, UiCaptureSource.ATTRIBUTE, attribute));
        return this;
    }

    /**
     * Bounds the step's own wait: the polling of {@code ui.expectEventually}, or the sign-in of
     * {@code ui.login} (checking a restored session, filling the form, and the appearance of the element
     * that proves the browser is signed in). Waiting for a free account is bounded separately, by
     * {@link #accountTimeout(Duration)}.
     *
     * @param timeout the overall timeout, strictly positive
     * @return this builder
     */
    public UiStep within(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be strictly positive");
        }
        this.timeoutMillis = timeout.toMillis();
        return this;
    }

    /**
     * Bounds the step's own wait, in seconds.
     *
     * @param seconds the overall timeout in seconds
     * @return this builder
     */
    public UiStep withinSeconds(long seconds) {
        return within(Duration.ofSeconds(seconds));
    }

    /**
     * Sets the interval between polls of {@code ui.expectEventually}.
     *
     * @param pollInterval the interval, strictly positive
     * @return this builder
     */
    public UiStep pollInterval(Duration pollInterval) {
        Objects.requireNonNull(pollInterval, "pollInterval must not be null");
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval must be strictly positive");
        }
        this.pollIntervalMillis = pollInterval.toMillis();
        return this;
    }

    /**
     * Names the role whose test account {@code ui.login} leases.
     *
     * <p>Mandatory once the application declares roles, and refused pre-flight when it does not declare the
     * one asked for. "Any account" is deliberately not expressible there: a case whose precondition is
     * "as a manager", and a negative check that a client cannot reach a manager's screen, are both
     * unwritable without it.
     *
     * @param role the role, as declared in the application's {@code auth.roles}
     * @return this builder
     */
    public UiStep role(String role) {
        requireLoginStep("role(...)");
        this.role = requireNonBlank(role, "role");
        return this;
    }

    /**
     * Bounds how long {@code ui.login} waits for a free test account when the pool is exhausted.
     *
     * <p>Default {@link UiStepParameters#DEFAULT_ACCOUNT_TIMEOUT_MILLIS}. There is no way to wait forever:
     * a suite whose parallelism exceeds its pool must queue and then fail with a message naming the pool,
     * because a run that hangs reports nothing.
     *
     * @param timeout the bound, strictly positive
     * @return this builder
     */
    public UiStep accountTimeout(Duration timeout) {
        requireLoginStep("accountTimeout(...)");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("accountTimeout must be strictly positive");
        }
        this.accountTimeoutMillis = timeout.toMillis();
        return this;
    }

    /**
     * Injects the SDK-owned correlation id as a header on every request the page makes.
     *
     * @return this builder
     */
    public UiStep injectCorrelationId() {
        return injectCorrelationId(true);
    }

    /**
     * Controls correlation-id injection for this step's session.
     *
     * @param inject whether to inject
     * @return this builder
     */
    public UiStep injectCorrelationId(boolean inject) {
        this.injectCorrelationId = inject;
        return this;
    }

    /**
     * Assembles the immutable step. Nothing is executed here.
     *
     * @return the scenario step
     */
    public ScenarioStep build() {
        boolean polling = UiStepParameters.EXPECT_EVENTUALLY_TYPE.equals(this.type);
        boolean asserting = polling || UiStepParameters.EXPECT_TYPE.equals(this.type);
        boolean waiting = polling || UiStepParameters.LOGIN_TYPE.equals(this.type);
        if (asserting && this.assertions.isEmpty()) {
            throw new IllegalStateException(this.type
                    + " requires at least one assertion — a step that expects nothing cannot fail and is not a check");
        }
        if (!asserting && !this.assertions.isEmpty()) {
            // An assertion needs an element to be about, and it needs its own step number in the report.
            // Both are reasons to keep checks in their own step type rather than as a rider on an action.
            throw new IllegalStateException("assertions belong on " + UiStepParameters.EXPECT_TYPE + " / "
                    + UiStepParameters.EXPECT_EVENTUALLY_TYPE
                    + ", not on " + this.type);
        }
        if (!waiting && this.timeoutMillis != null) {
            throw new IllegalStateException("within(...) bounds a wait, and only " + UiStepParameters.EXPECT_EVENTUALLY_TYPE + " and "
                    + UiStepParameters.LOGIN_TYPE + " wait; " + this.type + " does not accept it");
        }
        if (!polling && this.pollIntervalMillis != null) {
            throw new IllegalStateException("pollInterval(...) polls, and only " + UiStepParameters.EXPECT_EVENTUALLY_TYPE + " polls; "
                    + this.type + " does not accept it");
        }
        if (this.pollIntervalMillis != null && this.pollIntervalMillis > effectiveTimeoutMillis()) {
            throw new IllegalStateException("pollInterval(" + this.pollIntervalMillis + " ms) must not exceed the step's timeout ("
                    + effectiveTimeoutMillis()
                            + " ms) — a step that polls less often than it waits performs a single probe and then waits for the interval");
        }
        if (!this.captures.isEmpty() && !asserting) {
            throw new IllegalStateException("captures read the page after a check, so they belong on " + UiStepParameters.EXPECT_TYPE
                    + " / "
                    + UiStepParameters.EXPECT_EVENTUALLY_TYPE + ", not on " + this.type);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(UiStepParameters.APPLICATION, this.application);
        if (this.role != null) {
            parameters.put(UiStepParameters.ROLE, this.role);
        }
        if (this.accountTimeoutMillis != null) {
            parameters.put(UiStepParameters.ACCOUNT_TIMEOUT_MILLIS, this.accountTimeoutMillis);
        }
        if (this.path != null) {
            parameters.put(UiStepParameters.PATH, this.path);
        }
        if (this.locator != null) {
            parameters.put(UiStepParameters.LOCATOR, UiStepParameters.writeLocator(this.locator));
        }
        if (this.value != null) {
            parameters.put(UiStepParameters.VALUE, this.value);
        }
        if (this.injectCorrelationId != null) {
            parameters.put(UiStepParameters.INJECT_CORRELATION_ID, this.injectCorrelationId);
        }
        if (!this.assertions.isEmpty()) {
            parameters.put(UiStepParameters.ASSERTIONS, this.assertions.stream().map(UiStepParameters::writeAssertion).toList());
        }
        if (!this.captures.isEmpty()) {
            parameters.put(UiStepParameters.CAPTURES, this.captures.stream().map(UiStepParameters::writeCapture).toList());
        }
        if (this.timeoutMillis != null) {
            parameters.put(UiStepParameters.TIMEOUT_MILLIS, this.timeoutMillis);
        }
        if (this.pollIntervalMillis != null) {
            parameters.put(UiStepParameters.POLL_INTERVAL_MILLIS, this.pollIntervalMillis);
        }
        return new GenericStep(defaultedId(), this.type, this.description, parameters);
    }

    private long effectiveTimeoutMillis() {
        return (this.timeoutMillis == null) ? UiStepParameters.DEFAULT_TIMEOUT_MILLIS : this.timeoutMillis;
    }

    private UiStep addAssertion(UiAssertion assertion) {
        this.assertions.add(assertion);
        return this;
    }

    private void requireLoginStep(String method) {
        if (!UiStepParameters.LOGIN_TYPE.equals(this.type)) {
            throw new IllegalStateException(method + " configures a sign-in, and only " + UiStepParameters.LOGIN_TYPE + " signs in; "
                    + this.type + " does not accept it");
        }
    }

    /**
     * The generated id names what the step does to <em>which</em> element, not just its type and
     * application: step ids must be unique within a scenario, and the most ordinary UI scenario there is —
     * filling a form — is several {@code ui.fill} steps on one application. An id built from type and alias
     * alone would make that scenario fail validation with "Duplicate step id" on the second field. This
     * follows the other adapters, whose defaults likewise carry the discriminating part (the path, the
     * topic, the datasource).
     */
    private String defaultedId() {
        if (this.id != null) {
            return this.id;
        }
        if (this.locator != null) {
            return this.type + " " + this.locator.describe();
        }
        if (this.role != null) {
            // Two sign-ins in one scenario differ by role, not by application — an id built from the alias
            // alone would collide on exactly the case role(...) exists for.
            return this.type + " " + this.application + " as " + this.role;
        }
        return (this.path == null) ? this.type + " " + this.application : this.type + " " + this.path;
    }

    private static String requireRelativePath(String path) {
        String value = requireNonBlank(path, "path");
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("//")) {
            throw new IllegalArgumentException("path must be relative to the application's registered base URL; an absolute address is not "
                    + "addressable by the SDK, but got: "
                    + value);
        }
        return value;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
