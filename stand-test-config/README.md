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
  - otherwise the familiar **`application.yml`** (or `.yaml`) — its `stand.test.environments` section
    (nested `stand: test: environments:` or dotted keys), the exact schema the Spring Boot starter binds,
    so one configuration style serves both worlds; an `application.yml` without that section contributes
    nothing;
  - if no source is present → an **empty registry** (behaviour unchanged; the strict validator still
    rejects unknown environments — configure one to run against a stand).
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
        auth: { scheme: BASIC, username-ref: CLIENT_USER, password-ref: CLIENT_PASSWORD }
        # or: auth: { scheme: BEARER, token-ref: CLIENT_TOKEN }
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
    kafka-cluster:                                  # the DEFAULT cluster (topics without `cluster`)
      bootstrap-servers-ref: KAFKA_BOOTSTRAP
      security-protocol-ref: KAFKA_SECURITY_PROTOCOL
    kafka-clusters:                                 # additional NAMED clusters
      audit:
        bootstrap-servers-ref: AUDIT_KAFKA_BOOTSTRAP
```

**Multiple Kafka clusters:** the single `kafka-cluster` is the environment's default; `kafka-clusters`
whitelists named clusters, and a topic selects one via `cluster: <alias>` (e.g.
`audit: { name: ift.audit.v1, cluster: audit }`). A topic naming an undeclared cluster is rejected at
load time — the whitelist stays closed. Multiple services and datasources need nothing special: the
maps take any number of aliases.

No code is needed: `StandTestExtension` discovers `FileEnvironmentRegistry` via `ServiceLoader`. Point the
loader at another file with `-Dstand.test.environments.config=/path/to/envs.yml`.

## Placeholders: `${ENV_VAR}` and `${ENV_VAR:default}`

Every `*-ref` field accepts three spellings — a bare env-var `NAME`, `${NAME}`, and `${NAME:default}`.
The reference is stored verbatim and resolved by the adapters at the point of use; with
`${NAME:default}` the inline default applies only when the variable is **missing** (a variable set to an
empty value wins, matching Spring's semantics):

```yaml
    services:
      client-service:
        base-url-ref: ${CLIENT_SERVICE_URL:http://localhost:8080}   # env var wins when set
```

**Trade-off, stated plainly:** an inline default IS a value in the repository. Use defaults for
non-secret DEV endpoints; keep credentials as pure references — a default on `password-ref` puts a
password into VCS, and nothing will stop you but this sentence.

## Security: references, never values (plan §9)

Every address/secret field is a `*-ref` — the **name of an environment variable**, not the value (bare
URLs, JDBC connection strings, tokens and `Bearer`/`Basic` values are rejected fail-closed). The adapters
resolve references at run time (`System.getenv`), so secrets stay out of source and out of the config
file — unless you opt into an inline `${NAME:default}` (see above). Field names are kebab-case
(canonical); camelCase is also accepted. Service `auth` refs (`username-ref`/`password-ref`/`token-ref`)
follow the same rule; note the shape guard cannot recognise a *bare* token pasted as a "name" (it catches
whitespace, `://` and `Bearer `/`Basic ` prefixes) — the same residual trust applies to datasource
passwords today.

## Relationship to the Spring Boot starter

The Spring Boot starter builds its registry from `@ConfigurationProperties("stand.test")` and does **not**
need this module. This module is the non-Spring counterpart; the YAML shape here mirrors the Spring
`stand.test.environments.<env>...` tree (minus the `stand.test` prefix).

**This surface is refs-only by design.** The starter's endpoint value twins (`base-url`, `url`,
`target`, `bootstrap-servers`, `security-protocol` with real Spring `${VAR:}` placeholders) do NOT
exist here: there is no Spring resolver when this file is loaded, and a committable YAML file must
never carry resolved endpoints. A value field in `stand-test-environments.yml` is rejected
fail-closed as an unknown key.

**Known limitation (drift risk):** the mapping lives in two places — this module's `EnvironmentConfig`
(from a YAML map) and the starter's `EnvironmentRegistryFactory` (from Spring POJOs). They share one
logical schema but are not a single implementation; keep them in sync. Unifying them (e.g. the starter
delegating to `EnvironmentConfig`) is tracked future work.

## Testing

Unit tests cover the mapper (every resource type → references; unknown key / blank ref / bad correlation
source → `StandTestException` with location; camelCase aliasing), the loader (default resource present /
absent, explicit path present / missing / blank) and the SPI provider (`ServiceLoader` discovery + lazy,
cached loading). No real stand, no `Thread.sleep`. Instruction coverage ≥ 80% (JaCoCo).
