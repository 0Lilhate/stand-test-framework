# stand-test-example

**Group:** examples · **Gradle plugin:** `java-library` · **Internal dependencies (test):** `stand-test-core`, `stand-test-junit`, `stand-test-rest`, `stand-test-db`, `stand-test-kafka`, `stand-test-allure`

Technical **usage examples** for the stand-test SDK (Iteration 8). They show how a consuming team writes
scenarios with the SDK and run green offline through the public API against in-process doubles — there is
**no business logic and no real-stand configuration** here.

## What this module shows

The examples live in `src/test/java` (there is no production code):

| Example | Demonstrates |
| --- | --- |
| `RestExampleTest` | `RestStep.post` — inject the SDK correlation id, assert status + JSON path, capture a value. |
| `DbExampleTest` | `DbStep.seed` → `expectEventually` → `cleanup` (await + `testRunId`-scoped write/cleanup). |
| `RestToDbExampleTest` | a value captured from REST flowing into later DB steps via the per-run variable store. |
| `NegativeTimeoutExampleTest` | the await/timeout path — `expectEventually` times out as a `StandTestAssertionError` (no `Thread.sleep`). |
| `ReportingExampleTest` | how the Allure adapter renders a run (steps, status, labels, parameters, diagnostics). |
| `StandTestExampleTest` | the canonical `@StandTest` path — inject a `StandClient` whose `EnvironmentRegistry` and Allure publisher are discovered via `ServiceLoader`, then run the REST→DB scenario. |
| `KafkaExampleTest` | `KafkaStep.send` → `expect` — inject the SDK correlation header, match it, JSON-path assert + capture. **Needs a broker** (tagged `requires-broker`, excluded from the default run). |

## Execution model (why it runs offline)

Each example runs the real SDK as a black box against an in-process **double** — these are not
Testcontainers and are allowed for SDK self-tests (plan §16):

- **REST** → JDK `com.sun.net.httpserver.HttpServer` (`ExampleHttpServer`) on an ephemeral loopback
  port. The executor is wired with the public passthrough `BaseUrlResolver` seam
  (`new RestStepExecutor(new WebClientHttpCaller(), ref -> ref)`), so the registry's `baseUrlRef` is the
  live server URL — no environment variable, no fixed port.
- **DB** → H2 in-memory (`ExampleH2`). The default `DbStepExecutor()` resolves the datasource refs from
  the **process environment** (its seam ctor is package-private, so env-ref is the cross-module path).
  The module's `build.gradle.kts` sets the refs for the test JVM:
  `MAIN_DB_URL` / `MAIN_DB_USER` / `MAIN_DB_PASSWORD`.
  The H2 schema/table is created **out-of-band** in `@BeforeAll` (DDL is forbidden through the SDK
  write-guard, plan §8.8), then the scenarios only seed/query/expect/cleanup.

Endpoints are never hardcoded in the scenarios — they come from the `EnvironmentRegistry`
(`ExampleStand`), exactly as a real consumer would whitelist aliases.

### The `@StandTest` path (Phase 2)

`StandTestExampleTest` shows the canonical consumer path: `@StandTest` injects a ready `StandClient` (no
hand-built runner) whose `EnvironmentRegistry` and Allure publisher are discovered via `ServiceLoader`.
Two differences from the manual-runner examples above:

- **Registry via SPI** — `ExampleEnvironmentRegistry` (registered in
  `META-INF/services/…EnvironmentRegistry`) defines the `ift` environment. Because it is instantiated by a
  no-arg constructor it cannot know a runtime port, so the REST alias stores an **env-ref**
  (`baseUrlRef = "CLIENT_SERVICE_URL"`) that the default `RestStepExecutor` resolves via `System.getenv`.
- **Fixed REST port** — the double must therefore listen on a stable address. `build.gradle.kts` sets
  `CLIENT_SERVICE_URL=http://127.0.0.1:18080` (override in CI with `-PexampleRestPort=NNNN` to avoid
  collisions), and `ExampleDoublesExtension` starts the HTTP double + H2 schema once per class, binding
  the port parsed from that env var.

### The Kafka example (requires a broker)

`KafkaExampleTest` shows `KafkaStep.send` → `expect`. Unlike REST/DB it **cannot** run against an
in-process double: `kafka.expect` arms a real `KafkaConsumer` and the fake client factory is
package-private to the adapter's own tests. So it is tagged `@Tag("requires-broker")` and **excluded from
the default run** — the offline build only compiles it. The scenario is a self-contained round-trip on one
topic: `send` publishes a message with the SDK correlation id injected as a header, and `expect` (whose
consumer the runner armed at the log end during `prepare`) matches it by that id, asserts `$.status` and
captures `$.entityId`.

Run it against a reachable broker (the topic must already exist, or the broker must auto-create topics):

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ./gradlew :stand-test-example:test -PincludeRequiresBroker
# override the broker address with -PkafkaBootstrapServers=host:port
```

## Running

```bash
./gradlew :stand-test-example:test     # all examples, green offline (no external stand)
./gradlew :stand-test-example:build
./gradlew :stand-test-example:test -PexampleRestPort=18099   # override the @StandTest REST port (CI)
```

This module is build-only: it produces no published artifact and is excluded from the coverage gate
(there is no production code to cover). Checkstyle still applies to the example sources.

## Switching to a real DEV/IFT stand

The scenarios are unchanged against a real stand — only the wiring differs:

- point the datasource refs at the real stand by setting `MAIN_DB_URL`/`MAIN_DB_USER`/`MAIN_DB_PASSWORD`
  (and use the default `EnvironmentBaseUrlResolver` for REST, whose `baseUrlRef` is an env-var name);
- never put stand URLs or secrets in source — only env refs resolved at run time (plan §9/§20).
