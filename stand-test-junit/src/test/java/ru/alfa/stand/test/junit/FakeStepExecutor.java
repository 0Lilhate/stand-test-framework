package ru.alfa.stand.test.junit;

import java.time.Instant;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Test {@link StepExecutor} discovered via {@link java.util.ServiceLoader} (registered in
 * {@code META-INF/services}). Supports {@code fake.ok} (succeeds) and {@code fake.fail} (returns
 * FAILED). Must be public with a public no-arg constructor for the service loader.
 */
public final class FakeStepExecutor implements StepExecutor {

    @Override
    public boolean supports(String stepType) {
        return "fake.ok".equals(stepType) || "fake.fail".equals(stepType);
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Instant now = Instant.now();
        if ("fake.fail".equals(step.type())) {
            return StepResult.failed(step.id(), step.type(), now, now, "fake failure");
        }
        return StepResult.success(step.id(), step.type(), now, now);
    }
}
