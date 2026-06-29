package ru.alfa.stand.test.kafka;

import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Default {@link ReferenceResolver}: treats a reference strictly as the name of an environment variable
 * and resolves it indirectly.
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
        String resolved = this.lookup.apply(reference);
        if (resolved == null || resolved.isBlank()) {
            throw new StandTestException("Kafka cluster reference '" + reference + "' did not resolve (environment variable not set)");
        }
        return resolved;
    }
}
