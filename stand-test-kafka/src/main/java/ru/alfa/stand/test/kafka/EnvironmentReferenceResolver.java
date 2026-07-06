package ru.alfa.stand.test.kafka;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link ReferenceResolver}: treats a reference as the name of an environment variable and
 * resolves it indirectly; the {@code ${NAME}}/{@code ${NAME:default}} placeholder spellings are
 * supported via {@link SecretReferences#resolve}.
 *
 * <p>This keeps broker addresses and secrets out of source (plan §9 / {@code KafkaClusterDefinition}:
 * the registry stores references, never values). Tests that need a literal value inject their own
 * {@link ReferenceResolver} via {@link KafkaStepExecutor}'s constructor rather than embedding values in
 * the registry, mirroring {@code EnvironmentBaseUrlResolver}.
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
            throw new StandTestException("Kafka cluster reference must not be blank");
        }
        String resolved = SecretReferences.resolve(reference, this.lookup);
        if (resolved == null || resolved.isBlank()) {
            if (SecretReferences.isLiteral(reference)) {
                throw new StandTestException("Kafka cluster setting is configured as a literal value but it is empty — an unset environment variable behind a ${VAR:} placeholder resolves to the empty default");
            }
            throw new StandTestException("Kafka cluster reference '" + reference + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }
}
