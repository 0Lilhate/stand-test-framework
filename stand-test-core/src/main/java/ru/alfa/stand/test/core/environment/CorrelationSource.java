package ru.alfa.stand.test.core.environment;

/**
 * Where a correlation id is carried for a given transport.
 */
public enum CorrelationSource {

    /** Carried in an HTTP header. */
    HEADER,

    /** Carried as the message key. */
    KEY,

    /** Carried in a payload field. */
    PAYLOAD_FIELD,

    /** Carried in transport metadata (for example gRPC metadata). */
    METADATA
}
