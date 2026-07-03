package ru.alfa.stand.test.junit;

import java.util.Objects;
import java.util.Optional;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Fallback {@link EnvironmentRegistry} used by {@link StandTestExtension} when the {@link
 * java.util.ServiceLoader} finds no provider on the test classpath.
 *
 * <p>An empty registry would make every scenario fail the whitelist guardrail with
 * "Environment '...' is not whitelisted" — a message that points at the scenario, not at the real
 * cause (no registry configured at all). Because {@link EnvironmentRegistry} is a pure lookup with no
 * diagnostic channel, this implementation raises the distinct, actionable diagnostic itself: any
 * lookup throws a {@link StandTestException} (a configuration error, plan §8.3) naming the missing
 * provider and how to supply one.
 */
final class NoProviderEnvironmentRegistry implements EnvironmentRegistry {

    @Override
    public Optional<EnvironmentDefinition> environment(String name) {
        Objects.requireNonNull(name, "name must not be null");
        throw new StandTestException("No EnvironmentRegistry provider found on the test classpath, so environment '" + name
                + "' cannot be resolved. Register a provider via META-INF/services/ru.alfa.stand.test.core.environment.EnvironmentRegistry"
                + " (for example add the stand-test-config module with a stand-test-environments.yml) so the SDK knows the whitelisted environments.");
    }
}
