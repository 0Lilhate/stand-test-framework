---
name: stand-test-java-dsl-authoring
description: Generate a JUnit 5 test on the stand-test-sdk lazy Java DSL (Scenario.builder + RestStep/KafkaStep/DbStep/GrpcStep, injected StandClient, expectEventually awaits, captures/${var}, testRunId-scoped data, no eager IO, no validator bypass). The DEFAULT authoring track for stand autotests. Use when converting a scenario design into a Java test.
---

# Skill: stand-test-java-dsl-authoring

Generate a JUnit 5 test class from a `ScenarioDesign.md` using the SDK's lazy Java DSL. This is
the **default authoring track** — it covers the full SDK surface.

## When to use

Track = Java DSL in the design (any case needing `db.seed`/`db.cleanup`, `rest.put`/`delete`,
gRPC custom metadata, negative paths, or non-equals checks outside REST).

## The only sanctioned shape

Build an immutable `Scenario`, hand it to an injected `StandClient`. Nothing else executes IO.

**Spring Boot consumer (starter on classpath):**

```java
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ORDER_SERVICE_URL", matches = ".+")
class OrderStatusProjectionTest {

    @Autowired
    private StandClient stand;

    @Test
    @DisplayName("Order status projection reaches DONE after creation")
    void orderStatusProjection() {
        Scenario scenario = Scenario.builder("order-status-projection")
                .environment("ift")
                .tag("integration")
                .step(RestStep.post("order-service", "/api/orders")
                        .id("create-order")
                        .header("Content-Type", "application/json")
                        .body("{\"amount\":100,\"externalId\":\"${testRunId}\"}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .capture("orderId", "$.orderId")
                        .build())
                .step(DbStep.expectEventually("orders-db")
                        .id("await-projection")
                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                        .param("id", "${orderId}")
                        .expectValue("DONE")
                        .withinSeconds(30)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
```

**Plain JUnit consumer (`stand-test-junit` + `stand-test-config`):** identical scenario code,
wiring via `@StandTest` on the class and parameters
`(StandClient stand, @StandScenarioId String id, @StandEnv String env)`. Note: the injected
strings do NOT auto-flow into the builder — pass them explicitly to
`Scenario.builder(id).environment(env)`.

## Step builder crib

| Need | API |
|---|---|
| REST call | `RestStep.get/post/put/delete("<service-alias>", "/path")` + `.query(k,v)` `.header(k,v)` `.body(json)`/`.bodyFromResource("fixtures/x.json")` (mutually exclusive) `.expectStatus(int)` |
| REST assertions | `.assertPath(p, v)` EQUALS · `.assertPathContains(p, v)` · `.assertPathMatches(p, regex)` full-match · `.assertPathExists(p)`/`.assertPathAbsent(p)` (JSON null counts as present) · `.assertPathNotNull(p)`/`.assertPathIsNull(p)` |
| REST polling | `RestStep.expectEventually(alias, path)` + `.withinSeconds(n)` `.pollInterval(Duration)` — GET-only, needs ≥1 expectation, no body; 5xx polls through, transport error = infra abort, captures from final response only |
| Kafka publish | `KafkaStep.send(topicAlias).body(json)`/`.bodyFromResource(...)` `.key(v)` `.header(k,v)` `.injectCorrelationId()` |
| Kafka await | `KafkaStep.expect(topicAlias).correlationIdFromContext().withinSeconds(n).assertPath(p, v).capture(var, p)` — equals-only; same scenario as the trigger; each expect consumes its match |
| DB probe/capture | `DbStep.query(dsAlias).sql("SELECT ...").param(k, v).capture(var, "column")` — first row, by column label; 0 rows under capture = infra error (not for absence checks) |
| DB await | `DbStep.expectEventually(dsAlias).sql("SELECT ...").param(...).expectValue(v).withinSeconds(n)` — single row/value, equals-only; >1 row aborts immediately |
| DB seed | `DbStep.seed(dsAlias).sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)").param("id", "${...}")` — `:testRunId` is auto-bound; schema-qualified 2-part target in `allowed-schemas`, datasource `write-allowed: true` |
| DB cleanup | `DbStep.cleanup(dsAlias).sql("DELETE FROM test_data.orders").whereTestRunId("test_run_id")` — SQL must carry **no WHERE of its own**; SDK appends `WHERE <col> = :testRunId` |
| gRPC call | `GrpcStep.unary(targetAlias).method("pkg.Service/Method").request(json)`/`.requestFromResource(...)` `.metadata(k, v)` `.injectCorrelationId()` `.withinSeconds(n)` (deadline MANDATORY) `.assertPath(p, v)` `.capture(var, p)` — equals-only; enums assert as protobuf JSON names (`"SERVING"`) |
| Data flow | `.capture("var", "$.jsonPath")` → `${var}` in later path/query/header/body/param values. Built-ins: `${scenarioId}` `${testRunId}` `${correlationId}` `${environment}`. Syntax `${name}` only |
| Negative path | `assertThatThrownBy(() -> stand.run(scenario)).isInstanceOf(StandTestAssertionError.class).hasMessageContaining("...")` |

