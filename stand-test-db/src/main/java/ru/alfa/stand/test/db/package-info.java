/**
 * Stand test SDK — database / JDBC adapter.
 *
 * <p>Owns the typed {@link ru.alfa.stand.test.db.DbStep} model (step types {@code db.query} /
 * {@code db.expectEventually} / {@code db.seed} / {@code db.cleanup}) and the
 * {@link ru.alfa.stand.test.db.DbStepExecutor} (registered via the core {@code StepExecutor} SPI in
 * {@code META-INF/services}). It is the single point of real JDBC IO to a stand — a seed/probe/assertion
 * layer, not a generic DB client: reads are the default and writes exist only as constrained test-data
 * preparation, gated by {@link ru.alfa.stand.test.db.DbWriteGuard} over the core
 * {@code SqlStatementClassifier} (plan §8.8).
 */
package ru.alfa.stand.test.db;
