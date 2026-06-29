package ru.alfa.stand.test.core.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.variable.VariableStore;

class StepExecutionContextTest {

    @Test
    @DisplayName("exposes the per-run collaborators it was built with")
    void exposesCollaborators() {
        ScenarioContext scenarioContext = ScenarioContext.start(ScenarioId.of("flow"), "ift");
        VariableStore variableStore = new VariableStore();
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(java.util.Map.of());
        ResourceScope resourceScope = new ResourceScope();

        StepExecutionContext context = new StepExecutionContext(
                scenarioContext, variableStore, registry, NoOpReportingEventPublisher.INSTANCE, resourceScope);

        assertThat(context.scenarioContext()).isSameAs(scenarioContext);
        assertThat(context.variableStore()).isSameAs(variableStore);
        assertThat(context.environmentRegistry()).isSameAs(registry);
        assertThat(context.reportingEventPublisher()).isSameAs(NoOpReportingEventPublisher.INSTANCE);
        assertThat(context.resourceScope()).isSameAs(resourceScope);
    }

    @Test
    @DisplayName("the four-argument constructor supplies a fresh, empty resource scope")
    void fourArgConstructorSuppliesFreshScope() {
        StepExecutionContext context = new StepExecutionContext(
                ScenarioContext.start(ScenarioId.of("flow"), "ift"),
                new VariableStore(),
                new InMemoryEnvironmentRegistry(java.util.Map.of()),
                NoOpReportingEventPublisher.INSTANCE);

        assertThat(context.resourceScope()).isNotNull();
        assertThat(context.resourceScope().contains("anything")).isFalse();
    }

    @Test
    @DisplayName("rejects null collaborators")
    void rejectsNullCollaborators() {
        VariableStore variableStore = new VariableStore();
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(java.util.Map.of());

        assertThatThrownBy(() -> new StepExecutionContext(
                null, variableStore, registry, NoOpReportingEventPublisher.INSTANCE))
                .isInstanceOf(NullPointerException.class);
    }
}
