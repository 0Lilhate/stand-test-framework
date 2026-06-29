package ru.alfa.stand.test.core.environment;

/**
 * Definition of a logical gRPC target alias.
 *
 * <p>{@code targetRef} is a reference (for example an environment-variable name) resolving to the
 * target {@code host:port} at runtime — never a hardcoded address.
 *
 * @param alias the logical gRPC target alias (never blank)
 * @param targetRef a reference resolving to the target address (never blank)
 * @param correlation how the correlation id is carried in metadata (may be null)
 */
public record GrpcTargetDefinition(String alias, String targetRef, CorrelationConfig correlation) {

    public GrpcTargetDefinition {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("grpc target alias must not be blank");
        }
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef must not be blank");
        }
    }
}
