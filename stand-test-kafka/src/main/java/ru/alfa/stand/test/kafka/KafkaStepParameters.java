package ru.alfa.stand.test.kafka;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The shared parameter-map schema for a Kafka {@code ScenarioStep}.
 *
 * <p>{@link KafkaStep} writes these keys into a core {@code GenericStep}'s parameter map; the
 * {@link KafkaStepExecutor} reads them back. Keeping the key names and the read/write logic in one place
 * makes the parameter map a single, explicit contract that a future YAML front-end can target without
 * sharing the typed Java builder (mirroring {@code RestStepParameters}).
 *
 * <p>Reader methods are deliberately strict: a value of the wrong shape is a {@link StandTestException}
 * (a configuration error), never a silent default.
 */
public final class KafkaStepParameters {

    /** Prefix of the core step type produced for a Kafka step (for example {@code kafka.send}). */
    public static final String TYPE_PREFIX = "kafka.";

    /** Parameter key: logical topic alias resolved via the environment registry. */
    public static final String TOPIC = "topic";
    /** Parameter key: inline message value (JSON as a string). */
    public static final String BODY = "body";
    /** Parameter key: classpath resource whose content is the message value. */
    public static final String BODY_RESOURCE = "bodyResource";
    /** Parameter key: message key (partitioning key / discriminator). */
    public static final String KEY = "key";
    /** Parameter key: message headers as a string-to-string map. */
    public static final String HEADERS = "headers";
    /** Parameter key (send): whether to inject the SDK correlation id into the outbound message. */
    public static final String INJECT_CORRELATION_ID = "injectCorrelationId";
    /** Parameter key (expect): whether to select messages by the SDK-owned correlation id. */
    public static final String CORRELATION_FROM_CONTEXT = "correlationIdFromContext";
    /** Parameter key (expect): maximum time to wait for a matching message, in milliseconds. */
    public static final String TIMEOUT_MILLIS = "timeoutMillis";
    /** Parameter key (expect): the per-probe consumer poll timeout, in milliseconds. */
    public static final String POLL_TIMEOUT_MILLIS = "pollTimeoutMillis";
    /** Parameter key (expect): list of JSONPath assertions against the matched message value. */
    public static final String ASSERTIONS = "assertions";
    /** Parameter key (expect): list of captures from the matched message value. */
    public static final String CAPTURES = "captures";

    /** Nested key (assertion / capture): JSONPath expression. */
    public static final String JSON_PATH = "jsonPath";
    /** Nested key (assertion): expected value. */
    public static final String EXPECTED_VALUE = "expectedValue";
    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = "variableName";

    /** Default {@code kafka.expect} timeout when none is set, in milliseconds. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    /** Default per-probe consumer poll timeout when none is set, in milliseconds. */
    public static final long DEFAULT_POLL_TIMEOUT_MILLIS = 500L;

    private KafkaStepParameters() {
    }

    static String requireString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("Kafka step parameter '" + key + "' must be a non-blank string");
        }
        return text;
    }

    static Optional<String> optionalString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("Kafka step parameter '" + key + "' must be a string");
        }
        return Optional.of(text);
    }

    static boolean flag(Map<String, Object> parameters, String key) {
        return Boolean.TRUE.equals(parameters.get(key));
    }

    static long positiveMillis(Map<String, Object> parameters, String key, long defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)) {
            throw new StandTestException("Kafka step parameter '" + key + "' must be a number of milliseconds");
        }
        long millis = number.longValue();
        if (millis <= 0) {
            throw new StandTestException("Kafka step parameter '" + key + "' must be a positive number of milliseconds");
        }
        return millis;
    }

    static Map<String, String> stringMap(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException("Kafka step parameter '" + key + "' must be a map");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    static List<KafkaAssertion> assertions(Map<String, Object> parameters) {
        List<KafkaAssertion> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, ASSERTIONS)) {
            Object path = entry.get(JSON_PATH);
            Object expected = entry.get(EXPECTED_VALUE);
            if (!(path instanceof String text)) {
                throw new StandTestException("Kafka assertion '" + JSON_PATH + "' must be a string");
            }
            if (expected == null) {
                throw new StandTestException("Kafka assertion '" + EXPECTED_VALUE + "' must not be null");
            }
            result.add(new KafkaAssertion(text, expected));
        }
        return result;
    }

    static List<KafkaCapture> captures(Map<String, Object> parameters) {
        List<KafkaCapture> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, CAPTURES)) {
            Object name = entry.get(VARIABLE_NAME);
            Object path = entry.get(JSON_PATH);
            if (!(name instanceof String variableName) || !(path instanceof String jsonPath)) {
                throw new StandTestException("Kafka capture requires string '" + VARIABLE_NAME + "' and '" + JSON_PATH + "'");
            }
            result.add(new KafkaCapture(variableName, jsonPath));
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
            throw new StandTestException("Kafka step parameter '" + key + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new StandTestException("Kafka step parameter '" + key + "' entries must be maps");
            }
            result.add((Map<String, Object>) item);
        }
        return result;
    }
}
