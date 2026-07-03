package ru.alfa.stand.test.grpc;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link ReferenceResolver}: treats a reference as the name of an environment variable and
 * resolves it indirectly; the {@code ${NAME}}/{@code ${NAME:default}} placeholder spellings are
 * supported via {@link SecretReferences#resolve}.
 *
 * <p>This keeps gRPC target addresses out of source (plan §9 / {@code GrpcTargetDefinition}: the registry
 * stores references, never addresses). Tests that need a literal value inject their own
 * {@link ReferenceResolver} via {@link GrpcStepExecutor}'s constructor rather than embedding addresses in
 * the registry, mirroring the Kafka adapter's {@code EnvironmentReferenceResolver}.
 */
public final class EnvironmentReferenceResolver implements ReferenceResolver {

    private final UnaryOperator<String> lookup;

    /**
     * Creates a resolver backed by the process environment ({@link System#getenv(String)}).
     */
    public EnvironmentReferenceResolver() {
        this(System::getenv);
    }

    /**
     * Creates a resolver backed by the given reference lookup (for tests).
     *
     * @param lookup resolves a reference name to its value (or null if unset)
     */
    EnvironmentReferenceResolver(UnaryOperator<String> lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup must not be null");
    }

    @Override
    public String resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new StandTestException("gRPC target reference must not be blank");
        }
        String resolved = SecretReferences.resolve(reference, this.lookup);
        if (resolved == null || resolved.isBlank()) {
            throw new StandTestException("gRPC target reference '" + reference + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }
}
