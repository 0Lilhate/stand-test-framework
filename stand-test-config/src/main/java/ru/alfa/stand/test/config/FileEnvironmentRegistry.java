package ru.alfa.stand.test.config;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;

/**
 * {@link EnvironmentRegistry} SPI provider backed by {@link YamlEnvironmentConfigLoader}.
 *
 * <p>Registered in {@code META-INF/services}, so a plain-JUnit {@code StandTestExtension} discovers it via
 * {@link java.util.ServiceLoader} and gets a populated registry from the config file — no wiring code.
 *
 * <p>Loading is <strong>lazy</strong> (on first environment or default-environment lookup) and cached: this keeps
 * {@code ServiceLoader} discovery free of file IO, so a malformed config surfaces at first use rather than
 * breaking service discovery with a {@code ServiceConfigurationError}. The class must have a public no-arg
 * constructor for the SPI.
 */
public final class FileEnvironmentRegistry implements EnvironmentRegistry {

    private final Supplier<EnvironmentRegistry> loader;
    private volatile EnvironmentRegistry delegate;

    /**
     * Creates a provider that lazily loads from the default {@link YamlEnvironmentConfigLoader}.
     */
    public FileEnvironmentRegistry() {
        this(new YamlEnvironmentConfigLoader()::load);
    }

    /**
     * Creates a provider with an explicit registry supplier (for tests).
     *
     * @param loader supplies the delegate registry; invoked at most once, lazily
     */
    FileEnvironmentRegistry(Supplier<EnvironmentRegistry> loader) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    @Override
    public Optional<EnvironmentDefinition> environment(String name) {
        return delegate().environment(name);
    }

    @Override
    public Optional<String> defaultEnvironment() {
        return delegate().defaultEnvironment();
    }

    private EnvironmentRegistry delegate() {
        EnvironmentRegistry local = this.delegate;
        if (local == null) {
            synchronized (this) {
                local = this.delegate;
                if (local == null) {
                    local = this.loader.get();
                    this.delegate = local;
                }
            }
        }
        return local;
    }
}
