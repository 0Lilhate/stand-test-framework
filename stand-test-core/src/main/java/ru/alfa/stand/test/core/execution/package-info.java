/**
 * Execution SPI contracts.
 *
 * <p>{@link ru.alfa.stand.test.core.execution.ScenarioRunner} runs a scenario;
 * {@link ru.alfa.stand.test.core.execution.StepExecutor} executes a single step type and is the SPI
 * the adapters implement; {@link ru.alfa.stand.test.core.execution.StepExecutionContext} carries the
 * per-run context, variable store, environment registry and reporting sink. This iteration provides
 * contracts only — no runner or executor performs any IO.
 */
package ru.alfa.stand.test.core.execution;
