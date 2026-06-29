package ru.alfa.stand.test.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import ru.alfa.stand.test.core.exception.StandTestException;

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
    public static final String TYPE_PREFIX = "rest.";

    /** Parameter key: HTTP method name (see {@link RestMethod}). */
    public static final String METHOD = "method";
    /** Parameter key: logical service alias resolved via the environment registry. */
    public static final String SERVICE = "service";
    /** Parameter key: request path appended to the resolved base URL. */
    public static final String PATH = "path";
    /** Parameter key: query parameters as a string-to-string map. */
    public static final String QUERY = "query";
    /** Parameter key: request headers as a string-to-string map. */
    public static final String HEADERS = "headers";
    /** Parameter key: inline request body. */
    public static final String BODY = "body";
    /** Parameter key: classpath resource whose content is the request body. */
    public static final String BODY_RESOURCE = "bodyResource";
    /** Parameter key: whether to inject the SDK correlation id into the outbound request. */
    public static final String INJECT_CORRELATION_ID = "injectCorrelationId";
    /** Parameter key: expected HTTP status code. */
    public static final String EXPECTED_STATUS = "expectedStatus";
    /** Parameter key: list of JSONPath assertions. */
    public static final String ASSERTIONS = "assertions";
    /** Parameter key: list of response captures. */
    public static final String CAPTURES = "captures";

    /** Nested key (assertion / capture): JSONPath expression. */
    public static final String JSON_PATH = "jsonPath";
    /** Nested key (assertion): expected value. */
    public static final String EXPECTED_VALUE = "expectedValue";
    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = "variableName";

    private RestStepParameters() {
    }

    static String requireString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("REST step parameter '" + key + "' must be a non-blank string");
        }
        return text;
    }

    static Optional<String> optionalString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("REST step parameter '" + key + "' must be a string");
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

    static boolean injectCorrelationId(Map<String, Object> parameters) {
        return Boolean.TRUE.equals(parameters.get(INJECT_CORRELATION_ID));
    }

    static OptionalInt expectedStatus(Map<String, Object> parameters) {
        Object value = parameters.get(EXPECTED_STATUS);
        if (value == null) {
            return OptionalInt.empty();
        }
        if (!(value instanceof Integer status)) {
            throw new StandTestException("REST step parameter '" + EXPECTED_STATUS + "' must be an integer");
        }
        return OptionalInt.of(status);
    }

    static Map<String, String> stringMap(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException("REST step parameter '" + key + "' must be a map");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    static List<RestAssertion> assertions(Map<String, Object> parameters) {
        List<RestAssertion> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, ASSERTIONS)) {
            Object path = entry.get(JSON_PATH);
            Object expected = entry.get(EXPECTED_VALUE);
            if (!(path instanceof String text)) {
                throw new StandTestException("REST assertion '" + JSON_PATH + "' must be a string");
            }
            if (expected == null) {
                throw new StandTestException("REST assertion '" + EXPECTED_VALUE + "' must not be null");
            }
            result.add(new RestAssertion(text, expected));
        }
        return result;
    }

    static List<RestCapture> captures(Map<String, Object> parameters) {
        List<RestCapture> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, CAPTURES)) {
            Object name = entry.get(VARIABLE_NAME);
            Object path = entry.get(JSON_PATH);
            if (!(name instanceof String variableName) || !(path instanceof String jsonPath)) {
                throw new StandTestException("REST capture requires string '" + VARIABLE_NAME + "' and '" + JSON_PATH + "'");
            }
            result.add(new RestCapture(variableName, jsonPath));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entryList(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("REST step parameter '" + key + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new StandTestException("REST step parameter '" + key + "' entries must be maps");
            }
            result.add((Map<String, Object>) item);
        }
        return result;
    }
}
