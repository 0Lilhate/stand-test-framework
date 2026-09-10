package ru.alfa.stand.test.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

/**
 * The shared parameter-map schema for a REST {@code ScenarioStep}.
 *
 * <p>{@link RestStep} writes these keys into a core {@code GenericStep}'s parameter map; the
 * {@link RestStepExecutor} reads them back. Keeping the key names and the read/write logic in one
 * place makes the parameter map a single, explicit contract that a future YAML front-end can target
 * without sharing the typed Java builder.
 *
 * <p>Reader methods are deliberately strict: a value of the wrong shape is an
 * {@link StandTestException} (a configuration error), never a silent default.
 */
public final class RestStepParameters {

    /** Prefix of the core step type produced for a REST step (for example {@code rest.get}). */
    public static final String TYPE_PREFIX = StepParameterKeys.REST_PREFIX;

    /** Core step type of the polling step. */
    public static final String EXPECT_EVENTUALLY_TYPE = "rest.expectEventually";

    /** Default poll timeout when the step sets none: 30 seconds. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /** Default poll interval when the step sets none: 200 milliseconds. */
    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 200L;

    /** Parameter key: HTTP method name (see {@link RestMethod}). */
    public static final String METHOD = StepParameterKeys.METHOD;
    /** Parameter key: logical service alias resolved via the environment registry. */
    public static final String SERVICE = StepParameterKeys.SERVICE;
    /** Parameter key: request path appended to the resolved base URL. */
    public static final String PATH = StepParameterKeys.PATH;
    /** Parameter key: query parameters as a string-to-string map. */
    public static final String QUERY = StepParameterKeys.QUERY;
    /** Parameter key: request headers as a string-to-string map. */
    public static final String HEADERS = StepParameterKeys.HEADERS;
    /** Parameter key: inline request body. */
    public static final String BODY = StepParameterKeys.BODY;
    /** Parameter key: classpath resource whose content is the request body. */
    public static final String BODY_RESOURCE = StepParameterKeys.BODY_RESOURCE;
    /** Parameter key: whether to inject the SDK correlation id into the outbound request. */
    public static final String INJECT_CORRELATION_ID = StepParameterKeys.INJECT_CORRELATION_ID;
    /** Parameter key: expected HTTP status code. */
    public static final String EXPECTED_STATUS = StepParameterKeys.EXPECTED_STATUS;
    /** Parameter key: list of JSONPath assertions. */
    public static final String ASSERTIONS = StepParameterKeys.ASSERTIONS;
    /** Parameter key: list of response captures. */
    public static final String CAPTURES = StepParameterKeys.CAPTURES;

    /** Nested key (assertion / capture): JSONPath expression. */
    public static final String JSON_PATH = StepParameterKeys.JSON_PATH;
    /** Nested key (assertion): expected value. */
    public static final String EXPECTED_VALUE = StepParameterKeys.EXPECTED_VALUE;
    /** Nested key (assertion): matcher name; absent means EQUALS. */
    public static final String MATCHER = StepParameterKeys.MATCHER;
    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = StepParameterKeys.VARIABLE_NAME;

    /** Parameter key (expectEventually): maximum time to wait, in milliseconds. */
    public static final String TIMEOUT_MILLIS = StepParameterKeys.TIMEOUT_MILLIS;
    /** Parameter key (expectEventually): the poll interval between probes, in milliseconds. */
    public static final String POLL_INTERVAL_MILLIS = StepParameterKeys.POLL_INTERVAL_MILLIS;

    /** Message prefix shared by every parameter diagnostic of this adapter. */
    private static final String PARAMETER_PREFIX = "REST step parameter '";

    private RestStepParameters() {
    }

