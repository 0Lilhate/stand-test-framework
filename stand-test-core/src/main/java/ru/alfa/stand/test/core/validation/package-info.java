/**
 * Scenario validation contracts.
 *
 * <p>{@link ru.alfa.stand.test.core.validation.ScenarioValidator} validates a scenario model before
 * execution and returns a {@link ru.alfa.stand.test.core.validation.ValidationResult} of
 * {@link ru.alfa.stand.test.core.validation.ValidationIssue}s.
 * {@link ru.alfa.stand.test.core.validation.ForbiddenOperation} is the single source of truth for
 * disallowed operations, consumed by the runtime validator and (later) by the AI schema. Environment
 * whitelist validation is intentionally not implemented in this iteration — only the contracts exist.
 */
package ru.alfa.stand.test.core.validation;
