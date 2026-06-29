/**
 * Immutable scenario metadata.
 *
 * <p>{@link ru.alfa.stand.test.core.context.ScenarioContext} carries the stable metadata of a run
 * (ids, environment, tags, creation time). It is deliberately <em>not</em> a variable store: runtime
 * variables captured during execution live in {@link ru.alfa.stand.test.core.variable.VariableStore}.
 */
package ru.alfa.stand.test.core.context;
