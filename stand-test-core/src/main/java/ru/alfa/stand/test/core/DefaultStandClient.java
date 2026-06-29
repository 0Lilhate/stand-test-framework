package ru.alfa.stand.test.core;

import java.util.Objects;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Default {@link StandClient}: a thin facade that delegates to a {@link ScenarioRunner}.
 *
 * <p>The client adds no behaviour of its own — validation, execution and failure semantics live in the
 * runner (see {@link ru.alfa.stand.test.core.execution.DefaultScenarioRunner}). It exists so consumers
 * depend on the stable {@link StandClient} facade rather than on a concrete runner type.
 */
public final class DefaultStandClient implements StandClient {

    private final ScenarioRunner runner;

    /**
     * Creates a client backed by the given runner.
     *
     * @param runner the scenario runner to delegate to
     */
    public DefaultStandClient(ScenarioRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    @Override
    public ScenarioResult run(Scenario scenario) {
        return runner.run(scenario);
    }
}
