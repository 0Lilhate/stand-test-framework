package ru.alfa.stand.test.example;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;

/**
 * {@link EnvironmentRegistry} discovered via {@link java.util.ServiceLoader} (registered in
 * {@code META-INF/services}) so the {@code @StandTest} extension can resolve the example's {@code ift}
 * aliases without a hand-built runner. Must be public with a public no-arg constructor for the service
 * loader; because {@link InMemoryEnvironmentRegistry} is {@code final}, this provider <em>delegates</em>
 * to one rather than subclassing.
 *
 * <p>Unlike the manual-runner registry ({@code ExampleStand.registry}, which stores a literal base URL
 * for the passthrough resolver), this provider stores an <strong>env-ref</strong> ({@code CLIENT_SERVICE_URL})
 * as the service {@code baseUrlRef}: the default no-arg {@code RestStepExecutor} resolves it through
 * {@code EnvironmentBaseUrlResolver} → {@code System.getenv}. The port is pinned by the build
 * ({@code CLIENT_SERVICE_URL}), which the {@link ExampleDoublesExtension} also binds the HTTP double to.
 */
public final class ExampleEnvironmentRegistry implements EnvironmentRegistry {

    private final EnvironmentRegistry delegate;

    public ExampleEnvironmentRegistry() {
        ServiceEndpointDefinition service = new ServiceEndpointDefinition(
                ExampleStand.SERVICE, "CLIENT_SERVICE_URL",
                new CorrelationConfig(CorrelationSource.HEADER, ExampleStand.CORRELATION_HEADER));
        DatasourceDefinition datasource = new DatasourceDefinition(
                ExampleStand.DATASOURCE, "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD",
                Set.of(ExampleStand.SCHEMA), true);
        EnvironmentDefinition ift = new EnvironmentDefinition(
                ExampleStand.ENVIRONMENT,
                Map.of(ExampleStand.SERVICE, service),
                Map.of(),
                Map.of(ExampleStand.DATASOURCE, datasource),
                Map.of());
        this.delegate = new InMemoryEnvironmentRegistry(Map.of(ExampleStand.ENVIRONMENT, ift));
    }

    @Override
    public Optional<EnvironmentDefinition> environment(String name) {
        return delegate.environment(name);
    }
}
