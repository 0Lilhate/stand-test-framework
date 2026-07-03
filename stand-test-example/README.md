# stand-test-example

**Group:** examples · **Gradle plugin:** `java-library` · **Internal dependencies (test):** `stand-test-core`, `stand-test-await`, `stand-test-junit`, `stand-test-rest`, `stand-test-db`, `stand-test-kafka`, `stand-test-grpc`, `stand-test-allure`, `stand-test-config`, `stand-test-scenario-yaml`, `stand-test-ai-schema`, `stand-test-spring-boot-starter`

Technical **usage examples and a verification module** for the stand-test SDK (Iteration 8). They show
how a consuming team writes scenarios with the SDK, prove the published modules compose into one working
pipeline, and run green offline through the public API against in-process doubles — there is
**no business logic and no real-stand configuration** here. The examples demonstrate SDK composition;
they are not a 1-to-1 template for a business test (a real test keeps the same scenario shape but points
the registry refs at a real DEV/IFT stand).

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
| `FullStandTestFrameworkExampleTest` | **the composition proof**: one scenario through model → validator → runner → `StepExecutor` SPI (REST + DB + gRPC + a test-only variable-snapshot probe) → await (`db.expectEventually`) → variable capture/`${…}` resolve → correlation propagation (REST header **and** gRPC metadata carry the same SDK-owned id) → Allure mapping; plus per-run `VariableStore` isolation (a second run starts empty, fresh `testRunId`) and a deterministic `Awaiter` demo on a fake `TimeSource` (3 poll attempts, zero wall-clock time). |
| `FrameworkFailureSemanticsExampleTest` | failure semantics — an unmet step assertion surfaces as `StandTestAssertionError` (an `AssertionError`, so JUnit fails the test) while the Allure side-channel still renders the step FAILED with its diagnostics attachment. |
| `StandTestSpringBootStarterExampleTest` | the Spring consumer path — `ApplicationContextRunner` over the starter's auto-configuration: beans by default, nothing on `stand.test.enabled=false`, `stand.test.environments.*` binding, user bean wins. Offline, no bootable app. |
| `AiSchemaParityTest` | AI-format guardrail parity — the canonical/gRPC documents pass the `stand-test-ai-schema` JSON Schema **and** parse into validator-clean scenarios, while `ai/invalid-flow.json` (destructive step type, hardcoded URL, unbounded timeout) is rejected by the schema. No runner involved. |
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

- **gRPC** → an in-JVM `io.grpc` server (`ExampleGrpcServer`) with the bundled Health service + Server
  Reflection on a loopback port pinned by the `GRPC_TARGET` env-ref. It also records the ASCII metadata
  of the last call, so the composition example can assert the SDK injected its correlation id.

Endpoints are never hardcoded in the scenarios — they come from the `EnvironmentRegistry`
(`ExampleStand`), exactly as a real consumer would whitelist aliases.

### What is a fake here — and why only in `src/test`

All doubles and probes (`ExampleHttpServer`, `ExampleGrpcServer`, `ExampleH2`,
`CapturingAllureLifecycleFacade`, `VariableSnapshotProbe`, `AdvancingTimeSource`) are **test classes of
this module only**: `src/main/java` stays empty, nothing is published, and no fake can leak onto a
consumer's classpath or into the SDK modules. They plug into *public SDK seams* (the `StepExecutor` SPI,
the Allure lifecycle facade, the await `TimeSource`) and never re-implement adapter logic.
`VariableSnapshotProbe` exists because the per-run `VariableStore` is deliberately owned by the runner
and not exposed on `ScenarioResult` — a custom executor is the sanctioned way to observe it.

### SDK modules in this example

| Module | Used | How |
| --- | --- | --- |
| `stand-test-core` | yes | model/builder, validator, runner, SPI, results, events — every test |
| `stand-test-await` | yes | inside `db.expectEventually`/timeouts + directly (`Awaiter` on a fake `TimeSource`) |
| `stand-test-junit` | yes | `@StandTest` injection path (`StandTestExampleTest`) |
| `stand-test-rest` | yes | `RestStep` + real `RestStepExecutor` against the HTTP double |
| `stand-test-db` | yes | `DbStep` seed/expectEventually/cleanup against H2 |
| `stand-test-grpc` | yes | `grpc.unary` over reflection against the local gRPC double |
| `stand-test-kafka` | partially | compile + tagged `requires-broker` test only: `kafka.expect` arms a real consumer, no offline double exists |
| `stand-test-allure` | yes | `AllureReportingEventPublisher` over a capturing lifecycle facade |
| `stand-test-config` | yes | `FileEnvironmentRegistry` SPI provider loads `application.yml` for `@StandTest` |
| `stand-test-scenario-yaml` | yes | `AiScenarioParser` (AI-format documents → core `Scenario`) |
| `stand-test-ai-schema` | yes | shipped JSON Schema validates the valid/invalid example documents |
| `stand-test-spring-boot-starter` | yes | `ApplicationContextRunner` context checks (no bootable app) |

### The `@StandTest` path (Phase 2)

`StandTestExampleTest` shows the canonical consumer path: `@StandTest` injects a ready `StandClient` (no
hand-built runner) whose `EnvironmentRegistry` and Allure publisher are discovered via `ServiceLoader`.
Two differences from the manual-runner examples above:

- **Registry via `application.yml`** — the `ift` environment is declared in the familiar
  `src/test/resources/application.yml` under `stand.test.environments` (the full surface: services,
  datasources, topics, grpc-targets, kafka-cluster) and loaded by `stand-test-config`'s
  `FileEnvironmentRegistry` SPI provider — the exact wiring a real consumer uses, no Java registry code.
  The same tree binds natively in a Spring Boot project via the starter. `*-ref` fields hold env-var
  references — a bare NAME or the `${NAME:default}` placeholder (the env var wins when set; the inline
  default is a conscious value-in-repo trade-off for non-secret DEV endpoints); a bare URL or secret
  value is rejected fail-closed. Note the SDK allows exactly ONE `EnvironmentRegistry` provider on the
  classpath — a second (hand-written) provider next to `stand-test-config` fails loudly.
- **Fixed REST port** — the double must therefore listen on a stable address. `build.gradle.kts` sets
  `CLIENT_SERVICE_URL=http://127.0.0.1:18080` (override in CI with `-PexampleRestPort=NNNN` to avoid
  collisions), and `ExampleDoublesExtension` starts the HTTP double **once per run** (an engine-root
  store `CloseableResource`, matching the engine-root scope of the cached `StandClient`) plus the
  idempotent H2 schema bootstrap per class — so any number of `@StandTest` classes can share the cached
  client safely.

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
./gradlew :stand-test-example:test -PexampleGrpcPort=18091   # override the gRPC double port (CI)
```

Reading order for a first-time consumer: start with `RestExampleTest` (one step), then
`RestToDbExampleTest` (variables across transports), then `FullStandTestFrameworkExampleTest` (the whole
pipeline at once), then the `@StandTest`/starter tests for the two wiring styles (ServiceLoader vs
Spring beans).

This module is build-only: it produces no published artifact and is excluded from the coverage gate
(there is no production code to cover). Checkstyle still applies to the example sources.

## Switching to a real DEV/IFT stand

The scenarios are unchanged against a real stand — only the wiring differs:

- point the datasource refs at the real stand by setting `MAIN_DB_URL`/`MAIN_DB_USER`/`MAIN_DB_PASSWORD`
  (and use the default `EnvironmentBaseUrlResolver` for REST, whose `baseUrlRef` is an env-var name);
- never put stand URLs or secrets in source — only env refs resolved at run time (plan §9/§20).
