package ru.alfa.stand.test.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

/**
 * The wire schema of a {@code ui.*} step: the parameter-map keys {@link UiStep} writes and
 * {@link UiStepExecutor} reads, plus the readers that turn the raw map back into the typed model.
 *
 * <p>Every key is a reference to {@link StepParameterKeys} rather than a string literal. That is not
 * cosmetic: the runtime guardrail validator inspects steps <em>by key name</em> (the application alias,
 * the timeout bounds), so a UI step that spelled its own keys would quietly fall outside checks it is
 * supposed to be subject to.
 *
 * <p>The type is public for the same reason {@code RestStepParameters} is: the declarative front-end
 * (and any consumer inspecting a foreign {@code GenericStep}) needs the contract without guessing at
 * literals.
 */
public final class UiStepParameters {

    /** Prefix of every UI step type. */
    public static final String TYPE_PREFIX = StepParameterKeys.UI_PREFIX;

    /** Step type: open the application at a relative path. */
    public static final String OPEN_TYPE = "ui.open";

    /** Step type: click an element. */
    public static final String CLICK_TYPE = "ui.click";

    /** Step type: type a value into an element. */
    public static final String FILL_TYPE = "ui.fill";

    /** Step type: assert element properties once, without waiting. */
    public static final String EXPECT_TYPE = "ui.expect";

    /** Step type: poll element properties until they hold or the timeout expires. */
    public static final String EXPECT_EVENTUALLY_TYPE = "ui.expectEventually";

    /**
     * Step type: sign in to the application as a test account of the requested role.
     *
     * <p>This is the one UI step type core also knows by name ({@code StepParameterKeys.UI_LOGIN_TYPE}),
     * because the pre-flight validator has a rule about it — that a role must be named once the application
     * declares roles. Mirroring the constant rather than repeating the literal is what keeps the rule and
     * the step from drifting apart.
     */
    public static final String LOGIN_TYPE = StepParameterKeys.UI_LOGIN_TYPE;

    /** Default overall timeout of a polling step, in milliseconds. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /**
     * Default bound on waiting for a free test account, in milliseconds.
     *
     * <p>A minute, and never unbounded: a suite parallel beyond the size of its account pool must queue and
     * then fail with a message naming the pool, not hang until CI kills it. ADR-UI-006 §3 fixes both the
     * default and the ceiling, which is the SDK-wide {@code DefaultScenarioValidator.MAX_TIMEOUT_MILLIS}.
     */
    public static final long DEFAULT_ACCOUNT_TIMEOUT_MILLIS = 60_000L;

    /** Default interval between polls, in milliseconds — the same default the REST adapter uses. */
    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 200L;

    /** Default bound on a single action (click / fill / navigate), in milliseconds. */
    public static final long DEFAULT_ACTION_TIMEOUT_MILLIS = 10_000L;

    /** Parameter key: the logical UI application alias. */
    public static final String APPLICATION = StepParameterKeys.APPLICATION;

    /** Parameter key: the relative path an open step navigates to. */
    public static final String PATH = StepParameterKeys.PATH;

    /** Parameter key: the element address. */
    public static final String LOCATOR = StepParameterKeys.LOCATOR;

    /** Parameter key: the value a fill step types. */
    public static final String VALUE = StepParameterKeys.VALUE;

    /** Parameter key: the assertions of an expect / expectEventually step. */
    public static final String ASSERTIONS = StepParameterKeys.ASSERTIONS;

    /** Parameter key: the captures of an expect / expectEventually step. */
    public static final String CAPTURES = StepParameterKeys.CAPTURES;

    /** Parameter key: whether to inject the SDK correlation id into the page's outgoing requests. */
    public static final String INJECT_CORRELATION_ID = StepParameterKeys.INJECT_CORRELATION_ID;

    /** Parameter key: the overall timeout, in milliseconds. */
    public static final String TIMEOUT_MILLIS = StepParameterKeys.TIMEOUT_MILLIS;

    /** Parameter key: the interval between polls, in milliseconds. */
    public static final String POLL_INTERVAL_MILLIS = StepParameterKeys.POLL_INTERVAL_MILLIS;

    /** Parameter key (login): the role whose test account the step leases. */
    public static final String ROLE = StepParameterKeys.ROLE;

    /** Parameter key (login): the bound on waiting for a free test account, in milliseconds. */
    public static final String ACCOUNT_TIMEOUT_MILLIS = StepParameterKeys.ACCOUNT_TIMEOUT_MILLIS;

    /** Nested key (locator): the addressing strategy. */
    public static final String STRATEGY = StepParameterKeys.STRATEGY;

    /** Nested key (locator): the accessible name of a ROLE locator. */
    public static final String ACCESSIBLE_NAME = StepParameterKeys.ACCESSIBLE_NAME;

    /** Nested key (locator): whether the element's values are masked wherever the SDK would print them. */
    public static final String SENSITIVE = StepParameterKeys.SENSITIVE;

    /** Nested key (assertion): the element property under test. */
    public static final String PROPERTY = StepParameterKeys.PROPERTY;

    /** Nested key (assertion / capture): the element attribute name. */
    public static final String ATTRIBUTE = StepParameterKeys.ATTRIBUTE;

    /** Nested key (assertion): the expected value. */
    public static final String EXPECTED_VALUE = StepParameterKeys.EXPECTED_VALUE;

    /** Nested key (assertion): the matcher name; absent means EQUALS. */
    public static final String MATCHER = StepParameterKeys.MATCHER;

    /** Nested key (capture): the variable the captured value is stored under. */
    public static final String VARIABLE_NAME = StepParameterKeys.VARIABLE_NAME;

    /** Nested key (capture): where the value is read from. */
    public static final String SOURCE = StepParameterKeys.SOURCE;

