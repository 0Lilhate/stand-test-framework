package ru.alfa.stand.test.scenario;

import ru.alfa.stand.test.core.scenario.StepParameterKeys;

/**
 * Internal {@code GenericStep} parameter keys used by the YAML/AI parsers.
 *
 * <p>These now delegate to {@link StepParameterKeys} in {@code stand-test-core} — the single source of
 * truth also referenced by the adapter parameter schemas ({@code RestStepParameters} /
 * {@code DbStepParameters} / {@code KafkaStepParameters}). The class is kept as a package-local alias so
 * the translators read short names; a renamed key changes only the one core constant.
 */
final class YamlStepKeys {

    static final String METHOD = StepParameterKeys.METHOD;
    static final String SERVICE = StepParameterKeys.SERVICE;
    static final String PATH = StepParameterKeys.PATH;
    static final String QUERY = StepParameterKeys.QUERY;
    static final String HEADERS = StepParameterKeys.HEADERS;
    static final String BODY = StepParameterKeys.BODY;
    static final String BODY_RESOURCE = StepParameterKeys.BODY_RESOURCE;
    static final String INJECT_CORRELATION_ID = StepParameterKeys.INJECT_CORRELATION_ID;
    static final String EXPECTED_STATUS = StepParameterKeys.EXPECTED_STATUS;
    static final String ASSERTIONS = StepParameterKeys.ASSERTIONS;
    static final String CAPTURES = StepParameterKeys.CAPTURES;
    static final String JSON_PATH = StepParameterKeys.JSON_PATH;
    static final String EXPECTED_VALUE = StepParameterKeys.EXPECTED_VALUE;
    static final String MATCHER = StepParameterKeys.MATCHER;
    static final String VARIABLE_NAME = StepParameterKeys.VARIABLE_NAME;
    static final String COLUMN = StepParameterKeys.COLUMN;

    static final String TOPIC = StepParameterKeys.TOPIC;
    static final String KEY = StepParameterKeys.KEY;
    static final String CORRELATION_FROM_CONTEXT = StepParameterKeys.CORRELATION_FROM_CONTEXT;
    static final String TIMEOUT_MILLIS = StepParameterKeys.TIMEOUT_MILLIS;
    static final String POLL_TIMEOUT_MILLIS = StepParameterKeys.POLL_TIMEOUT_MILLIS;

    static final String DATASOURCE = StepParameterKeys.DATASOURCE;
    static final String SQL = StepParameterKeys.SQL;
    static final String SQL_RESOURCE = StepParameterKeys.SQL_RESOURCE;
    static final String PARAMS = StepParameterKeys.PARAMS;
    static final String WHERE_TEST_RUN_ID_COLUMN = StepParameterKeys.WHERE_TEST_RUN_ID_COLUMN;
    static final String SEED_TEST_RUN_ID_COLUMN = StepParameterKeys.SEED_TEST_RUN_ID_COLUMN;
    static final String POLL_INTERVAL_MILLIS = StepParameterKeys.POLL_INTERVAL_MILLIS;

    static final String TARGET = StepParameterKeys.TARGET;
    static final String METHOD_FULL_NAME = StepParameterKeys.METHOD_FULL_NAME;
    static final String DEADLINE_MILLIS = StepParameterKeys.DEADLINE_MILLIS;
    static final String REQUEST = StepParameterKeys.REQUEST;
    static final String REQUEST_RESOURCE = StepParameterKeys.REQUEST_RESOURCE;

    private YamlStepKeys() {
    }
}
