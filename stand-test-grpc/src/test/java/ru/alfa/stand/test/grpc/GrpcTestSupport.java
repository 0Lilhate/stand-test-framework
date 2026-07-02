package ru.alfa.stand.test.grpc;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.GrpcTargetDefinition;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Shared fixtures for the gRPC adapter tests: a whitelisted environment with a gRPC target and a
 * ready-made {@link StepExecutionContext}. No real server is involved.
 */
final class GrpcTestSupport {

    static final String ENVIRONMENT = "ift";
    static final String TARGET_ALIAS = "billing-grpc";
    static final String TARGET_REF = "BILLING_GRPC_TARGET";
    static final String CORRELATION_KEY = "x-correlation-id";

    private GrpcTestSupport() {
    }

    static GrpcTargetDefinition metadataTarget() {
        return new GrpcTargetDefinition(TARGET_ALIAS, TARGET_REF, new CorrelationConfig(CorrelationSource.METADATA, CORRELATION_KEY));
    }

    static GrpcTargetDefinition headerTarget() {
        return new GrpcTargetDefinition(TARGET_ALIAS, TARGET_REF, new CorrelationConfig(CorrelationSource.HEADER, CORRELATION_KEY));
    }

    static GrpcTargetDefinition targetWithoutCorrelation() {
        return new GrpcTargetDefinition(TARGET_ALIAS, TARGET_REF, null);
    }

    static EnvironmentRegistry registry(GrpcTargetDefinition... targets) {
        Map<String, GrpcTargetDefinition> byAlias = new LinkedHashMap<>();
        for (GrpcTargetDefinition target : targets) {
            byAlias.put(target.alias(), target);
        }
        EnvironmentDefinition environment = new EnvironmentDefinition(ENVIRONMENT, Map.of(), Map.of(), Map.of(), byAlias);
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static EnvironmentRegistry emptyRegistry() {
        return new InMemoryEnvironmentRegistry(Map.of());
    }

    static StepExecutionContext context(EnvironmentRegistry registry, VariableStore store) {
        ScenarioContext scenarioContext = ScenarioContext.start(ScenarioId.of("scenario-1"), ENVIRONMENT);
        return new StepExecutionContext(scenarioContext, store, registry, NoOpReportingEventPublisher.INSTANCE);
    }
}
