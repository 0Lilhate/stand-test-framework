package ru.alfa.stand.test.example;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * A test-only {@link StepExecutor} that demonstrates the SPI seam: when the runner dispatches an
 * {@code example.variables.snapshot} step to it, it records an immutable snapshot of the run's
 * {@code VariableStore} and the run's {@code testRunId}, performing no IO and no assertions. The store is
 * per-run and owned by the runner (never exposed on {@code ScenarioResult}), so a custom executor — the
 * exact extension point a real adapter uses — is the public way an example can prove which variables the
 * earlier capture steps produced and that a second run starts from an empty store.
 */
final class VariableSnapshotProbe implements StepExecutor {

    static final String STEP_TYPE = "example.variables.snapshot";

    private final List<Map<String, Object>> snapshots = new ArrayList<>();

    private final List<String> testRunIds = new ArrayList<>();

    @Override
    public boolean supports(String stepType) {
        return STEP_TYPE.equals(stepType);
    }

    @Override
    public StepResult execute(ScenarioStep step, StepExecutionContext context) {
        Instant now = Instant.now();
        this.snapshots.add(context.variableStore().asMap());
        this.testRunIds.add(context.scenarioContext().testRunId().value());
        return StepResult.success(step.id(), step.type(), now, now);
    }

    List<Map<String, Object>> snapshots() {
        return List.copyOf(this.snapshots);
    }

    List<String> testRunIds() {
        return List.copyOf(this.testRunIds);
    }
}
