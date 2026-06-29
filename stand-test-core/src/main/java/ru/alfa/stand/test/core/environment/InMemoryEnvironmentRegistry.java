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

    /**
     * Creates a registry from the given environment definitions keyed by name.
     *
     * @param environments the environment definitions keyed by logical name
     */
    public InMemoryEnvironmentRegistry(Map<String, EnvironmentDefinition> environments) {
        this.environments = Map.copyOf(Objects.requireNonNull(environments, "environments must not be null"));
    }

    @Override
    public Optional<EnvironmentDefinition> environment(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(environments.get(name));
    }
}
