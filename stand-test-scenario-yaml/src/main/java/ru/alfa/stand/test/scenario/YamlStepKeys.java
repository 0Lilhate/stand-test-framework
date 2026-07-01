package ru.alfa.stand.test.scenario;

/**
 * Internal {@code GenericStep} parameter keys — a deliberate <strong>mirror</strong> of the adapters'
 * {@code RestStepParameters} / {@code DbStepParameters} / {@code KafkaStepParameters} constants.
 *
 * <p>The YAML engine is <strong>core-only</strong> (plan §4/§5): it must not have a compile-time edge to
 * the adapter modules, so it cannot reference their parameter-key constants directly. These wire keys are
 * therefore duplicated here as literals — they form an implicit contract between the parser and the
 * adapters. Keep them in sync; a renamed key in an adapter would silently break parsing.
 *
 * <p><strong>Follow-up (design doc):</strong> hoist the parameter-key constants into {@code stand-test-core}
 * so both the adapters and this parser reference a single source of truth, removing this duplication.
 */
final class YamlStepKeys {

    static final String METHOD = "method";
    static final String SERVICE = "service";
    static final String PATH = "path";
    static final String QUERY = "query";
    static final String HEADERS = "headers";
    static final String BODY = "body";
    static final String BODY_RESOURCE = "bodyResource";
    static final String INJECT_CORRELATION_ID = "injectCorrelationId";
    static final String EXPECTED_STATUS = "expectedStatus";
    static final String ASSERTIONS = "assertions";
    static final String CAPTURES = "captures";
    static final String JSON_PATH = "jsonPath";
    static final String EXPECTED_VALUE = "expectedValue";
    static final String VARIABLE_NAME = "variableName";
    static final String COLUMN = "column";

    static final String TOPIC = "topic";
    static final String KEY = "key";
    static final String CORRELATION_FROM_CONTEXT = "correlationIdFromContext";
    static final String TIMEOUT_MILLIS = "timeoutMillis";
    static final String POLL_TIMEOUT_MILLIS = "pollTimeoutMillis";

    static final String DATASOURCE = "datasource";
    static final String SQL = "sql";
    static final String SQL_RESOURCE = "sqlResource";
    static final String PARAMS = "params";
    static final String WHERE_TEST_RUN_ID_COLUMN = "whereTestRunIdColumn";
    static final String POLL_INTERVAL_MILLIS = "pollIntervalMillis";

    private YamlStepKeys() {
    }
}
