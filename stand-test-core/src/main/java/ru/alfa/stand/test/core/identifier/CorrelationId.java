package ru.alfa.stand.test.core.identifier;

import java.util.UUID;

/**
 * SDK-owned cross-system correlation id. Immutable, value-based, never blank.
 *
 * <p>The SDK generates this at the start of a scenario run and is expected to inject it into outbound
 * REST/Kafka/gRPC calls, so that asynchronous effects can be correlated back to the originating flow.
 *
 * @param value the non-blank identifier value
 */
public record CorrelationId(String value) {

    public CorrelationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CorrelationId value must not be blank");
        }
    }

    /**
     * Creates a correlation id from the given value.
     *
     * @param value the non-blank identifier value
     * @return a new correlation id
     */
    public static CorrelationId of(String value) {
        return new CorrelationId(value);
    }

    /**
     * Generates a new unique correlation id.
     *
     * @return a freshly generated correlation id
     */
    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
