package ru.alfa.stand.test.kafka;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

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
    public static final String TYPE_PREFIX = StepParameterKeys.KAFKA_PREFIX;

    /** Parameter key: logical topic alias resolved via the environment registry. */
    public static final String TOPIC = StepParameterKeys.TOPIC;
    /** Parameter key: inline message value (JSON as a string). */
    public static final String BODY = StepParameterKeys.BODY;
    /** Parameter key: classpath resource whose content is the message value. */
    public static final String BODY_RESOURCE = StepParameterKeys.BODY_RESOURCE;
    /** Parameter key: message key (partitioning key / discriminator). */
    public static final String KEY = StepParameterKeys.KEY;
    /** Parameter key: message headers as a string-to-string map. */
    public static final String HEADERS = StepParameterKeys.HEADERS;
    /** Parameter key (send): whether to inject the SDK correlation id into the outbound message. */
    public static final String INJECT_CORRELATION_ID = StepParameterKeys.INJECT_CORRELATION_ID;
    /** Parameter key (expect): whether to select messages by the SDK-owned correlation id. */
    public static final String CORRELATION_FROM_CONTEXT = StepParameterKeys.CORRELATION_FROM_CONTEXT;
    /** Parameter key (expect): maximum time to wait for a matching message, in milliseconds. */
    public static final String TIMEOUT_MILLIS = StepParameterKeys.TIMEOUT_MILLIS;
    /** Parameter key (expect): the per-probe consumer poll timeout, in milliseconds. */
    public static final String POLL_TIMEOUT_MILLIS = StepParameterKeys.POLL_TIMEOUT_MILLIS;
    /** Parameter key (expect): list of JSONPath assertions against the matched message value. */
    public static final String ASSERTIONS = StepParameterKeys.ASSERTIONS;
    /** Parameter key (expect): list of captures from the matched message value. */
    public static final String CAPTURES = StepParameterKeys.CAPTURES;

    /** Nested key (assertion / capture): JSONPath expression. */
    public static final String JSON_PATH = StepParameterKeys.JSON_PATH;
    /** Nested key (assertion): expected value. */
    public static final String EXPECTED_VALUE = StepParameterKeys.EXPECTED_VALUE;
    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = StepParameterKeys.VARIABLE_NAME;

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

    /**
     * The explicit correlation-injection choice, if the {@code kafka.send} step set one:
     * {@code Optional.of(true)} to force injection, {@code Optional.of(false)} to opt out,
     * {@code Optional.empty()} when unset (the executor then defaults to injecting when the topic declares a
     * HEADER correlation carrier).
     */
    static Optional<Boolean> injectCorrelationIdFlag(Map<String, Object> parameters) {
        Object value = parameters.get(INJECT_CORRELATION_ID);
        return (value instanceof Boolean flag) ? Optional.of(flag) : Optional.empty();
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
        return entryList(parameters, ASSERTIONS).stream()
                .map(KafkaStepParameters::assertion)
                .toList();
    }

    /**
     * Reads one assertion. There is no matcher key: {@code kafka.expect} runs EQUALS only, so an absent
     * wire matcher is the only spelling — see {@code MessageAssertions}, which delegates to core's
     * {@code AssertionMatchers.equalsMatch}.
     */
    private static KafkaAssertion assertion(Map<String, Object> entry) {
        Object path = entry.get(JSON_PATH);
        Object expected = entry.get(EXPECTED_VALUE);
        if (!(path instanceof String text)) {
            throw new StandTestException("Kafka assertion '" + JSON_PATH + "' must be a string");
        }
        if (expected == null) {
            throw new StandTestException("Kafka assertion '" + EXPECTED_VALUE + "' must not be null");
        }
        return new KafkaAssertion(text, expected);
    }

    static List<KafkaCapture> captures(Map<String, Object> parameters) {
        return entryList(parameters, CAPTURES).stream()
                .map(entry -> {
                    Object name = entry.get(VARIABLE_NAME);
                    Object path = entry.get(JSON_PATH);
                    if (!(name instanceof String variableName) || !(path instanceof String jsonPath)) {
                        throw new StandTestException("Kafka capture requires string '" + VARIABLE_NAME + "' and '" + JSON_PATH + "'");
                    }
                    return new KafkaCapture(variableName, jsonPath);
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
            throw new StandTestException("Kafka step parameter '" + key + "' must be a list");
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?>)) {
                        throw new StandTestException("Kafka step parameter '" + key + "' entries must be maps");
                    }
                    return (Map<String, Object>) item;
                })
                .toList();
    }
}
