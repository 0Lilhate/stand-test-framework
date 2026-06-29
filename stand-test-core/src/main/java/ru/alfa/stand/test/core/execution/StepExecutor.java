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
     * Optional pre-execution hook, invoked by the runner for every step (in declaration order) before
     * the first step runs (plan §8.7). The default is a no-op.
     *
     * <p>This exists for async-expect steps that must position a resource <em>before</em> the
     * triggering step produces its effect — the canonical case being a {@code kafka.expect} consumer
     * that is armed ({@code assign}/{@code seekToEnd}) here so it is listening before an earlier
     * {@code rest.post} sends the message it will wait for. This is the resolution of
     * {@code KAFKA-SEEK-RACE}: the runner positions all such resources up front, so an
     * SDK-owned correlation id injected outbound by an earlier step cannot be missed. Resources opened
     * here belong in the run's {@link ResourceScope} (see {@link StepExecutionContext#resourceScope()})
     * so the runner closes them after the run. Implementations should be idempotent per logical key
     * (arming the same topic twice in one run must not open a second consumer).
     *
     * @param step the step being prepared
     * @param context the per-run execution context
     */
    default void prepare(ScenarioStep step, StepExecutionContext context) {
        // No-op by default: only async-expect adapters (Kafka) override this. REST/DB prepare is a no-op.
    }

    /**
     * Executes the given step.
     *
     * @param step the step to execute
     * @param context the per-run execution context
     * @return the step result
     */
    StepResult execute(ScenarioStep step, StepExecutionContext context);
}
