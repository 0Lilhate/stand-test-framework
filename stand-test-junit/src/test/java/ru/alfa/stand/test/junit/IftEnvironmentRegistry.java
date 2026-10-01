package ru.alfa.stand.test.junit;

import java.util.Map;
import java.util.Optional;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;

/**
 * Test {@link EnvironmentRegistry} discovered via {@link java.util.ServiceLoader}, whitelisting the
 * {@code ift} environment the run-fixtures execute against. Without a whitelisted environment the runner's
 * pre-execution guardrail rejects the scenario before any step runs.
 */
public final class IftEnvironmentRegistry implements EnvironmentRegistry {

    private final EnvironmentRegistry delegate = new InMemoryEnvironmentRegistry(
            Map.of("ift", new EnvironmentDefinition("ift", Map.of(), Map.of(), Map.of(), Map.of())), "ift");

    @Override
    public Optional<EnvironmentDefinition> environment(String name) {
        return delegate.environment(name);
    }

    @Override
    public Optional<String> defaultEnvironment() {
        return delegate.defaultEnvironment();
    }
}
