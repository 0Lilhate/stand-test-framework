package ru.alfa.stand.test.core.environment;

/**
 * Definition of a logical REST/gRPC service endpoint.
 *
 * <p>{@code baseUrlRef} is a reference (for example an environment-variable name) that resolves to a
 * base URL at runtime — never a hardcoded URL. {@code correlation} describes how the correlation id
 * is injected and may be null when the service does not participate in correlation. {@code auth}
 * carries the service-level authentication references (never values) and may be null when the
 * service needs no authentication.
 *
 * @param name the logical service alias (never blank)
 * @param baseUrlRef a reference resolving to the base URL (never blank)
 * @param correlation the correlation injection config (may be null)
 * @param auth the authentication config (may be null)
 */
public record ServiceEndpointDefinition(String name, String baseUrlRef, CorrelationConfig correlation, AuthConfig auth) {

    public ServiceEndpointDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("service name must not be blank");
        }
        if (baseUrlRef == null || baseUrlRef.isBlank()) {
            throw new IllegalArgumentException("baseUrlRef must not be blank");
        }
    }

    /**
     * Creates an endpoint definition without authentication.
     *
     * @param name the logical service alias (never blank)
     * @param baseUrlRef a reference resolving to the base URL (never blank)
     * @param correlation the correlation injection config (may be null)
     */
    public ServiceEndpointDefinition(String name, String baseUrlRef, CorrelationConfig correlation) {
        this(name, baseUrlRef, correlation, null);
    }
}
