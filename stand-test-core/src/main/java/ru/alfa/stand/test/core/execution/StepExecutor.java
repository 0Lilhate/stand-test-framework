package ru.alfa.stand.test.core.execution;

import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * SPI for executing a single step type.
 *
 * <p>Adapters implement this interface; the runner dispatches each step to an executor that
 * {@link #supports(String)} the step's {@link ScenarioStep#type()}. Returning a boolean (rather than
 * a single exact type) lets one adapter claim a family of step types (for example {@code rest.post},
 * {@code rest.get}). Core defines no implementation — there is no REST/Kafka/DB/gRPC IO here.
 *
 * <p><strong>Thread-safety.</strong> Implementations must be stateless and thread-safe: a single
 * executor instance may be shared across concurrent scenario runs (for example one cached
 * {@code StandClient} per JUnit engine). All per-run state is supplied through
 * {@link StepExecutionContext} (which carries the per-run variable store); executors must keep
 * per-run mutable state there, never in instance fields.
 */
public interface StepExecutor {

    /**
     * Returns whether this executor can handle the given step type.
     *
     * @param stepType the step type (for example {@code rest.post})
     * @return true if this executor handles the type
     */
    boolean supports(String stepType);

    /**
     * Executes the given step.
     *
     * @param step the step to execute
     * @param context the per-run execution context
     * @return the step result
     */
    StepResult execute(ScenarioStep step, StepExecutionContext context);
}
