package ru.alfa.stand.test.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileEnvironmentRegistryTest {

    @Test
    @DisplayName("is discovered via the core EnvironmentRegistry ServiceLoader")
    void discoveredViaServiceLoader() {
        boolean found = ServiceLoader.load(EnvironmentRegistry.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(FileEnvironmentRegistry.class::isInstance);
        assertThat(found).as("FileEnvironmentRegistry must be registered in META-INF/services").isTrue();
    }

    @Test
    @DisplayName("loads lazily on first lookup and caches the result")
    void loadsLazilyAndCaches() {
        AtomicInteger loads = new AtomicInteger();
        Supplier<EnvironmentRegistry> loader = () -> {
            loads.incrementAndGet();
            return new InMemoryEnvironmentRegistry(Map.of());
        };
        FileEnvironmentRegistry registry = new FileEnvironmentRegistry(loader);

        assertThat(loads.get()).as("no load at construction").isZero();
        registry.environment("ift");
        registry.environment("ift");
        assertThat(loads.get()).as("loaded exactly once, then cached").isEqualTo(1);
    }

    @Test
    @DisplayName("BR-01: the SPI provider exposes its loaded default environment")
    void exposesDefaultEnvironment() {
        EnvironmentDefinition ift = new EnvironmentDefinition("ift", Map.of(), Map.of(), Map.of(), Map.of());
        FileEnvironmentRegistry registry = new FileEnvironmentRegistry(
                () -> new InMemoryEnvironmentRegistry(Map.of("ift", ift), "ift"));

        assertThat(registry.defaultEnvironment()).contains("ift");
    }
}
