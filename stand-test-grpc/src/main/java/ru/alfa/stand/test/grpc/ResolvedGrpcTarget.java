package ru.alfa.stand.test.grpc;

/**
 * A gRPC target whose reference has already been resolved to an address, handed to a
 * {@link GrpcChannelFactory}.
 *
 * <p>The core {@link ru.alfa.stand.test.core.environment.GrpcTargetDefinition} stores a {@code targetRef}
 * (an environment-variable name), never a hardcoded address; the executor resolves it via a
 * {@link ReferenceResolver} before creating a channel, so addresses stay out of source (plan §9). The
 * {@code target} is a gRPC target string such as {@code host:port}.
 *
 * @param target the resolved gRPC target address (never blank), for example {@code host:port}
 */
public record ResolvedGrpcTarget(String target) {

    public ResolvedGrpcTarget {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
    }
}
