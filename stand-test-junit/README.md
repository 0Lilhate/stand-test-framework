# stand-test-junit

**Group:** core · **Gradle plugin:** `java-library`

The JUnit 5 integration layer — the bridge between the JUnit lifecycle and the SDK, without Spring.

**Internal dependencies:** `stand-test-core`, `stand-test-await` (target graph, docs/arch §4/§5).

## What it does

- **`@StandTest`** — a meta-annotation that wires `StandTestExtension` (`@ExtendWith`). Place it on a
  test class or method. Optional `env()` is the lowest-precedence environment source.
- **`@StandScenarioId` / `@StandEnv`** — declare the scenario id / logical environment on a class, method
  or `String` parameter. The extension injects the declared value into the annotated `String`
  parameter, resolved most-specific-first: parameter → method → class (→ `@StandTest(env)` for the
  environment). They inject a `String` (feeding `Scenario.builder(id)` / `.environment(env)`) so they
  never clash with the core `ScenarioId` type's simple name.
- **`StandTestExtension`** — a JUnit 5 `ParameterResolver` that injects, into test methods:
  - **`StandClient`** — assembled from the `StepExecutor`s discovered on the classpath via
    `ServiceLoader` (the SPI wiring point — each adapter registers its executor in
    `META-INF/services`), behind a `DefaultScenarioRunner`. Built once and cached for the engine run.
  - **`Awaiter`** — a system-backed awaiter for ad-hoc waits.
  - **`@StandScenarioId String` / `@StandEnv String`** — the declared scenario id / environment.
- **Failure mapping is automatic.** `StandTestAssertionError extends AssertionError` and
  `StandTestException extends RuntimeException`, so a failure thrown by the runner inside
  `stand.run(...)` surfaces as a native JUnit test failure/error — the extension translates nothing.

## Usage sketch

```java
@StandTest
@StandEnv("ift")
@StandScenarioId("example-flow")
class ExampleFlowTest {

    @Test
    void shouldProcessFlow(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
        var scenario = Scenario.builder(id)                // "example-flow" (declared once above)
                .environment(env)                          // "ift"
                .step(/* RestStep / KafkaStep / DbStep — from the adapter modules */)
                .build();
        stand.run(scenario);                              // Validator -> Runner -> StepExecutor SPI
    }
}
```

The adapters (`rest`/`kafka`/`db`) register their `StepExecutor` via `META-INF/services`, so adding a
dependency on an adapter makes its step types runnable — no wiring code in the test.

## Not here

No transport, no business assertions, no reporting — only the JUnit ↔ SDK bridge. Spring-based
`@Autowired StandClient` is a separate, later module (`stand-test-spring-boot-starter`).

## Testing

The extension is driven in-process with JUnit's `EngineTestKit`: fixture classes (tagged
`standtest-fixture` and excluded from the normal run) exercise passing, assertion-failing and
infrastructure-failing scenarios, the `StandClient`/`Awaiter` injection and caching, and the
`@StandScenarioId`/`@StandEnv` resolution — precedence (parameter → method → class → `@StandTest(env)`),
`@Nested` inheritance from the enclosing class, and the misuse errors (missing declaration, both
annotations on one parameter, non-`String` parameter) — asserting the exact JUnit outcome and thrown
type.
