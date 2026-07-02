# stand-test-spring-boot-starter

**Group:** integrations · **Gradle plugin:** `java-library`

Spring Boot auto-configuration for the stand-test SDK. Drop it on a Spring Boot **test** project's
classpath and an `@Autowired StandClient` (plus the runner, validator, environment registry, reporting
publisher and the adapter step executors) becomes available — the same object graph
`StandTestExtension.buildStandClient()` assembles for JUnit, but wired through the Spring context and
driven by `application.yml` instead of the `ServiceLoader`.

This is a **library**, not a bootable application — the `org.springframework.boot` plugin is
deliberately **not** applied.

## What it auto-configures

Every bean below is `@ConditionalOnMissingBean` (declare your own to override) and the whole
configuration is gated by `stand.test.enabled` (default `true`).

| Bean | Condition | Notes |
|------|-----------|-------|
| `ScenarioValidator` | always | `DefaultScenarioValidator` (structural + guardrail checks) |
| `EnvironmentRegistry` | always | built from `stand.test.environments.*` (empty when none) |
| `RestStepExecutor` | `stand-test-rest` on classpath | collected into the runner |
| `KafkaStepExecutor` | `stand-test-kafka` on classpath | collected into the runner |
| `DbStepExecutor` | `stand-test-db` on classpath | collected into the runner |
| `ReportingEventPublisher` | Allure on classpath & `reporting.allure.enabled` | else the no-op publisher |
| `ScenarioRunner` | always | `DefaultScenarioRunner` over all `StepExecutor` beans |
| `StandClient` | always | `DefaultStandClient` facade |
| `Awaiter` | `stand-test-await` on classpath | fresh system-backed awaiter |
| `AwaitPolicy` | `stand-test-await` on classpath | reusable default from `stand.test.await.*` |

## What it does **not** do

The starter is an integration module only. It contains **no** transport or business logic and never
depends back on the modules it wires. It does not implement REST/Kafka/DB/gRPC clients, the JUnit
extension, the Allure mapping, the YAML parser or the AI schema — those live in their own modules; the
starter merely collects their existing beans. It ships no real stand config, no endpoints and no
secrets: only `*Ref` **names** that the adapters resolve (from the OS environment) at run time.

## Add the dependency

The starter forces **only `stand-test-core`**. Every adapter/reporting/await module is an *optional*
(`compileOnly`) dependency — it is **not** pulled transitively — so you add just the ones you use and
keep heavy transitive deps (spring-webflux, kafka-clients, allure-java-commons) off your classpath
unless you asked for them:

```kotlin
testImplementation("ru.alfa.stand.test:stand-test-spring-boot-starter:<version>")

// add only what this test project actually exercises:
testImplementation("ru.alfa.stand.test:stand-test-rest:<version>")     // → RestStepExecutor bean
testImplementation("ru.alfa.stand.test:stand-test-db:<version>")       // → DbStepExecutor bean
testImplementation("ru.alfa.stand.test:stand-test-allure:<version>")   // → Allure reporting publisher
testImplementation("ru.alfa.stand.test:stand-test-await:<version>")    // → Awaiter / AwaitPolicy beans
```

Each corresponding bean appears **only** when its module is on the classpath (`@ConditionalOnClass`); a
module you don't add simply contributes nothing. With no adapters at all the `StandClient`/runner still
wire, just with an empty executor list. gRPC is **not** wired yet (the adapter is still a skeleton).

## Example `application-test.yml`

```yaml
stand:
  test:
    enabled: true
    await:
      timeout: 30s
      poll-interval: 500ms
    reporting:
      enabled: true
      allure:
        enabled: true
    environments:
      ift:
        services:
          client-service:
            base-url-ref: CLIENT_SERVICE_URL
            correlation: { source: HEADER, name: X-Correlation-Id }
        datasources:
          main-db:
            url-ref: MAIN_DB_URL
            user-ref: MAIN_DB_USER
            password-ref: MAIN_DB_PASSWORD
            allowed-schemas: [test_data]
            write-allowed: true
        topics:
          events:
            name: ift.events.v1
            correlation: { source: KEY, name: X-Correlation-Id }
        grpc-targets:
          accounts:
            target-ref: ACCOUNTS_GRPC
        kafka-cluster:
          bootstrap-servers-ref: KAFKA_BOOTSTRAP
```

> **The `*Ref` values are environment-variable names, never values.** `CLIENT_SERVICE_URL`,
> `MAIN_DB_PASSWORD`, `KAFKA_BOOTSTRAP` etc. are resolved by the adapters from the OS environment at
> execution time — the SDK keeps endpoints and secrets out of source.

## Disable the starter

```yaml
stand:
  test:
    enabled: false
```

With `stand.test.enabled=false` the auto-configuration contributes **no** beans — wire the SDK manually
(or via `@StandTest` from `stand-test-junit`) instead.

## Override a bean

Any bean is overridable — just declare your own of the same type:

```java
@Bean
StandClient standClient(ScenarioRunner runner) {
    return new DefaultStandClient(runner);
}

@Bean
ReportingEventPublisher reportingEventPublisher() {
    return new MyReportingEventPublisher();
}
```

## Optional integrations — required classpath

| Feature | Needs on classpath |
|---------|--------------------|
| REST steps | `stand-test-rest` |
| Kafka steps | `stand-test-kafka` |
| DB steps | `stand-test-db` |
| gRPC steps | `stand-test-grpc` *(not wired yet — adapter is a skeleton)* |
| Allure reporting | `stand-test-allure` |
| `Awaiter` / `AwaitPolicy` beans | `stand-test-await` |

These modules are **not** pulled transitively (they are `compileOnly` for the starter) — add the ones
you need explicitly. Omitting a module drops the corresponding bean (its `@ConditionalOnClass` skips it);
`stand-test-core` is the only module the starter forces.

## Why no REST/Kafka/DB/gRPC logic here

Transport logic lives in the adapter modules so the dependency graph stays one-way and acyclic
(`starter → adapters → core`, never the reverse) and `stand-test-core` stays Spring-free. The starter
only **collects** the adapter beans; duplicating client logic here would fork behaviour and violate the
module graph (docs/arch §4/§5).

## Troubleshooting

- **`@Autowired StandClient` fails on `run(...)` with an unknown-environment error** — the environment
  registry is empty. The runtime guardrail rejects any environment not declared under
  `stand.test.environments.*`, so at least one environment is effectively mandatory. Configure one.
- **Context fails to start with `Invalid stand.test.environments.<env> configuration: ... must not be
  blank`** — a `*Ref` (or a nested `correlation.source`/`name`) is missing. Every reference is required
  and must be a non-blank env-var name.
- **No beans appear at all** — check `stand.test.enabled` is not `false`, and that the auto-configuration
  is on the classpath (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`).
- **A step type has "no registered executor"** — the matching adapter module is not on the classpath
  (or was excluded). Add `stand-test-rest`/`-kafka`/`-db`.
- **Reporting doesn't reach Allure** — ensure `stand-test-allure` is present and
  `stand.test.reporting.allure.enabled` is not `false`; otherwise the no-op publisher is used.
