package ru.alfa.stand.test.core;

import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Future-facing facade contract for running a {@link Scenario} against a stand.
 *
 * <p>The default implementation is {@link DefaultStandClient}, a thin facade over a
 * {@link ru.alfa.stand.test.core.execution.ScenarioRunner}. A consumer builds an immutable
 * {@code Scenario} model (the Java DSL is a lazy builder) and hands it to {@code run}, which validates
 * the scenario and executes it through the runner and the adapter {@code StepExecutor}s.
 *
 * <p><strong>Failure semantics.</strong> {@code run} returns a {@link ScenarioResult} for
 * reporting/diagnostics, but a returned result must never hide a failed test: assertion failures are
 * raised as {@link ru.alfa.stand.test.core.exception.StandTestAssertionError} (a JUnit-compatible
 * {@link AssertionError}) and infrastructure/configuration failures as
 * {@link ru.alfa.stand.test.core.exception.StandTestException}.
 */
public interface StandClient {

    /**
     * Runs the given scenario and returns its result.
     *
     * @param scenario the immutable scenario model to execute
     * @return the scenario result for reporting and diagnostics
     */
    ScenarioResult run(Scenario scenario);
}
