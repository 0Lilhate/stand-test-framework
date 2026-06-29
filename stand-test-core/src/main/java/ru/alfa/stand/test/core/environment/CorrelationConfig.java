package ru.alfa.stand.test.core.environment;

import java.util.Objects;

/**
 * How the SDK-owned correlation id is injected/matched for a transport: a source plus the carrier
 * name (for example header name {@code X-Correlation-Id}).
 *
 * @param source where the correlation id is carried
 * @param name the carrier name (header/key/field/metadata key), never blank
 */
public record CorrelationConfig(CorrelationSource source, String name) {

    public CorrelationConfig {
        Objects.requireNonNull(source, "source must not be null");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("correlation name must not be blank");
        }
    }
}
