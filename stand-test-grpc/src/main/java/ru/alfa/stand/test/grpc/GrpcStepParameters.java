package ru.alfa.stand.test.grpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

/**
 * The shared parameter-map schema for a gRPC {@code ScenarioStep}.
 *
 * <p>{@link GrpcStep} writes these keys into a core {@code GenericStep}'s parameter map; the
 * {@link GrpcStepExecutor} reads them back. Keeping the key names and the read/write logic in one place
 * makes the parameter map a single, explicit contract that a future YAML/AI front-end can target without
 * sharing the typed Java builder (mirroring {@code KafkaStepParameters}). gRPC metadata reuses the shared
 * {@code headers} wire key, since metadata is the gRPC analogue of HTTP headers.
 *
 * <p>Reader methods are deliberately strict: a value of the wrong shape is a {@link StandTestException}
 * (a configuration error), never a silent default.
 */
public final class GrpcStepParameters {

    /** Prefix of the core step type produced for a gRPC step (for example {@code grpc.unary}). */
    public static final String TYPE_PREFIX = StepParameterKeys.GRPC_PREFIX;

    /** Parameter key: logical gRPC target alias resolved via the environment registry. */
    public static final String TARGET = StepParameterKeys.TARGET;
    /** Parameter key: fully-qualified gRPC method name ({@code package.Service/Method}). */
    public static final String METHOD_FULL_NAME = StepParameterKeys.METHOD_FULL_NAME;
    /** Parameter key: unary call deadline, in milliseconds. */
    public static final String DEADLINE_MILLIS = StepParameterKeys.DEADLINE_MILLIS;
    /** Parameter key: inline request payload (JSON as a string). */
    public static final String REQUEST = StepParameterKeys.REQUEST;
    /** Parameter key: classpath resource whose content is the request payload (JSON). */
    public static final String REQUEST_RESOURCE = StepParameterKeys.REQUEST_RESOURCE;
    /** Parameter key: request metadata as a string-to-string map (the gRPC analogue of headers). */
    public static final String METADATA = StepParameterKeys.HEADERS;
    /** Parameter key: whether to inject the SDK-owned correlation id into the outbound metadata. */
    public static final String INJECT_CORRELATION_ID = StepParameterKeys.INJECT_CORRELATION_ID;
    /** Parameter key: list of JSONPath assertions against the response JSON. */
    public static final String ASSERTIONS = StepParameterKeys.ASSERTIONS;
    /** Parameter key: list of captures from the response JSON. */
    public static final String CAPTURES = StepParameterKeys.CAPTURES;

    /** Nested key (assertion / capture): JSONPath expression. */
    public static final String JSON_PATH = StepParameterKeys.JSON_PATH;
    /** Nested key (assertion): expected value. */
    public static final String EXPECTED_VALUE = StepParameterKeys.EXPECTED_VALUE;
    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = StepParameterKeys.VARIABLE_NAME;

    private GrpcStepParameters() {
    }

    static String requireString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("gRPC step parameter '" + key + "' must be a non-blank string");
        }
        return text;
    }

    static Optional<String> optionalString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("gRPC step parameter '" + key + "' must be a string");
        }
        return Optional.of(text);
    }

    static boolean flag(Map<String, Object> parameters, String key) {
        return Boolean.TRUE.equals(parameters.get(key));
    }

    static long requirePositiveMillis(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            throw new StandTestException("gRPC step parameter '" + key + "' is required (a positive number of milliseconds)");
        }
        if (!(value instanceof Number number)) {
            throw new StandTestException("gRPC step parameter '" + key + "' must be a number of milliseconds");
        }
        long millis = number.longValue();
        if (millis <= 0) {
            throw new StandTestException("gRPC step parameter '" + key + "' must be a positive number of milliseconds");
        }
        return millis;
    }

    static Map<String, String> stringMap(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException("gRPC step parameter '" + key + "' must be a map");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    static List<GrpcAssertion> assertions(Map<String, Object> parameters) {
        List<GrpcAssertion> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, ASSERTIONS)) {
            Object path = entry.get(JSON_PATH);
            Object expected = entry.get(EXPECTED_VALUE);
            if (!(path instanceof String text)) {
                throw new StandTestException("gRPC assertion '" + JSON_PATH + "' must be a string");
            }
            if (expected == null) {
                throw new StandTestException("gRPC assertion '" + EXPECTED_VALUE + "' must not be null");
            }
            result.add(new GrpcAssertion(text, expected));
        }
        return result;
    }

    static List<GrpcCapture> captures(Map<String, Object> parameters) {
        List<GrpcCapture> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, CAPTURES)) {
            Object name = entry.get(VARIABLE_NAME);
            Object path = entry.get(JSON_PATH);
            if (!(name instanceof String variableName) || !(path instanceof String jsonPath)) {
                throw new StandTestException("gRPC capture requires string '" + VARIABLE_NAME + "' and '" + JSON_PATH + "'");
            }
            result.add(new GrpcCapture(variableName, jsonPath));
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
            throw new StandTestException("gRPC step parameter '" + key + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new StandTestException("gRPC step parameter '" + key + "' entries must be maps");
            }
            result.add((Map<String, Object>) item);
        }
        return result;
    }
}
