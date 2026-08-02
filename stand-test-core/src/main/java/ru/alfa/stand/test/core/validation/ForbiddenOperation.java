package ru.alfa.stand.test.core.validation;

/**
 * Single source of truth for operations the SDK forbids.
 *
 * <p>The runtime {@link ScenarioValidator} and the future AI schema must both derive their
 * constraints from this enum, so the two cannot drift apart. Each constant carries a stable code and
 * a human-readable description.
 */
public enum ForbiddenOperation {

    /** Use of {@code Thread.sleep} or fixed delays instead of the shared await mechanism. */
    THREAD_SLEEP("THREAD_SLEEP", "Use of Thread.sleep or fixed delays instead of the shared await mechanism"),

    /** Hardcoded test-data identifiers instead of generated or captured values. */
    FIXED_TEST_DATA_ID("FIXED_TEST_DATA_ID", "Hardcoded test-data identifiers instead of generated or captured values"),

    /** A hardcoded stand URL instead of a whitelisted environment alias. */
    HARDCODED_STAND_URL("HARDCODED_STAND_URL", "Hardcoded stand URL instead of a whitelisted environment alias"),

    /** A secret value embedded in code or YAML instead of a secret reference. */
    SECRET_IN_SOURCE("SECRET_IN_SOURCE", "Secret value embedded in code or YAML instead of a secret reference"),

    /** A raw Kafka consumer or producer in a test, bypassing the SDK. */
    RAW_KAFKA_CLIENT("RAW_KAFKA_CLIENT", "Raw Kafka consumer or producer in a test bypassing the SDK"),

    /** Raw JDBC usage in a test, bypassing the SDK. */
    RAW_JDBC_CLIENT("RAW_JDBC_CLIENT", "Raw JDBC usage in a test bypassing the SDK"),

    /** Connecting to an environment that is not whitelisted. */
    NON_WHITELISTED_ENVIRONMENT("NON_WHITELISTED_ENVIRONMENT", "Connecting to an environment that is not whitelisted"),

    /** Connecting to a datasource that is not whitelisted. */
    NON_WHITELISTED_DATASOURCE("NON_WHITELISTED_DATASOURCE", "Connecting to a datasource that is not whitelisted"),

    /** Calling a REST service alias that is not whitelisted in the environment. */
    NON_WHITELISTED_SERVICE("NON_WHITELISTED_SERVICE", "Calling a service alias that is not whitelisted"),

    /** Using a Kafka topic alias that is not whitelisted in the environment. */
    NON_WHITELISTED_TOPIC("NON_WHITELISTED_TOPIC", "Using a topic alias that is not whitelisted"),

    /** Calling a gRPC target alias that is not whitelisted in the environment. */
    NON_WHITELISTED_GRPC_TARGET("NON_WHITELISTED_GRPC_TARGET", "Calling a gRPC target alias that is not whitelisted"),

    /** Opening a UI application alias that is not whitelisted in the environment. */
    NON_WHITELISTED_UI_APPLICATION("NON_WHITELISTED_UI_APPLICATION", "Opening a UI application alias that is not whitelisted"),

    /** Destructive SQL without an explicit write/destructive allow flag. */
    DESTRUCTIVE_SQL_WITHOUT_ALLOW("DESTRUCTIVE_SQL_WITHOUT_ALLOW", "Destructive SQL without an explicit write or destructive allow flag"),

    /** A missing, non-positive, non-integer or effectively-unbounded timeout/deadline. */
    UNBOUNDED_TIMEOUT("UNBOUNDED_TIMEOUT", "Missing or effectively unbounded timeout or deadline instead of a bounded declarative wait"),

    /** Concrete-service business logic placed inside the SDK. */
    BUSINESS_LOGIC_IN_SDK("BUSINESS_LOGIC_IN_SDK", "Concrete-service business logic placed inside the SDK"),

    /** Imperative eager-IO Java DSL that bypasses the Scenario Validator. */
    IMPERATIVE_EAGER_IO("IMPERATIVE_EAGER_IO", "Imperative eager-IO Java DSL that bypasses the Scenario Validator");

    private final String code;
    private final String description;

    ForbiddenOperation(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
