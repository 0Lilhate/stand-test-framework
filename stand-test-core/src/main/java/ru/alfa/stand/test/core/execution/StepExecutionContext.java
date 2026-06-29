package ru.alfa.stand.test.core.execution;

import java.util.Objects;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.variable.VariableResolver;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Immutable holder of the per-run collaborators handed to a {@link StepExecutor}.
 *
 * <p>It references the run's {@link ScenarioContext} (metadata), the per-run {@link VariableStore}
 * (mutable, shared within the run), the {@link EnvironmentRegistry} (alias resolution / whitelist) and
 * the {@link ReportingEventPublisher} (reporting sink). The holder itself adds no behaviour and no IO.
 *
 * @param scenarioContext the run metadata
 * @param variableStore the per-run variable store
 * @param environmentRegistry the environment registry
 * @param reportingEventPublisher the reporting sink
 */
public record StepExecutionContext(
        ScenarioContext scenarioContext,
        VariableStore variableStore,
        EnvironmentRegistry environmentRegistry,
        ReportingEventPublisher reportingEventPublisher) {

    public StepExecutionContext {
        Objects.requireNonNull(scenarioContext, "scenarioContext must not be null");
        Objects.requireNonNull(variableStore, "variableStore must not be null");
        Objects.requireNonNull(environmentRegistry, "environmentRegistry must not be null");
        Objects.requireNonNull(reportingEventPublisher, "reportingEventPublisher must not be null");
    }

    /**
     * Returns a resolver bound to this run's context and variable store, so all executors share one
     * {@code ${name}} resolution path.
     *
     * @return a variable resolver for this run
     */
    public VariableResolver resolver() {
        return new VariableResolver(scenarioContext, variableStore);
    }
}
