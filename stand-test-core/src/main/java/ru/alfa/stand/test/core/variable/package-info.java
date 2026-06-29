/**
 * Runtime variable storage and resolution for a single scenario run.
 *
 * <p>{@link ru.alfa.stand.test.core.variable.VariableStore} is the per-run mutable store of captured
 * values (one instance per run, owned by the runner — never static, global or thread-local by
 * default). {@link ru.alfa.stand.test.core.variable.VariableResolver} performs simple
 * {@code ${name}} substitution against built-in metadata and the store; it is intentionally not an
 * expression language and never executes code.
 */
package ru.alfa.stand.test.core.variable;
