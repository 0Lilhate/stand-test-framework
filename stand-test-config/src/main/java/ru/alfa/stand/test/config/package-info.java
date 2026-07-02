/**
 * Stand test SDK — file-based environment configuration loader.
 *
 * <p>Builds an immutable core {@link ru.alfa.stand.test.core.environment.EnvironmentRegistry} from a
 * declarative YAML file and publishes it through the core {@code EnvironmentRegistry} SPI
 * ({@code META-INF/services}), so a plain-JUnit consumer's {@code StandTestExtension} — which resolves the
 * registry via {@link java.util.ServiceLoader} and otherwise falls back to an empty one — gets a populated
 * registry with no wiring code. The Spring Boot starter builds its registry from
 * {@code @ConfigurationProperties} instead; this module is the non-Spring counterpart.
 *
 * <p>The file stores only <strong>references</strong> (environment-variable names) for addresses and
 * secrets, never their values (plan §9); the adapters resolve those references at run time via
 * {@code System.getenv}. {@link ru.alfa.stand.test.config.FileEnvironmentRegistry} loads lazily (on first
 * lookup) so {@code ServiceLoader} discovery never triggers file IO. Unknown keys, malformed YAML and
 * invalid references are rejected as config-class {@link ru.alfa.stand.test.core.exception.StandTestException};
 * a missing default file yields an empty registry (unchanged behaviour).
 */
package ru.alfa.stand.test.config;
