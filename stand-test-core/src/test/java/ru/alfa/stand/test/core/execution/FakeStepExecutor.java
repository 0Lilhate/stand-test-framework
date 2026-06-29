package ru.alfa.stand.test.core.execution;

import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Configurable in-memory {@link StepExecutor} for runner tests: supports a single step type and runs a
 * caller-supplied behaviour (which may return a result or throw). An optional {@code prepareBehaviour}
 * exercises the {@link StepExecutor#prepare} pre-phase.
 */
final class FakeStepExecutor implements StepExecutor {

    private final String supportedType;
    private final BiFunction<ScenarioStep, StepExecutionContext, StepResult> behaviour;
    private BiConsumer<ScenarioStep, StepExecutionContext> prepareBehaviour;
    private int invocations;
    private int prepareInvocations;

    FakeStepExecutor(String supportedType, BiFunction<ScenarioStep, StepExecutionContext, StepResult> behaviour) {
        this.supportedType = supportedType;
        this.behaviour = behaviour;
    }

    static FakeStepExecutor succeeding(String type) {
        return new FakeStepExecutor(type, (step, context) ->
                StepResult.success(step.id(), step.type(), Instant.now(), Instant.now()));
    }

    static FakeStepExecutor failing(String type, String message) {
        return new FakeStepExecutor(type, (step, context) ->
                StepResult.failed(step.id(), step.type(), Instant.now(), Instant.now(), message));
    }

    FakeStepExecutor onPrepare(BiConsumer<ScenarioStep, StepExecutionContext> prepareBehaviour) {
        this.prepareBehaviour = prepareBehaviour;
        return this;
    }

    @Override
    public boolean supports(String stepType) {
        return supportedType.equals(stepType);
    }

    @Override
    public void prepare(ScenarioStep step, StepExecutionContext context) {
        prepareInvocations++;
        if (prepareBehaviour != null) {
            prepareBehaviour.accept(step, context);
        }
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        invocations++;
        return behaviour.apply(step, context);
    }

    int invocations() {
        return invocations;
    }

    int prepareInvocations() {
        return prepareInvocations;
    }
}