    private UiStepParameters() {
    }

    static String requireString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("UI step parameter '" + key + "' must be a non-blank string");
        }
        return text;
    }

    static String optionalString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("UI step parameter '" + key + "' must be a non-blank string when present");
        }
        return text;
    }

    static Optional<Boolean> injectCorrelationIdFlag(Map<String, Object> parameters) {
        Object value = parameters.get(INJECT_CORRELATION_ID);
        return (value instanceof Boolean flag) ? Optional.of(flag) : Optional.empty();
    }

    static long positiveMillis(Map<String, Object> parameters, String key, long defaultMillis) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultMillis;
        }
        if (!(value instanceof Number number)) {
            throw new StandTestException("UI step parameter '" + key + "' must be a number of milliseconds");
        }
        long millis = number.longValue();
        if (millis <= 0) {
            throw new StandTestException("UI step parameter '" + key + "' must be strictly positive, but was " + millis);
        }
        return millis;
    }

    static UiLocator locator(Map<String, Object> parameters) {
        Object value = parameters.get(LOCATOR);
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException("UI step parameter '" + LOCATOR + "' must be a map");
        }
        return readLocator(raw);
    }

    static Map<String, Object> writeLocator(UiLocator locator) {
        Objects.requireNonNull(locator, "locator must not be null");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(STRATEGY, locator.strategy().name());
        map.put(VALUE, locator.value());
        if (locator.accessibleName() != null) {
            map.put(ACCESSIBLE_NAME, locator.accessibleName());
        }
        if (locator.sensitive()) {
            map.put(SENSITIVE, Boolean.TRUE);
        }
        return Map.copyOf(map);
    }

    static List<UiAssertion> assertions(Map<String, Object> parameters) {
        List<UiAssertion> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, ASSERTIONS)) {
            UiProperty property = enumValue(UiProperty.class, entry.get(PROPERTY), PROPERTY);
            Object expected = entry.get(EXPECTED_VALUE);
            if (expected == null) {
                throw new StandTestException("UI assertion '" + EXPECTED_VALUE + "' must not be null");
            }
            AssertionMatcher matcher = matcher(entry);
            String attribute = optionalNestedString(entry, ATTRIBUTE);
            result.add(new UiAssertion(property, attribute, expected, matcher));
        }
        return result;
    }

    static Map<String, Object> writeAssertion(UiAssertion assertion) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(PROPERTY, assertion.property().name());
        if (assertion.attribute() != null) {
            map.put(ATTRIBUTE, assertion.attribute());
        }
        map.put(EXPECTED_VALUE, assertion.expectedValue());
        map.put(MATCHER, assertion.matcher().name());
        return Map.copyOf(map);
    }

    static List<UiCapture> captures(Map<String, Object> parameters) {
        List<UiCapture> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, CAPTURES)) {
            Object variableName = entry.get(VARIABLE_NAME);
            if (!(variableName instanceof String name) || name.isBlank()) {
                throw new StandTestException("UI capture '" + VARIABLE_NAME + "' must be a non-blank string");
            }
            Object locator = entry.get(LOCATOR);
            if (!(locator instanceof Map<?, ?> raw)) {
                throw new StandTestException("UI capture '" + LOCATOR + "' must be a map");
            }
            UiCaptureSource source = enumValue(UiCaptureSource.class, entry.get(SOURCE), SOURCE);
            result.add(new UiCapture(name, readLocator(raw), source, optionalNestedString(entry, ATTRIBUTE)));
        }
        return result;
    }

    static Map<String, Object> writeCapture(UiCapture capture) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(VARIABLE_NAME, capture.variableName());
        map.put(LOCATOR, writeLocator(capture.locator()));
        map.put(SOURCE, capture.source().name());
        if (capture.attribute() != null) {
            map.put(ATTRIBUTE, capture.attribute());
        }
        return Map.copyOf(map);
    }

    private static UiLocator readLocator(Map<?, ?> raw) {
        LocatorStrategy strategy = enumValue(LocatorStrategy.class, raw.get(STRATEGY), STRATEGY);
        Object value = raw.get(VALUE);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("UI locator '" + VALUE + "' must be a non-blank string");
        }
        Object accessibleName = raw.get(ACCESSIBLE_NAME);
        if (accessibleName != null && !(accessibleName instanceof String)) {
            throw new StandTestException("UI locator '" + ACCESSIBLE_NAME + "' must be a string");
        }
        Object sensitive = raw.get(SENSITIVE);
        if (sensitive != null && !(sensitive instanceof Boolean)) {
            throw new StandTestException("UI locator '" + SENSITIVE + "' must be a boolean");
        }
        try {
            return new UiLocator(strategy, text, (String) accessibleName, Boolean.TRUE.equals(sensitive));
        } catch (IllegalArgumentException invalid) {
            throw new StandTestException("UI locator is invalid: " + invalid.getMessage(), invalid);
        }
    }

    private static AssertionMatcher matcher(Map<String, Object> entry) {
        Object value = entry.get(MATCHER);
        if (value == null) {
            return AssertionMatcher.EQUALS;
        }
        return enumValue(AssertionMatcher.class, value, MATCHER);
    }

    private static String optionalNestedString(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("UI step nested parameter '" + key + "' must be a non-blank string when present");
        }
        return text;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value, String key) {
        if (!(value instanceof String name) || name.isBlank()) {
            throw new StandTestException("UI step parameter '" + key + "' must be a non-blank string");
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new StandTestException("Unknown value '" + name + "' for UI step parameter '" + key + "'", unknown);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entryList(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new StandTestException("UI step parameter '" + key + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new StandTestException("Each entry of UI step parameter '" + key + "' must be a map");
            }
            result.add((Map<String, Object>) entry);
        }
        return result;
    }
}
