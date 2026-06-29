package ru.alfa.stand.test.core.execution;

import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Runs a validated scenario through the step-executor SPI and returns its result.
 *
 * <p>The default implementation is {@link DefaultScenarioRunner}. An implementation owns the
 * {@link StepExecutionContext} (and thus the per-run variable store), validates the scenario,
 * dispatches each step to a {@link StepExecutor}, and raises assertion failures as JUnit-compatible
 * errors rather than hiding them in the returned result.
 *
 * <p>Under the default short-circuit policy (plan §8.3) a successful {@code run} returns a SUCCESS
 * {@link ScenarioResult}; on failure it throws. Failure diagnostics reach reporting (JUnit/Allure)
 * through the same {@link ScenarioResult} and the reporting-event SPI.
 */
public interface ScenarioRunner {

    /**
     * Runs the given scenario.
     *
     * @param scenario the scenario to run
     * @return the scenario result for reporting and diagnostics
     */
    ScenarioResult run(Scenario scenario);
}
