# stand-test-sdk

An internal Java **test SDK** for writing integration/e2e autotests against **real DEV/IFT stands**.
It is a thin facade over mature tools (WebClient, `kafka-clients`, JDBC, gRPC, JUnit 5, Allure) that
gives every team one consistent way to describe a scenario, wait for asynchronous effects (no
`Thread.sleep`), correlate calls (`scenarioId`/`testRunId`/`correlationId` are SDK-owned) and report
results — plus a constrained declarative format safe for AI-generated tests.

Both DSL inputs converge on one immutable model; only that model is executed:

```
Java DSL (lazy builder) ─┐
                         ├─▶ Scenario Model ─▶ ScenarioValidator ─▶ ScenarioRunner ─▶ StepExecutor SPI ─▶ adapters ─▶ real DEV/IFT stand
YAML DSL ────────────────┘
```

The architectural source of truth is [docs/arch/stand-test-sdk-implementation-plan.md](docs/arch/stand-test-sdk-implementation-plan.md).

## Modules

| Module | What it is |
|--------|------------|
| [stand-test-core](stand-test-core/README.md) | Scenario model, SPI, validation, guardrails, result/event models — JDK-only, no adapter deps |
| [stand-test-await](stand-test-await/README.md) | The single await mechanism (deterministic polling, injectable time source) |
| [stand-test-junit](stand-test-junit/README.md) | `@StandTest` JUnit 5 extension — injects a `StandClient` assembled via `ServiceLoader` |
| [stand-test-rest](stand-test-rest/README.md) | REST steps (`RestStep`), correlation header injection, JSON assertions/captures |
| [stand-test-kafka](stand-test-kafka/README.md) | Kafka steps (`kafka.send`/`kafka.expect`), header correlation, bounded clients |
| [stand-test-db](stand-test-db/README.md) | DB probe/assert steps with fail-closed SQL write guard |
| [stand-test-grpc](stand-test-grpc/README.md) | gRPC unary steps via server reflection + `DynamicMessage`, mandatory deadline |
| [stand-test-allure](stand-test-allure/README.md) | Maps SDK reporting events to Allure (steps, labels, parameters, attachments) |
| [stand-test-config](stand-test-config/README.md) | File-based `EnvironmentRegistry` (`stand-test-environments.yml`) — the SPI provider for plain JUnit |
| [stand-test-spring-boot-starter](stand-test-spring-boot-starter/README.md) | Boot 3 auto-configuration: `@Autowired StandClient`, environments from `application.yml` |
| [stand-test-scenario-yaml](stand-test-scenario-yaml/README.md) | YAML DSL (given/then and AI steps/type surfaces) over the same model |
| [stand-test-ai-schema](stand-test-ai-schema/README.md) | JSON Schema + generation rules for safe AI-generated scenarios |
| [stand-test-bom](stand-test-bom/README.md) | `java-platform` BOM — version alignment for consumers |
| [stand-test-example](stand-test-example/README.md) | Test-only showcase on offline doubles (not published) — the living quick start |

## Quick start (plain JUnit, no Spring)

**1. Add the dependencies** (all modules share one version via the BOM):

```kotlin
testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))
testImplementation("ru.alfa.stand.test:stand-test-junit")
testImplementation("ru.alfa.stand.test:stand-test-rest")    // + -kafka / -db / -grpc as needed
testImplementation("ru.alfa.stand.test:stand-test-config")  // file-based environment registry
testImplementation("ru.alfa.stand.test:stand-test-allure")  // optional: Allure reporting
```

**2. Describe the stand** in `src/test/resources/stand-test-environments.yml`. Every `*-ref` is an
**environment-variable name, never a value** — endpoints and secrets stay out of source:

```yaml
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
```

**3. Write the first test.** `@StandTest` injects a `StandClient` assembled from the adapters on the
classpath — no wiring code:

```java
@StandTest
@StandEnv("ift")
@StandScenarioId("payment-flow")
class PaymentFlowTest {

    @Test
    void createsRequest(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
        Scenario scenario = Scenario.builder(id)
                .environment(env)
                .step(RestStep.post("client-service", "/api/requests")
                        .body("{\"amount\":1}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .capture("requestId", "$.requestId")
                        .build())
                .step(DbStep.expectEventually("main-db")
                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                        .param("id", "${requestId}")
                        .expectValue("NEW")
                        .withinSeconds(30)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);   // Validator -> Runner -> StepExecutor SPI

        assertThat(result.isSuccessful()).isTrue();
    }
}
```

Assertion mismatches surface as `StandTestAssertionError` (an `AssertionError` — a native JUnit
failure); infrastructure/config problems as `StandTestException`. Run the test with the referenced
environment variables set (`CLIENT_SERVICE_URL`, `MAIN_DB_URL`, …).

## Quick start (Spring Boot)

Add `stand-test-spring-boot-starter` plus the adapters you use, declare the environments under
`stand.test.environments.*` in `application.yml`, and `@Autowired StandClient` — see the
[starter README](stand-test-spring-boot-starter/README.md) for the full `application.yml` example,
bean override rules and troubleshooting. `stand.test.enabled: false` switches the whole
auto-configuration off.

## AI-generated scenarios

`stand-test-ai-schema` ships the JSON Schema and the
[generation rules](stand-test-ai-schema/src/main/resources/ai/stand-test-ai-generation-rules.md) an
LLM needs to produce safe declarative scenarios; `stand-test-scenario-yaml` parses that format into
the same validated model. Guardrails (whitelisted environments only, no raw URLs, no inline secrets,
no destructive SQL, bounded timeouts) derive from the single `ForbiddenOperation` source of truth and
are re-enforced at runtime by the validator.

## Build

```bash
./gradlew build                # compile + checkstyle (zero-tolerance) + tests + JaCoCo 80% gate
./gradlew publishToMavenLocal  # local publish of all modules + BOM
```

Toolchain is Java 24, bytecode targets **Java 17** (`--release 17`) — artifacts load on consumer
JDK 17/21/24. Publishing to the internal repository is parameterized via `standTestPublish*`
properties / `STAND_TEST_PUBLISH_*` env vars — see [docs/publishing.md](docs/publishing.md).

## Known limitations

- gRPC supports **unary** calls only (server reflection + `DynamicMessage`).
- The runtime value matcher is `equals`-based; richer matchers accepted by the AI schema are validated
  but not yet executed.
- The Kafka example in `stand-test-example` needs a real broker and is tagged
  `requires-broker` (excluded from the default run); all module unit tests run offline.
- A first publish to the internal Nexus/Artifactory has not happened yet (repository URL pending).
- Scenarios run only against environments declared in the registry — there is no escape hatch by
  design.

## Where to look next

`stand-test-example` is the living, compiling showcase: REST → DB → gRPC composition, correlation
propagation, per-run variable isolation, await on a fake time source, Allure output and failure
semantics — all on offline doubles.
