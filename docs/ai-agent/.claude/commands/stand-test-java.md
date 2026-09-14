---
description: 'Generate a compiling JUnit test on the stand-test-sdk Java DSL from a scenario design: authoring, compile/checkstyle, safety review.'
version: 1
---

# /stand-test-java — scenario design → Java DSL test

`ScenarioDesign.md` → compiling, checkstyle-clean JUnit test class on the Java DSL.

## Input

`ScenarioDesign.md` + environment mapping report + consumer project conventions (Spring Boot
starter vs plain JUnit; base package; existing template tests).

## Output

- JUnit test class in the consumer project's test sources
- fixtures for any `bodyFromResource`/`requestFromResource`
- safety review report

## Steps

1. **Author** — run
   [`stand-test-java-dsl-authoring`](../skills/stand-test-java-dsl-authoring/SKILL.md): one test
   class, scenario built exactly from the design's step table, correct consumer wiring
   (`@SpringBootTest` + `@Autowired StandClient`, or `@StandTest` with explicitly passed
   `@StandScenarioId`/`@StandEnv` parameters). No `@EnabledIfEnvironmentVariable` gate by default —
   it is optional, see the guardrails.
2. **Ensure no eager IO** — inspect: builders only build; the single execution point is
   `stand.run(scenario)`; no HTTP/Kafka/JDBC/gRPC client types imported; no
   `new DefaultScenarioRunner(...)`/`new DefaultStandClient(...)` in consumer code.
3. **Ensure ScenarioValidator is in the path** — confirmed structurally by using the injected
   `StandClient` (the validator runs unconditionally inside `run()`); confirm no validator/
   runner bean overrides and no `stand.test.enabled=false` were introduced.
4. **Ensure JUnit integration is used** — the test runs on JUnit 5 platform with the sanctioned
   wiring; failure semantics respected (happy path asserts `result.isSuccessful()`; negative
   paths use `assertThatThrownBy(...).isInstanceOf(StandTestAssertionError.class)`).
5. **Generate fixtures** if referenced —
   [`stand-test-fixture-authoring`](../skills/stand-test-fixture-authoring/SKILL.md).
6. **Compile** — `./gradlew compileTestJava` (plus `checkstyleTest` where wired) in the
   consumer project. Fix compile/style findings by regenerating, not by suppressions.
7. **Safety review** — [`stand-test-safety-review`](../skills/stand-test-safety-review/SKILL.md),
   run by the `stand-test-safety-reviewer` SUBAGENT; any BLOCK → back to step 1. Then record the
   verdict from THIS context, naming the artifacts it covers:
   `node <bundle>/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <files>`.
   Invoking this command rather than the umbrella does not license skipping either half: the hook
   refuses a PASS the scan disagrees with, and one recorded with no subagent finished since the
   artifact was last written — and without the record the session cannot end.

## Mandatory checks

- [ ] Compiles; checkstyle-clean (AssertJ-only imports, no `System.out`, one statement/line).
- [ ] grep clean: `Thread.sleep|Awaitility|http://|https://|jdbc:|Authorization|new DefaultScenarioRunner|DriverManager|KafkaConsumer|ManagedChannelBuilder`.
- [ ] Every async step has an explicit bounded timeout; step ids explicit and unique.
- [ ] Every seed paired with a `whereTestRunId`-scoped cleanup.
- [ ] [`sdk-boundary-checklist.md`](../skills/stand-test-java-dsl-authoring/sdk-boundary-checklist.md) passes.

## Human approval points (blocking)

- Any consumer build change (new dependency, new source set).
- Final approval via [Workflow 4](stand-test-validate.md).
