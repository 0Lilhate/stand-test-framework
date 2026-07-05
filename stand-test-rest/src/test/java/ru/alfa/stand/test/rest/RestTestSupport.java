package ru.alfa.stand.test.rest;

import java.util.Map;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Shared fixtures for the REST adapter tests: a whitelisted environment with one service and a
 * ready-made {@link StepExecutionContext}.
 */
final class RestTestSupport {

    static final String ENVIRONMENT = "ift";
    static final String SERVICE = "client-service";
    static final String CORRELATION_HEADER = "X-Correlation-Id";

    private RestTestSupport() {
    }

    static EnvironmentRegistry registry(String baseUrl) {
        return registry(new ServiceEndpointDefinition(SERVICE, baseUrl, new CorrelationConfig(CorrelationSource.HEADER, CORRELATION_HEADER)));
    }

    static EnvironmentRegistry registryWithoutCorrelation(String baseUrl) {
        return registry(new ServiceEndpointDefinition(SERVICE, baseUrl, null));
    }

    static EnvironmentRegistry registryWithBasicAuth(String baseUrl) {
        return registry(new ServiceEndpointDefinition(
                SERVICE, baseUrl, new CorrelationConfig(CorrelationSource.HEADER, CORRELATION_HEADER), AuthConfig.basic("CLIENT_USER", "CLIENT_PASSWORD")));
    }

    static EnvironmentRegistry registry(ServiceEndpointDefinition endpoint) {
        EnvironmentDefinition environment = new EnvironmentDefinition(ENVIRONMENT, Map.of(endpoint.name(), endpoint), Map.of(), Map.of(), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static StepExecutionContext context(EnvironmentRegistry registry, VariableStore store) {
        ScenarioContext scenarioContext = ScenarioContext.start(ScenarioId.of("scenario-1"), ENVIRONMENT);
        return new StepExecutionContext(scenarioContext, store, registry, NoOpReportingEventPublisher.INSTANCE);
    }

    /**
     * A real-HTTP executor: the WebClient caller plus a passthrough base-URL resolver, so a registry
     * whose {@code baseUrlRef} is a literal {@code http://...} test-server URL resolves directly
     * (the production {@link EnvironmentBaseUrlResolver} is reference-only).
     *
     * @return an executor backed by the real WebClient transport
     */
    static RestStepExecutor liveExecutor() {
        return new RestStepExecutor(new WebClientHttpCaller(), ref -> ref);
    }
}
