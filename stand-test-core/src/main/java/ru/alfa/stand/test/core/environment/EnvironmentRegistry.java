package ru.alfa.stand.test.core.environment;

import java.util.Optional;

/**
 * Resolves a logical environment name to its {@link EnvironmentDefinition}.
 *
 * <p>This is the central point where the stand whitelist is applied: scenarios reference logical
 * aliases, and only environments present in the registry can be resolved. Implementations are
 * expected to be backed by whitelisted configuration; this module ships only the contract plus a
 * zero-IO in-memory implementation.
 */
public interface EnvironmentRegistry {

    /**
     * Resolves the environment with the given name.
     *
     * @param name the logical environment name
     * @return the environment definition, or empty if not whitelisted
     */
    Optional<EnvironmentDefinition> environment(String name);

    /** Returns the configured default, if this registry declares one. */
    default Optional<String> defaultEnvironment() {
        return Optional.empty();
    }
}
