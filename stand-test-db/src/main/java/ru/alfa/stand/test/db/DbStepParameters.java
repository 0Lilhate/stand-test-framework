package ru.alfa.stand.test.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.StepParameterKeys;

/**
 * The shared parameter-map schema for a DB {@code ScenarioStep}.
 *
 * <p>{@link DbStep} writes these keys into a core {@code GenericStep}'s parameter map; the
 * {@link DbStepExecutor} reads them back. Keeping the key names and the read/write logic in one place
 * makes the parameter map a single, explicit contract that a future YAML front-end can target without
 * sharing the typed Java builder (mirroring {@code RestStepParameters} / {@code KafkaStepParameters}).
 *
 * <p>Reader methods are deliberately strict: a value of the wrong shape is a {@link StandTestException}
 * (a configuration error), never a silent default.
 */
public final class DbStepParameters {

    /** Prefix of the core step type produced for a DB step (for example {@code db.query}). */
    public static final String TYPE_PREFIX = StepParameterKeys.DB_PREFIX;

    /** Parameter key: logical datasource alias resolved via the environment registry. */
    public static final String DATASOURCE = StepParameterKeys.DATASOURCE;
    /** Parameter key: inline SQL (a single statement). */
    public static final String SQL = StepParameterKeys.SQL;
    /** Parameter key: classpath resource whose content is the SQL (a single statement). */
    public static final String SQL_RESOURCE = StepParameterKeys.SQL_RESOURCE;
    /** Parameter key: named bind values as a name-to-value map. */
    public static final String PARAMS = StepParameterKeys.PARAMS;
    /** Parameter key (query): list of column captures into the variable store. */
    public static final String CAPTURES = StepParameterKeys.CAPTURES;
    /** Parameter key (expectEventually): the single value the first column must eventually equal. */
    public static final String EXPECTED_VALUE = StepParameterKeys.EXPECTED_VALUE;
    /** Parameter key (cleanup / optional): the column the appended {@code testRunId} predicate binds. */
    public static final String WHERE_TEST_RUN_ID_COLUMN = StepParameterKeys.WHERE_TEST_RUN_ID_COLUMN;
    /** Parameter key (seed): the column a seed INSERT must tag with {@code :testRunId} so its rows are reaped by cleanup. */
    public static final String SEED_TEST_RUN_ID_COLUMN = StepParameterKeys.SEED_TEST_RUN_ID_COLUMN;
    /** Parameter key (db.write): the primary-key column(s) identifying the written row for undo-log compensation. */
    public static final String IDENTIFIED_BY = StepParameterKeys.IDENTIFIED_BY;
    /** Parameter key (expectEventually): maximum time to wait for a match, in milliseconds. */
    public static final String TIMEOUT_MILLIS = StepParameterKeys.TIMEOUT_MILLIS;
    /** Parameter key (expectEventually): the poll interval between probes, in milliseconds. */
    public static final String POLL_INTERVAL_MILLIS = StepParameterKeys.POLL_INTERVAL_MILLIS;

    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = StepParameterKeys.VARIABLE_NAME;
    /** Nested key (capture): result-set column label. */
    public static final String COLUMN = StepParameterKeys.COLUMN;

    /** Default {@code db.expectEventually} timeout when none is set, in milliseconds. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    /** Default poll interval between probes when none is set, in milliseconds. */
    public static final long DEFAULT_POLL_INTERVAL_MILLIS = 200L;

    private DbStepParameters() {
    }

    static String requireString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("DB step parameter '" + key + "' must be a non-blank string");
        }
        return text;
    }

    static Optional<String> optionalString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("DB step parameter '" + key + "' must be a string");
        }
        return Optional.of(text);
    }

    static Object requireExpectedValue(Map<String, Object> parameters) {
        Object value = parameters.get(EXPECTED_VALUE);
        if (value == null) {
            throw new StandTestException("A db.expectEventually step requires an expected value (expectValue(...))");
        }
        return value;
    }

    static long positiveMillis(Map<String, Object> parameters, String key, long defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        long millis;
        if (value instanceof Long longMillis) {
            millis = longMillis;
        } else if (value instanceof Integer intMillis) {
            millis = intMillis;
        } else {
            throw new StandTestException("DB step parameter '" + key + "' must be a whole number of milliseconds (Integer or Long)");
        }
        if (millis <= 0) {
            throw new StandTestException("DB step parameter '" + key + "' must be a positive number of milliseconds");
        }
        return millis;
    }

    static Map<String, Object> bindValues(Map<String, Object> parameters) {
        Object value = parameters.get(PARAMS);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException("DB step parameter '" + PARAMS + "' must be a map");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getValue() == null) {
                throw new StandTestException("DB bind value for ':" + entry.getKey() + "' must not be null");
            }
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    static List<String> identifiedBy(Map<String, Object> parameters) {
        Object value = parameters.get(IDENTIFIED_BY);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("DB step parameter '" + IDENTIFIED_BY + "' must be a list of column names");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String column) || column.isBlank()) {
                throw new StandTestException("DB step parameter '" + IDENTIFIED_BY + "' entries must be non-blank column names");
            }
            if (!SqlIdentifiers.isPlainIdentifier(column)) {
                throw new StandTestException("DB step parameter '" + IDENTIFIED_BY + "' entry '" + column + "' must be a plain identifier");
            }
            result.add(column);
        }
        return List.copyOf(result);
    }

    static List<DbCapture> captures(Map<String, Object> parameters) {
        List<DbCapture> result = new ArrayList<>();
        for (Map<String, Object> entry : entryList(parameters, CAPTURES)) {
            Object name = entry.get(VARIABLE_NAME);
            Object column = entry.get(COLUMN);
            if (!(name instanceof String variableName) || !(column instanceof String columnLabel)) {
                throw new StandTestException("DB capture requires string '" + VARIABLE_NAME + "' and '" + COLUMN + "'");
            }
            result.add(new DbCapture(variableName, columnLabel));
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
            throw new StandTestException("DB step parameter '" + key + "' must be a list");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) {
                throw new StandTestException("DB step parameter '" + key + "' entries must be maps");
            }
            result.add((Map<String, Object>) item);
        }
        return result;
    }
}
