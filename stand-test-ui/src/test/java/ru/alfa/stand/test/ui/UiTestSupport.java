package ru.alfa.stand.test.ui;

import java.util.Map;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.variable.VariableStore;

/** Shared fixtures for the UI adapter's tests. */
final class UiTestSupport {

    static final String ENVIRONMENT = "ift";

    static final String APPLICATION = "client-portal";

    static final String BASE_URL_REF = "CLIENT_PORTAL_BASE_URL";

    private UiTestSupport() {
    }

    static EnvironmentRegistry registry() {
        return registry(new UiApplicationDefinition(APPLICATION, BASE_URL_REF));
    }

    static EnvironmentRegistry registry(UiApplicationDefinition application) {
        EnvironmentDefinition environment = new EnvironmentDefinition(
                ENVIRONMENT, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(application.alias(), application));
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static StepExecutionContext context() {
        return context(registry(), new ResourceScope());
    }

    static StepExecutionContext context(ResourceScope scope) {
        return context(registry(), scope);
    }

    static StepExecutionContext context(EnvironmentRegistry registry, ResourceScope scope) {
        return new StepExecutionContext(
                ScenarioContext.start(new ScenarioId("ui-test"), ENVIRONMENT),
                new VariableStore(),
                registry,
                NoOpReportingEventPublisher.INSTANCE,
                scope);
    }

    static ResolvedUiApplication resolved() {
        return new ResolvedUiApplication(APPLICATION, "http://localhost:8080", null);
    }

    /** A resolved application with an explicit trace mode, for the UITG-S016 failure-artefact tests. */
    static ResolvedUiApplication resolved(UiTraceMode trace) {
        return new ResolvedUiApplication(APPLICATION, "http://localhost:8080", null, null, trace);
    }

    static UiRunSettings settings() {
        return UiRunSettings.fromProperties(property -> null);
    }
}
