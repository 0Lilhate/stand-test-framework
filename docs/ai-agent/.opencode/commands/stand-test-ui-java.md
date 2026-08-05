---
description: 'UI scenario design + Page Objects → JUnit 5 UI test on stand-test-ui, compiled and checkstyle-clean, gated to skip without stand configuration. The only authoring track for ui.* (there is no declarative UI format). Followed by the UI safety gate.'
version: 1
---

# /stand-test-ui-java — design → UI test

Stage 6 of the UI branch, plus the compile gate. Assumes
[`/stand-test-ui-design`](stand-test-ui-design.md) has produced `UiScenarioDesign.md` and the Page
Objects; running this command does not license skipping those stages.

There is no `/stand-test-ui-yaml` counterpart: `ui.*` steps are not in the AI (JSON/YAML) format —
the schema does not accept them and `AiScenarioParser` cannot produce them.

## Input

`UiScenarioDesign.md`, the Page Object classes, `UiDiscoveryReport.md` (for cross-checking locators),
and the registry (for the env-var name that gates the test).

## Output

One JUnit 5 test class in the consumer's test sources, composed from Page Object factories.

## Steps

1. **Establish the wiring.**

   | Consumer setup | What to write |
   |---|---|
   | plain JUnit (`stand-test-junit` + `stand-test-ui` + `stand-test-config`) | `@StandTest(env = "…")`; take `StandClient stand` as a method parameter. `ServiceLoader` finds `UiStepExecutor` — nothing else is needed |
   | Spring Boot starter | `@SpringBootTest` + `@Autowired StandClient` **and** a consumer-declared `UiStepExecutor` bean — the starter does not auto-configure the UI executor in this SDK version |

   No such bean on a starter project ⇒ say so and stop; a test that would fail at run time with "no
   executor for step type ui.open" is not a deliverable.

2. **Transcribe the design** — [`stand-test-ui-java-authoring`](../skills/stand-test-ui-java-authoring/SKILL.md).
   One `Scenario`, steps in the design's order with the design's ids, `ui.login` first with an
   explicit role, every step coming from a Page Object factory. Run-unique values as
   `"<prefix>-${testRunId}"`; dates computed in plain Java locals above the builder.

3. **Check the placement of every `${…}`** against the resolution table in
   [`ui-sdk-surface-checklist.md`](../skills/stand-test-ui-java-authoring/ui-sdk-surface-checklist.md):
   resolved in `ui.fill` values and in REST/DB/Kafka/gRPC inputs; **not** resolved in a `ui.open` path
   and **not** in any assertion's expected value. A misplaced placeholder is a test that fails on its
   first run for a reason that reads like application drift.

4. **Gate the test** — `@EnabledIfEnvironmentVariable(named = "<the application's base-url-ref
   variable>", matches = ".+")`, and forward that variable into the test JVM in the consumer's build
   (a bare `export` does not reach a forked worker).

5. **Compile** — `./gradlew compileTestJava checkstyleTest`. Both the test **and** the Page Objects.

6. **Run**, if a stand is configured. Then read `build/test-results/test/TEST-*.xml`, not the exit
   code: `skipped="1"` means the gate fired and no browser was ever opened.

## Mandatory checks

- [ ] Every step of the design is present, in order, with its id and timeout.
- [ ] No `UiLocator` anywhere in this file — every element comes through a Page Object factory.
- [ ] `ui.login` is first and names a declared role; no `fill`+`click` against a login form.
- [ ] Every wait is `ui.expectEventually` with an explicit bounded `within(...)`; no `Thread.sleep`,
      no `Awaitility`, no driver wait, no retry loop.
- [ ] Every type and method is on the SDK surface checklist.
- [ ] `${…}` only where it resolves.
- [ ] No shared mutable static or instance state.
- [ ] No model call, prompt, or description-based locator library — the test must not depend on an LLM
      at run time.
- [ ] AssertJ only; no `System.out`; compiles and passes checkstyle.

## Next

[`/stand-test-ui-validate`](stand-test-ui-validate.md) — the safety gate, the quality gate and the
generation report. The artifact is not shown to a human before it.
