# stand-test-config

**Group:** configuration · **Gradle plugin:** `java-library`

File-based loader for the SDK's **environment configuration**. It reads a declarative YAML file into an
immutable core `EnvironmentRegistry` and publishes it through the core `EnvironmentRegistry` SPI
(`META-INF/services`), so a **plain-JUnit** consumer's `StandTestExtension` — which resolves the registry
via `ServiceLoader` and otherwise falls back to an *empty* one — gets a **populated** registry with no
wiring code. This closes the "known gap" where alias resolution (service/topic/datasource/grpc) failed at
run time because the registry was empty.

**Internal dependencies:** `stand-test-core` (`api`) only. **External:** SnakeYAML (safe-loaded). No edges
to the adapter modules (rest/kafka/db/grpc), junit, allure, or the Spring starter.

## What it does

- `FileEnvironmentRegistry` — the SPI provider (`EnvironmentRegistry`). **Lazily** loads on first lookup
  and caches, so `ServiceLoader` discovery never does file IO.
- `YamlEnvironmentConfigLoader` — resolves the config source and parses it:
  - if the system property `stand.test.environments.config` is set, its value is a **filesystem path that
    must exist** (a missing explicit path is a `StandTestException`);
  - otherwise the classpath resource `stand-test-environments.yml` (or `.yaml`);
  - if neither is present → an **empty registry** (behaviour unchanged; the strict validator still rejects
    unknown environments — configure one to run against a stand).
- `EnvironmentConfig` — the canonical `Map → EnvironmentRegistry` mapper (fail-closed: unknown keys,
  ill-typed values and invalid references are `StandTestException` with a dotted location).
- `SafeYaml` — SnakeYAML `SafeConstructor` + conservative alias/nesting limits (anti-YAML-bomb).

## Usage

Add the module (typically `testImplementation`) and drop a `stand-test-environments.yml` on the test
classpath (`src/test/resources`):

```yaml
environments:
  ift:
    services:
      client-service:
        base-url-ref: CLIENT_SERVICE_URL           # env-var NAME, never the URL
        correlation: { source: HEADER, name: X-Correlation-Id }
    topics:
      response-topic: { name: pakt.response.ift, correlation: { source: HEADER, name: X-Correlation-Id } }
    datasources:
      main-db:
        url-ref: MAIN_DB_URL
        user-ref: MAIN_DB_USER
        password-ref: MAIN_DB_PASSWORD
        allowed-schemas: [test_data]
        write-allowed: true
    grpc-targets:
      billing-grpc: { target-ref: BILLING_GRPC_TARGET, correlation: { source: METADATA, name: x-correlation-id } }
    kafka-cluster:
      bootstrap-servers-ref: KAFKA_BOOTSTRAP
      security-protocol-ref: KAFKA_SECURITY_PROTOCOL
```

No code is needed: `StandTestExtension` discovers `FileEnvironmentRegistry` via `ServiceLoader`. Point the
loader at another file with `-Dstand.test.environments.config=/path/to/envs.yml`.

## Security: references, never values (plan §9)

Every address/secret field is a `*-ref` — the **name of an environment variable**, not the value. The
file never contains URLs, JDBC connection strings, tokens or passwords. The adapters resolve those
references at run time (`System.getenv`), so secrets stay out of source and out of the config file. Field
names are kebab-case (canonical); camelCase is also accepted.

## Relationship to the Spring Boot starter

The Spring Boot starter builds its registry from `@ConfigurationProperties("stand.test")` and does **not**
need this module. This module is the non-Spring counterpart; the YAML shape here mirrors the Spring
`stand.test.environments.<env>...` tree (minus the `stand.test` prefix).

**Known limitation (drift risk):** the mapping lives in two places — this module's `EnvironmentConfig`
(from a YAML map) and the starter's `EnvironmentRegistryFactory` (from Spring POJOs). They share one
logical schema but are not a single implementation; keep them in sync. Unifying them (e.g. the starter
delegating to `EnvironmentConfig`) is tracked future work.

## Testing

Unit tests cover the mapper (every resource type → references; unknown key / blank ref / bad correlation
source → `StandTestException` with location; camelCase aliasing), the loader (default resource present /
absent, explicit path present / missing / blank) and the SPI provider (`ServiceLoader` discovery + lazy,
cached loading). No real stand, no `Thread.sleep`. Instruction coverage ≥ 80% (JaCoCo).
