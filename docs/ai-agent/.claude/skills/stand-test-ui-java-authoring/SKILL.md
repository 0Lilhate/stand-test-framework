---
name: stand-test-ui-java-authoring
description: Generate a JUnit 5 UI test on stand-test-ui — one Scenario composed from Page Object step factories, ui.login by role, bounded ui.expectEventually awaits, captures binding the screen to backend steps, testRunId-scoped data, no locator in the test body, no sleep, no LLM at run time. The ONLY authoring track for UI (ui.* has no declarative format). Use after Page Object design.
version: 1
---

# Skill: stand-test-ui-java-authoring

Stage 6 of the UI branch. Transcribe `UiScenarioDesign.md` into a JUnit 5 test class that composes
the Page Objects from stage 5.

**There is no other track.** `ui.*` steps do not exist in the AI (JSON/YAML) format — the schema does
not accept them and `AiScenarioParser` cannot produce them. A design that proposed a declarative UI
document proposed something unexecutable; go back to stage 4.

## When to use

After [`stand-test-ui-page-object-design`](../stand-test-ui-page-object-design/SKILL.md). Never
before: a test written first grows its own locators, and those locators are exactly what the Page
Object exists to own.

## Input

`UiScenarioDesign.md` (the step table, ids, timeouts, captures, assumptions) + the Page Object
classes + the registry (for the env-var name that gates the test).

## Output

One JUnit test class in the consumer's test sources. Template:
[`ui-test-template.java`](../stand-test-ui-java-authoring/ui-test-template.java). Worked example:
[`example-generated-ui-test.java`](../stand-test-ui-java-authoring/example-generated-ui-test.java).
What the SDK does and does not offer:
[`ui-sdk-surface-checklist.md`](../stand-test-ui-java-authoring/ui-sdk-surface-checklist.md).

## Wiring: which one the consumer has

| Consumer setup | What to write | Why |
|---|---|---|
| **Plain JUnit** (`stand-test-junit` + `stand-test-ui` + `stand-test-config`) | `@StandTest(env = "ift")` on the class; take `StandClient stand` as a test-method parameter | `StandTestExtension` discovers every `StepExecutor` through `ServiceLoader`, and `stand-test-ui` registers `UiStepExecutor` in `META-INF/services`. **Nothing else is needed** — this is the default track for UI |
| **Spring Boot starter** | `@SpringBootTest` + `@Autowired StandClient`, and nothing more | Since ADR-UI-008 the starter's `StepExecutorDiscovery` loads every SPI-registered executor beside the beans it declares, so `stand-test-ui` on the test classpath is enough. Discovery is not UI-specific — any adapter registered through `META-INF/services` is picked up, including one built outside this repository |

**Do not require a `UiStepExecutor` bean, and do not stop for the want of one.** This skill used to say
the opposite, and that instruction is now a false blocker — the costliest kind of stale rule, because it
halts a run that would have worked. A consumer may still declare the bean if it wants to configure the
executor itself: a declared bean wins by ordering, and the SPI copy of the same class is de-duplicated,
so it neither loses nor runs twice. What remains forbidden is unchanged — overriding the validator, the
runner or `StandClient`.

## The shape

```java
@StandTest(env = "ift")
class ApplicationSubmittedUiTest {

    @Test
    @DisplayName("Заявка, поданная клиентом, принимается и получает номер")
    void applicationIsSubmitted(StandClient stand) {
        String externalId = "ext-${testRunId}";

        Scenario scenario = Scenario.builder("ui-application-submitted")
                .environment("ift")
                .tag("ui")
                .tag("integration")
                .step(UiStep.login("client-portal").id("login").role("client").withinSeconds(30).build())
                .step(NewApplicationPage.open())
                .step(NewApplicationPage.expectFormIsReady())
                .step(NewApplicationPage.fillAmount("100000"))
                .step(NewApplicationPage.fillExternalId(externalId))
                .step(NewApplicationPage.submit())
                .step(NewApplicationPage.awaitAccepted())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
```

The test body reads as business steps because every locator is behind a Page Object. That is the
acceptance criterion for this stage, not a style preference.

## Hard rules

1. **No `UiLocator` in the test.** Not as a constant, not as a local, not as an argument. If the test
   needs a new element, the Page Object gets a new constant. This is the finding reviewers look for
   first, because it is the one that costs the suite its maintainability.
2. **`ui.login` first**, with an explicit `.role(...)`, before the first `ui.open` of that
   application. Never a hand-rolled `fill` + `click` against the login form: that puts a credential in
   test code, bypasses the account pool (two parallel runs would share one account) and loses session
   reuse. The role is mandatory once the application declares `auth.roles`.
3. **Every wait is `ui.expectEventually` with a bounded `withinSeconds(...)`.** No `Thread.sleep`, no
   `Awaitility`, no retry loop, no driver wait — the adapter exposes none, and reaching for one is a
   BLOCK. A single click or fill is bounded by `stand.test.ui.action.timeout.millis`, which is
   configuration, not a step field.
4. **Aliases only.** The application alias is the Page Object's constant; the test never writes an
   address, and there is no API that would take one.
