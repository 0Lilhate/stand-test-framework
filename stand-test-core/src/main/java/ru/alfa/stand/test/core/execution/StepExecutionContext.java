package ru.alfa.stand.test.core.execution;

import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.core.compensation.UndoLog;
import ru.alfa.stand.test.core.context.ScenarioContext;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.variable.VariableResolver;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Immutable holder of the per-run collaborators handed to a {@link StepExecutor}.
 *
 * <p>It references the run's {@link ScenarioContext} (metadata), the per-run {@link VariableStore}
 * (mutable, shared within the run), the {@link EnvironmentRegistry} (alias resolution / whitelist), the
 * {@link ReportingEventPublisher} (reporting sink), the run-scoped {@link ResourceScope} (live
 * {@code AutoCloseable} resources such as a pre-armed Kafka consumer, plan §8.7) and the per-run
 * {@link UndoLog} (test-data compensations registered by write steps, drained by the runner in its
 * {@code finally}). The holder itself adds no behaviour and no IO.
 *
 * @param scenarioContext the run metadata
 * @param variableStore the per-run variable store
 * @param environmentRegistry the environment registry
 * @param reportingEventPublisher the reporting sink
 * @param resourceScope the run-scoped registry of closeable resources
 * @param undoLog the per-run test-data compensation registry
 * @param stepOrdinals immutable one-based scenario step positions by step id
 */
public record StepExecutionContext(
        ScenarioContext scenarioContext,
        VariableStore variableStore,
        EnvironmentRegistry environmentRegistry,
        ReportingEventPublisher reportingEventPublisher,
        ResourceScope resourceScope,
        UndoLog undoLog,
        Map<String, Integer> stepOrdinals) {

    public StepExecutionContext {
        Objects.requireNonNull(scenarioContext, "scenarioContext must not be null");
        Objects.requireNonNull(variableStore, "variableStore must not be null");
        Objects.requireNonNull(environmentRegistry, "environmentRegistry must not be null");
        Objects.requireNonNull(reportingEventPublisher, "reportingEventPublisher must not be null");
        Objects.requireNonNull(resourceScope, "resourceScope must not be null");
        Objects.requireNonNull(undoLog, "undoLog must not be null");
        stepOrdinals = Map.copyOf(Objects.requireNonNull(stepOrdinals, "stepOrdinals must not be null"));
    }

    /** Preserves the six-argument constructor used before step ordinals became available. */
    public StepExecutionContext(ScenarioContext scenarioContext, VariableStore variableStore,
            EnvironmentRegistry environmentRegistry, ReportingEventPublisher reportingEventPublisher,
            ResourceScope resourceScope, UndoLog undoLog) {
        this(scenarioContext, variableStore, environmentRegistry, reportingEventPublisher,
                resourceScope, undoLog, Map.of());
    }

    /**
     * Creates a context with the run's shared {@link ResourceScope} and a fresh {@link UndoLog}.
     * Backwards-compatible overload for callers written before the undo-log was threaded through; the
     * runner uses the canonical constructor so the drained log is the one the executors registered into.
     *
     * @param scenarioContext the run metadata
     * @param variableStore the per-run variable store
     * @param environmentRegistry the environment registry
     * @param reportingEventPublisher the reporting sink
     * @param resourceScope the run-scoped registry of closeable resources
     */
    public StepExecutionContext(
            ScenarioContext scenarioContext,
            VariableStore variableStore,
            EnvironmentRegistry environmentRegistry,
            ReportingEventPublisher reportingEventPublisher,
            ResourceScope resourceScope) {
        this(scenarioContext, variableStore, environmentRegistry, reportingEventPublisher,
                resourceScope, new UndoLog(), Map.of());
    }

    /**
     * Creates a context with a fresh, empty {@link ResourceScope} and {@link UndoLog}. Convenience for
     * callers that do not pre-arm resources (REST/DB executors and most tests); the runner uses the
     * canonical constructor with the run's shared scope and log.
     *
     * @param scenarioContext the run metadata
     * @param variableStore the per-run variable store
     * @param environmentRegistry the environment registry
     * @param reportingEventPublisher the reporting sink
     */
    public StepExecutionContext(
            ScenarioContext scenarioContext,
            VariableStore variableStore,
            EnvironmentRegistry environmentRegistry,
            ReportingEventPublisher reportingEventPublisher) {
        this(scenarioContext, variableStore, environmentRegistry, reportingEventPublisher,
                new ResourceScope(), new UndoLog(), Map.of());
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

    /** Returns the scenario's one-based step ordinal, never an adapter-local counter. */
    public int stepOrdinal(String stepId) {
        Integer ordinal = stepOrdinals.get(stepId);
        if (ordinal == null || ordinal < 1) {
            throw new IllegalStateException("Step ordinal is unavailable for '" + stepId + "'");
        }
        return ordinal;
    }
}
