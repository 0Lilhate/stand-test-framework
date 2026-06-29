/**
 * Immutable, value-based identifier types for the stand-test SDK.
 *
 * <p>{@link ru.alfa.stand.test.core.identifier.ScenarioId} identifies a scenario;
 * {@link ru.alfa.stand.test.core.identifier.TestRunId} identifies a single run; and
 * {@link ru.alfa.stand.test.core.identifier.CorrelationId} is the SDK-owned cross-system correlation
 * id. All three are null-safe, reject blank values and have a readable {@code toString}.
 */
package ru.alfa.stand.test.core.identifier;