5. **Run-unique values derive from `${testRunId}`.** `ui.fill` resolves `${var}` through the same
   resolver a REST body goes through, so `"ext-${testRunId}"` is written as a plain string and
   resolved at execution. Dates are computed in plain Java locals above the builder — never calendar
   literals. Prefer `${testRunId}` over `UUID.randomUUID()`: a run-scoped value is traceable back to
   the run in logs and in stand data; a random one is not.
6. **No shared mutable state in the test class.** No `static` mutable field, no reused mutable object:
   the runner and every executor are shared across test threads, and the SDK runs test classes
   concurrently. Everything run-varying flows through captures and `${testRunId}`.
7. **Do NOT gate the test by default.** `@EnabledIfEnvironmentVariable` is optional: it sees only
   the bare environment variable, not a `${VAR:default}` default in `application.yml`, so with a
   default present it silently skips a UI test that would have run. Add it only when the
   application's base-url variable has no default; then make sure that variable reaches the test JVM.
   Either way check `build/test-results/.../TEST-*.xml` for `skipped="0"` — a green build over a
   skipped UI test is a test that never opened a browser.
8. **Do not catch SDK failures.** The happy path asserts `result.isSuccessful()`; an expected failure
   wraps `stand.run` in `assertThatThrownBy(...)`. Catching `StandTestAssertionError` /
   `StandTestException` to make a test pass is a BLOCK.
9. **Never construct the runner or the executor by hand** in consumer code
   (`new DefaultScenarioRunner(...)`, `new DefaultStandClient(...)`), never override the validator,
   runner or `StandClient` bean, never use the one-arg `validate(Scenario)` as a guardrail gate.
10. **No LLM at run time.** No model call, no agent invocation, no prompt string, no locator resolved
    "by description" while the test runs, no self-healing selector library. The artifact CI executes
    is plain Java, and everything it needs is in the repository.
11. **Metadata: `@DisplayName`, not `.title(...)`.** `Scenario.builder(...)` accepts `.title(...)` and
    `.description(...)`, but no reporter reads them — what reaches an Allure report is JUnit's
    `@DisplayName` via `allure-junit5`. State the behaviour there; put the case link, the assumptions
    and the not-covered notes in the class javadoc.
12. **Checkstyle-clean.** AssertJ only (`org.junit.jupiter.api.Assertions` and JUnit 4 `org.junit.Test`
    are banned imports), no `System.out`/`System.err`, no non-JetBrains `@NotNull`/`@Nullable`, one
    statement per line, blank line between members, Java-17-compatible sources.

## Composing a UI scenario with backend steps

One scenario, not two. A value captured off the screen is in the run's variable store, so a REST/DB
step later in the same scenario reads it as `${var}`:

```java
.step(NewApplicationPage.awaitAccepted())                         // capture("applicationNumber", NUMBER)
.step(RestStep.get("applications-service", "/api/applications/${applicationNumber}")
        .id("check-backend")
        .expectStatus(200)
        .assertPath("$.status", "ACCEPTED")
        .build())
```

Every protocol rule applies to those steps unchanged — aliases, KB-sourced contract details, bounded
awaits, `kafka.expect` discriminated by a per-run value, seeds paired with cleanups.

## Running it

| Property | Default | Use |
|---|---|---|
| `stand.test.ui.headless` | `true` | `-Dstand.test.ui.headless=false` to watch the run locally |
| `stand.test.ui.browser` | `chromium` | `firefox` \| `webkit` |
| `stand.test.ui.action.timeout.millis` | `10000` | bound on one click / fill |
| `stand.test.ui.navigation.timeout.millis` | `30000` | bound on one navigation |
| `stand.test.ui.artifacts.dir` | `build/stand-test-ui` | run artefacts, including saved browser sessions — **effectively secrets**: never attach, never log, never commit |

Browsers must be installed in the image or downloaded once; in a closed network that needs
`PLAYWRIGHT_DOWNLOAD_HOST` pointing at an internal mirror, or browsers baked into the CI image.

Parallelism: classes run concurrently, methods within a class do not, `maxParallelForks` stays **1**
(the account pool is in-process — a second JVM would hand the same account to a second run). The
ceiling is the pool size; above it runs queue, bounded by `accountTimeout`.

## Self-check before handing off

- [ ] Compiles and passes checkstyle: `./gradlew compileTestJava checkstyleTest`.
- [ ] `grep` clean over the test **and** the Page Objects: `Thread.sleep|Awaitility|waitFor|https?://|
      jdbc:|Authorization|password|new DefaultScenarioRunner|UiLocator\.` (the last one only inside
      Page Objects).
- [ ] Every step of the design is present, in order, with its id and its timeout.
- [ ] `ui.login` is first and names a role; no hand-rolled sign-in.
- [ ] Every `${var}` consumed is produced earlier; every run-unique value is `${testRunId}`-derived.
- [ ] No shared mutable static/instance state.
- [ ] No run gate unless one is genuinely needed; if `@EnabledIfEnvironmentVariable` IS present, the
      variable has no registry default and is forwarded to the test JVM.
- [ ] Nothing outside the SDK surface (see the surface checklist) appears anywhere.
- [ ] Run [`stand-test-ui-safety-review`](../stand-test-ui-safety-review/SKILL.md).

## Next stage

[`stand-test-ui-safety-review`](../stand-test-ui-safety-review/SKILL.md) — mandatory gate, in a
separate context.