    static String requireString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException(PARAMETER_PREFIX + key + "' must be a non-blank string");
        }
        return text;
    }

    static Optional<String> optionalString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new StandTestException(PARAMETER_PREFIX + key + "' must be a string");
        }
        return Optional.of(text);
    }

    static RestMethod method(Map<String, Object> parameters) {
        String name = requireString(parameters, METHOD);
        try {
            return RestMethod.valueOf(name);
        } catch (IllegalArgumentException unsupported) {
            throw new StandTestException("Unsupported REST method: '" + name + "'");
        }
    }

    /**
     * The explicit correlation-injection choice, if the step set one: {@code Optional.of(true)} to force
     * injection, {@code Optional.of(false)} to opt out, {@code Optional.empty()} when unset (the executor
     * then defaults to injecting when the service declares a HEADER correlation carrier).
     */
    static Optional<Boolean> injectCorrelationIdFlag(Map<String, Object> parameters) {
        Object value = parameters.get(INJECT_CORRELATION_ID);
        return (value instanceof Boolean flag) ? Optional.of(flag) : Optional.empty();
    }

    static OptionalInt expectedStatus(Map<String, Object> parameters) {
        Object value = parameters.get(EXPECTED_STATUS);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (!(value instanceof Integer status)) {
            throw new StandTestException(PARAMETER_PREFIX + EXPECTED_STATUS + "' must be an integer");
        }
        return OptionalInt.of(status);
    }

    static Map<String, String> stringMap(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException(PARAMETER_PREFIX + key + "' must be a map");
        }
        return raw.entrySet().stream()
                .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), entry -> String.valueOf(entry.getValue()),
                        (first, second) -> second, LinkedHashMap::new));
    }

    static List<RestAssertion> assertions(Map<String, Object> parameters) {
        return entryList(parameters, ASSERTIONS).stream()
                .map(RestStepParameters::assertion)
                .toList();
    }

    private static RestAssertion assertion(Map<String, Object> entry) {
        Object path = entry.get(JSON_PATH);
        Object expected = entry.get(EXPECTED_VALUE);
        if (!(path instanceof String text)) {
            throw new StandTestException("REST assertion '" + JSON_PATH + "' must be a string");
        }
        if (expected == null) {
            throw new StandTestException("REST assertion '" + EXPECTED_VALUE + "' must not be null");
        }
        AssertionMatcher matcher = matcher(entry);
        validateMatcherOperand(matcher, expected, text);
        return new RestAssertion(text, expected, matcher);
    }

    private static AssertionMatcher matcher(Map<String, Object> entry) {
        Object value = entry.get(MATCHER);
        if (value == null) {
            return AssertionMatcher.EQUALS;
        }
        if (!(value instanceof String name)) {
            throw new StandTestException("REST assertion '" + MATCHER + "' must be a string");
        }
        try {
            return AssertionMatcher.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new StandTestException("Unknown REST assertion matcher: '" + name + "'");
        }
    }

    /**
     * Fail-fast operand checks, applied while reading parameters so a structurally broken assertion
     * never reaches the stand (mirrors the schema's per-matcher value types): EXISTS/NOT_NULL carry a
     * Boolean polarity, MATCHES carries a compilable regular expression.
     */
    private static void validateMatcherOperand(AssertionMatcher matcher, Object expected, String jsonPath) {
        if ((matcher == AssertionMatcher.EXISTS || matcher == AssertionMatcher.NOT_NULL) && !(expected instanceof Boolean)) {
            throw new StandTestException("REST assertion at '" + jsonPath + "': matcher " + matcher + " requires a boolean '"
                    + EXPECTED_VALUE + "'");
        }
        if (matcher == AssertionMatcher.MATCHES) {
            if (!(expected instanceof String regex)) {
                throw new StandTestException("REST assertion at '" + jsonPath + "': matcher MATCHES requires a string regular expression");
            }
            try {
                Pattern.compile(regex);
            } catch (PatternSyntaxException invalid) {
                throw new StandTestException("REST assertion at '" + jsonPath + "': invalid regular expression for matcher MATCHES",
                        invalid);
            }
        }
    }

    static long positiveMillis(Map<String, Object> parameters, String key, long defaultMillis) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultMillis;
        }
        if (!(value instanceof Long) && !(value instanceof Integer)) {
            throw new StandTestException(PARAMETER_PREFIX + key + "' must be an integer number of milliseconds");
        }
        long millis = ((Number) value).longValue();
        if (millis <= 0) {
            throw new StandTestException(PARAMETER_PREFIX + key + "' must be strictly positive");
        }
        return millis;
    }

    static List<RestCapture> captures(Map<String, Object> parameters) {
        return entryList(parameters, CAPTURES).stream()
                .map(entry -> {
                    Object name = entry.get(VARIABLE_NAME);
                    Object path = entry.get(JSON_PATH);
                    if (!(name instanceof String variableName) || !(path instanceof String jsonPath)) {
                        throw new StandTestException("REST capture requires string '" + VARIABLE_NAME + "' and '" + JSON_PATH + "'");
                    }
                    return new RestCapture(variableName, jsonPath);
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entryList(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StandTestException(PARAMETER_PREFIX + key + "' must be a list");
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?>)) {
                        throw new StandTestException(PARAMETER_PREFIX + key + "' entries must be maps");
                    }
                    return (Map<String, Object>) item;
                })
                .toList();
    }
}