## Hard rules

1. **No IO in builders / no eager fluent execution** — the builders are lazy by contract
   (`IMPERATIVE_EAGER_IO`); the only execution point is `stand.run(scenario)`.
2. **Never construct the runner manually.** `new DefaultScenarioRunner(List.of(...))` wires an
   EMPTY environment registry that rejects every environment. Use the injected `StandClient`
   (starter or `@StandTest`).
3. **Never disable or replace the validator.** It runs inside `run()`; a Spring
   `ScenarioValidator` bean override is a prohibited bypass.
4. **No `Thread.sleep`, no Awaitility, no manual retry loops** — only `*.expectEventually` /
   `KafkaStep.expect`; for a rare ad-hoc wait inject `Awaiter` (plain-JUnit path) with a
   bounded `AwaitPolicy` and `.orElseThrow()`.
5. **No raw clients** — no WebClient/RestTemplate/KafkaProducer/KafkaConsumer/DriverManager/
   generated gRPC stubs in test code (`RAW_KAFKA_CLIENT`/`RAW_JDBC_CLIENT`).
6. **No secrets, no `Authorization` headers, no header names matching**
   `authorization|token|password|secret|api[-_]?key|cookie` — auth is registry-driven
   (`AuthConfig` BASIC/BEARER refs); the validator rejects violations (`SECRET_IN_SOURCE`).
7. **Aliases only** — service/topic/datasource/target strings must exist in the registry;
   never URLs (`HARDCODED_STAND_URL`). There is no API taking a URL — do not invent one.
8. **Ids**: explicit `.id("...")` on every step; unique within the scenario.
9. **Do not catch** `StandTestAssertionError`/`StandTestException`; happy path asserts
   `result.isSuccessful()`, expected failure wraps `stand.run` in `assertThatThrownBy`.
10. **Gate the test**: `@EnabledIfEnvironmentVariable(named = "<a base-url env var>", matches = ".+")`
    so it skips (not fails) without stand configuration.

## Code style (checkstyle-enforced in SDK-style repos)

- AssertJ only: `import static org.assertj.core.api.Assertions.*` —
  `org.junit.jupiter.api.Assertions` and JUnit 4 `org.junit.Test` are banned imports.
- No `System.out`/`System.err`; no `@NotNull`/`@Nullable` annotations (non-JetBrains).
- One statement per line; blank line between members; JUnit 5 `@Test` + `@DisplayName`.
- Keep sources Java-17 compatible (SDK targets `--release 17`).

## Self-check before handing off

- [ ] Compiles: `./gradlew compileTestJava` (consumer project) — and checkstyle if wired.
- [ ] Every step from `ScenarioDesign.md` is present, in order, with its timeout.
- [ ] grep clean: `Thread.sleep|Awaitility|http://|https://|jdbc:|Authorization|new DefaultScenarioRunner`.
- [ ] Seed rows carry `test_run_id` → `:testRunId`; every seed has a cleanup.
- [ ] Run `stand-test-safety-review`.

## Output

One JUnit test class in the consumer project's test sources (+ fixtures if
`bodyFromResource`/`requestFromResource` used). Template:
[`java-test-template.java`](../stand-test-java-dsl-authoring/java-test-template.java). Worked example:
[`example-generated.java`](../stand-test-java-dsl-authoring/example-generated.java).
Real-world imitation corpus: `stand-test-example` module
(`FullStandTestFrameworkExampleTest` is the canonical composition).
