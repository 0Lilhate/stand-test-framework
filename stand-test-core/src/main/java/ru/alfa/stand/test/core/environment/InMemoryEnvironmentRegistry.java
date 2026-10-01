package ru.alfa.stand.test.core.environment;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory {@link EnvironmentRegistry} backed by a fixed map of definitions.
 *
 * <p>This performs no file or config parsing — it is a plain immutable data holder constructed from
 * already-resolved definitions. The map is defensively copied.
 */
public final class InMemoryEnvironmentRegistry implements EnvironmentRegistry {

    private final Map<String, EnvironmentDefinition> environments;
    private final String defaultEnvironment;

    /**
     * Creates a registry from the given environment definitions keyed by name.
     *
     * @param environments the environment definitions keyed by logical name
     */
    public InMemoryEnvironmentRegistry(Map<String, EnvironmentDefinition> environments) {
        this(environments, null);
    }

    /** Creates a registry with an optional default environment, validated at construction. */
    public InMemoryEnvironmentRegistry(Map<String, EnvironmentDefinition> environments, String defaultEnvironment) {
        this.environments = Map.copyOf(Objects.requireNonNull(environments, "environments must not be null"));
        if (defaultEnvironment != null && !this.environments.containsKey(defaultEnvironment)) {
            throw new IllegalArgumentException("Default environment '" + defaultEnvironment + "' is not declared in the registry");
        }
        this.defaultEnvironment = defaultEnvironment;
    }

    @Override
    public Optional<EnvironmentDefinition> environment(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(environments.get(name));
    }

    @Override
    public Optional<String> defaultEnvironment() {
        return Optional.ofNullable(defaultEnvironment);
    }
}
