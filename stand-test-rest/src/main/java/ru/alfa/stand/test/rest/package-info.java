/**
 * Stand test SDK — REST / HTTP adapter.
 *
 * <p>Owns the typed lazy-builder {@link ru.alfa.stand.test.rest.RestStep} and the REST
 * {@link ru.alfa.stand.test.rest.RestStepExecutor} (registered via the core
 * {@code StepExecutor} SPI in {@code META-INF/services}). The executor is the single point of real
 * HTTP IO to a stand: it resolves the logical service alias through the core
 * {@code EnvironmentRegistry}, substitutes {@code ${...}} variables, injects the SDK-owned
 * correlation id outbound, runs JSONPath assertions and captures response values into the run's
 * variable store.
 *
 * <p>The HTTP call is delegated to {@link ru.alfa.stand.test.rest.HttpCaller} (default:
 * {@link ru.alfa.stand.test.rest.WebClientHttpCaller}, Spring WebClient on the JDK HttpClient
 * connector). The SDK never ships its own HTTP client (plan §4, §20).
 */
package ru.alfa.stand.test.rest;
