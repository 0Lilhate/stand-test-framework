/**
 * Stand test SDK — Spring Boot starter / auto-configuration.
 *
 * <p>Provides {@link ru.alfa.stand.test.starter.StandTestAutoConfiguration}, which assembles the SDK
 * ({@code StandClient}, {@code ScenarioRunner}, validator, {@code EnvironmentRegistry}, reporting
 * publisher and the adapter {@code StepExecutor}s) as Spring beans — the same object graph
 * {@code StandTestExtension.buildStandClient()} builds for JUnit, but wired through the context and
 * driven by {@link ru.alfa.stand.test.starter.StandTestProperties} (rooted at {@code stand.test})
 * instead of the {@code ServiceLoader}.
 *
 * <p>This is an integration module only: it contains no transport or business logic and never depends
 * back on the modules it wires. Every bean is conditional and overridable, and the whole configuration
 * is gated by {@code stand.test.enabled}.
 */
package ru.alfa.stand.test.starter;
