package ru.alfa.stand.test.core.scenario;

/**
 * Single source of truth for the {@link GenericStep} parameter-map wire keys and step-type prefixes.
 *
 * <p>The adapter parameter schemas ({@code RestStepParameters} / {@code KafkaStepParameters} /
 * {@code DbStepParameters}) and the {@code scenario-yaml} parser reference these constants, so the wire
 * contract has one authoritative definition instead of literals duplicated across modules. Core owns the
 * contract; the adapters own the read/write logic keyed by it and the runner's guardrail validator reads
 * the environment-facing aliases (service/topic/datasource) and SQL through the same keys.
 */
public final class StepParameterKeys {

    /** Prefix of the core step type produced for a REST step (for example {@code rest.get}). */
    public static final String REST_PREFIX = "rest.";
    /** Prefix of the core step type produced for a Kafka step (for example {@code kafka.send}). */
    public static final String KAFKA_PREFIX = "kafka.";
    /** Prefix of the core step type produced for a DB step (for example {@code db.query}). */
    public static final String DB_PREFIX = "db.";
    /** Prefix of the core step type produced for a gRPC step (for example {@code grpc.unary}). */
    public static final String GRPC_PREFIX = "grpc.";

    /** Parameter key: HTTP method name. */
    public static final String METHOD = "method";
    /** Parameter key: logical service alias resolved via the environment registry. */
    public static final String SERVICE = "service";
    /** Parameter key: request path appended to the resolved base URL. */
    public static final String PATH = "path";
    /** Parameter key: query parameters as a string-to-string map. */
    public static final String QUERY = "query";
    /** Parameter key: headers as a string-to-string map. */
    public static final String HEADERS = "headers";
    /** Parameter key: inline body / message value. */
    public static final String BODY = "body";
    /** Parameter key: classpath resource whose content is the body / message value. */
    public static final String BODY_RESOURCE = "bodyResource";
    /** Parameter key: whether to inject the SDK correlation id into the outbound message/request. */
    public static final String INJECT_CORRELATION_ID = "injectCorrelationId";
    /** Parameter key: expected HTTP status code. */
    public static final String EXPECTED_STATUS = "expectedStatus";
    /** Parameter key: list of JSONPath assertions. */
    public static final String ASSERTIONS = "assertions";
    /** Parameter key: list of captures. */
    public static final String CAPTURES = "captures";

    /** Nested key (assertion / capture): JSONPath expression. */
    public static final String JSON_PATH = "jsonPath";
    /** Nested key (assertion / expectEventually): expected value. */
    public static final String EXPECTED_VALUE = "expectedValue";
    /** Nested key (capture): target variable name. */
    public static final String VARIABLE_NAME = "variableName";
    /** Nested key (DB capture): result-set column label. */
    public static final String COLUMN = "column";

    /** Parameter key: logical topic alias resolved via the environment registry. */
    public static final String TOPIC = "topic";
    /** Parameter key: message key (partitioning / selection). */
    public static final String KEY = "key";
    /** Parameter key: whether to select an expected message by the SDK correlation id. */
    public static final String CORRELATION_FROM_CONTEXT = "correlationIdFromContext";
    /** Parameter key: maximum time to wait for a match, in milliseconds. */
    public static final String TIMEOUT_MILLIS = "timeoutMillis";
    /** Parameter key: per-probe poll timeout, in milliseconds. */
    public static final String POLL_TIMEOUT_MILLIS = "pollTimeoutMillis";

    /** Parameter key: logical datasource alias resolved via the environment registry. */
    public static final String DATASOURCE = "datasource";
    /** Parameter key: inline SQL (a single statement). */
    public static final String SQL = "sql";
    /** Parameter key: classpath resource whose content is the SQL (a single statement). */
    public static final String SQL_RESOURCE = "sqlResource";
    /** Parameter key: named bind values as a name-to-value map. */
    public static final String PARAMS = "params";
    /** Parameter key (cleanup / optional): the column the appended {@code testRunId} predicate binds. */
    public static final String WHERE_TEST_RUN_ID_COLUMN = "whereTestRunIdColumn";
    /** Parameter key (expectEventually): the poll interval between probes, in milliseconds. */
    public static final String POLL_INTERVAL_MILLIS = "pollIntervalMillis";

    /** Parameter key: logical gRPC target alias resolved via the environment registry. */
    public static final String TARGET = "target";
    /** Parameter key: fully-qualified gRPC method name ({@code package.Service/Method}). */
    public static final String METHOD_FULL_NAME = "methodFullName";
    /** Parameter key: unary call deadline, in milliseconds. */
    public static final String DEADLINE_MILLIS = "deadlineMillis";
    /** Parameter key: inline request payload (JSON as a string). */
    public static final String REQUEST = "request";
    /** Parameter key: classpath resource whose content is the request payload (JSON). */
    public static final String REQUEST_RESOURCE = "requestResource";

    private StepParameterKeys() {
    }
}
