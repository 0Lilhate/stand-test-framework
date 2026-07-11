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
| Kafka await | `KafkaStep.expect(topicAlias).correlationIdFromContext().withinSeconds(n).assertPath(p, v).capture(var, p)` — equals-only; same scenario as the trigger; each expect consumes its match. A per-run discriminator is REQUIRED: `correlationIdFromContext()` or a per-run `.key("${testRunId}...")` — a constant `.key(...)` alone is refused at run time (a constant key is allowed only alongside `correlationIdFromContext()`) |
| DB probe/capture | `DbStep.query(dsAlias).sql("SELECT ...").param(k, v).capture(var, "column")` — first row, by column label; 0 rows under capture = infra error (not for absence checks) |
| DB await | `DbStep.expectEventually(dsAlias).sql("SELECT ...").param(...).expectValue(v).withinSeconds(n)` — single row/value, equals-only; >1 row aborts immediately |
| DB seed | `DbStep.seed(dsAlias).sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)").taggedByTestRunId("test_run_id").param("id", "row-${testRunId}")` — `:testRunId` auto-bound; **`taggedByTestRunId("<col>")` is REQUIRED** and must name a column present in the INSERT list (the same column the cleanup filters) — else refused at run time; derive the PK from `${testRunId}` so concurrent seeds never collide; schema-qualified 2-part target in `allowed-schemas`, datasource `write-allowed: true` |
| DB cleanup | `DbStep.cleanup(dsAlias).sql("DELETE FROM test_data.orders").whereTestRunId("test_run_id")` — SQL must carry **no WHERE of its own**; SDK appends `WHERE <col> = :testRunId`; `<col>` must equal the paired seed's `taggedByTestRunId` column |
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
8a. **Per-run values — no stale statics.** Follow the design's field classes (KB `valueHints` /
    rule 11 of scenario-design): *run-unique* fields derive from `${testRunId}`
    (`"client-${testRunId}"`) or a capture — a repeating literal collides on the second run;
    *date* fields are computed in plain Java locals ABOVE the builder and concatenated into the
    body — never a hardcoded calendar date:
    `String begDate = java.time.LocalDate.now().toString();` … `.body("{\"begDate\":\"" + begDate + "\",…}")`
    (pure computation is not eager IO — the builder still only builds). Prefer
    `${testRunId}`-derived uniqueness over `UUID.randomUUID()`: run-scoped values are traceable
    back to the run in logs and DB rows, random ones are not. *Constant* fields come verbatim
    from the case/KB. This `${testRunId}`-scoping is exactly what makes the test PARALLEL-SAFE:
    concurrent runs on a shared stand get distinct ids and never collide.
9. **Do not catch** `StandTestAssertionError`/`StandTestException`; happy path asserts
   `result.isSuccessful()`, expected failure wraps `stand.run` in `assertThatThrownBy`.
10. **Gate the test**: `@EnabledIfEnvironmentVariable(named = "<a base-url env var>", matches = ".+")`
    so it skips (not fails) without stand configuration.
11. **Parallel-safe by construction.** No shared mutable static or instance state in the test class —
    every run-varying value flows through captures / `${testRunId}`; the runner and each step
    executor are shared across test threads, so a static field or a reused mutable object would race.
    Do NOT add `@StandParallelSafe` by default: a `${testRunId}`-scoped test is already safe, and the
    consumer's `junit-platform.properties` runs classes concurrently (methods same_thread). Use the
    SDK facades (package `ru.alfa.stand.test.junit`) only for exceptions — a class that touches a
    resource which cannot be `testRunId`-isolated (a fixed port, a shared file, a process-wide
    singleton): `@StandIsolated` (`@Isolated` — run the class alone), `@StandSerial`
    (`@Execution(SAME_THREAD)`), or JUnit's native `@ResourceLock("<alias>")` (mutual exclusion by
    named resource; there is no `@StandResourceLock` wrapper). `@StandParallelSafe`
    (`@Execution(CONCURRENT)`) only opts a class's METHODS into concurrency — rarely needed.

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
- [ ] Seed rows carry `test_run_id` → `:testRunId`; every seed declares `taggedByTestRunId("<col>")`
      naming the same column its cleanup filters; every seed has a cleanup.
- [ ] Every `kafka.expect` has a per-run discriminator (`correlationIdFromContext` or a
      `${testRunId}`-derived key) — no constant-key-only expect.
- [ ] No stale statics: every run-unique field is `${testRunId}`/capture-derived; every date
      field is computed, not a calendar literal (rule 8a / KB `valueHints`).
- [ ] Parallel-safe: no shared mutable static/instance state in the test class; `@StandIsolated`/
      `@ResourceLock` present only if a resource cannot be `testRunId`-isolated (rule 11).
- [ ] Run `stand-test-safety-review`.

## Output

One JUnit test class in the consumer project's test sources (+ fixtures if
`bodyFromResource`/`requestFromResource` used). Template:
[`java-test-template.java`](../stand-test-java-dsl-authoring/java-test-template.java). Worked example:
[`example-generated.java`](../stand-test-java-dsl-authoring/example-generated.java).
Real-world imitation corpus: `stand-test-example` module
(`FullStandTestFrameworkExampleTest` is the canonical composition).
