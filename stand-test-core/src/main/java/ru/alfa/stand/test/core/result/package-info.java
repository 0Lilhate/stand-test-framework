/**
 * Immutable execution result model.
 *
 * <p>{@link ru.alfa.stand.test.core.result.StepStatus} enumerates step outcomes;
 * {@link ru.alfa.stand.test.core.result.StepResult} captures a single step outcome with timing and
 * diagnostics; {@link ru.alfa.stand.test.core.result.ScenarioResult} aggregates step results for a
 * run. A {@code FAILED} status here is for reporting only and never replaces raising a JUnit failure.
 */
package ru.alfa.stand.test.core.result;
