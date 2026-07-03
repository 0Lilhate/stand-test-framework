/**
 * Stand test SDK — JUnit 5 integration (the bridge between the JUnit lifecycle and the SDK).
 *
 * <p>{@link ru.alfa.stand.test.junit.StandTest @StandTest} wires
 * {@link ru.alfa.stand.test.junit.StandTestExtension}, which resolves as test parameters, without
 * Spring: a {@link ru.alfa.stand.test.core.StandClient} (assembled from {@code StepExecutor} SPI
 * implementations discovered on the classpath), an {@link ru.alfa.stand.test.await.Awaiter}, and
 * {@code String}s annotated with {@link ru.alfa.stand.test.junit.StandScenarioId @StandScenarioId} /
 * {@link ru.alfa.stand.test.junit.StandEnv @StandEnv} (the declared scenario id / environment). SDK
 * failures surface as native JUnit failures via the exception hierarchy
 * ({@code StandTestAssertionError} / {@code StandTestException}), so no manual translation is needed.
 */
package ru.alfa.stand.test.junit;
