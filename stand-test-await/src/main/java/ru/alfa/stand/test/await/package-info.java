/**
 * Stand test SDK — the single await mechanism for asynchronous effects on real stands.
 *
 * <p>This package is the project-wide replacement for {@code Thread.sleep} (plan §2.4/§2.5): instead
 * of fixed pauses, callers wait on a {@link ru.alfa.stand.test.await.Awaiter} that polls a supplied
 * probe until a predicate holds or a configurable timeout elapses, then reports rich
 * {@link ru.alfa.stand.test.await.TimeoutDiagnostics} (what was awaited, attempts, elapsed, the last
 * observed value and the last error).
 *
 * <p>The awaiter is deliberately <em>transport-agnostic</em>: it knows nothing about REST, Kafka, DB
 * or gRPC and only evaluates the {@link java.util.function.Supplier}/{@link java.util.function.Predicate}
 * it is given. The probe runs on the calling thread, preserving the thread-confinement of stand-test
 * resources (JDBC connections, Kafka consumers). Time is abstracted behind
 * {@link ru.alfa.stand.test.await.TimeSource} so timeout behaviour can be unit-tested deterministically
 * without real sleeping.
 */
package ru.alfa.stand.test.await;
