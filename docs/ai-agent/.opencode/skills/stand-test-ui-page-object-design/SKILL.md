---
name: stand-test-ui-page-object-design
description: Turn the screen-to-Page-Object map of a UI scenario design into Page Object classes — locator constants (never in the test body), step factories returning ScenarioStep, sensitive fields marked asSensitive(), one class per screen, checkstyle-clean. The stage that decides whether the suite survives markup drift. Use after UI scenario design and before authoring the test.
version: 1
---

# Skill: stand-test-ui-page-object-design

Stage 5 of the UI branch. Produce the Page Object classes the test will compose from.

This stage exists as its own stage for one reason: **the Page Object is the only mitigation the SDK
has against markup drift**. One screen changes, one file changes. A suite whose locators live in test
bodies has to be edited in as many places as the screen appears — which is how UI suites die. It is
also what makes the static KPI-9 count possible: that count reads merged Page Objects, and a locator
that never reaches one is invisible to it.

## When to use

After [`stand-test-ui-scenario-design`](../stand-test-ui-scenario-design/SKILL.md), on its
screen → Page Object map. Also on its own when a screen drifted and only the Page Object needs
updating — the whole point of the pattern.

## Input

- `UiScenarioDesign.md` (the map, the step table, the locator provenance table).
- `UiDiscoveryReport.md` — the locators themselves, and the only place they may come from.
- The consumer project's existing Page Objects for the same application: **reuse before you create**.
  Two classes for one screen is drift with extra steps.

## Output

One Java class per screen, in the consumer's test sources, beside the tests that use them (e.g.
`src/test/java/<base package>/ui/pages/`). Template:
[`page-object-template.java`](../stand-test-ui-page-object-design/page-object-template.java).
Worked example:
[`example-page-object.java`](../stand-test-ui-page-object-design/example-page-object.java).

## The shape

```java
public final class NewApplicationPage {

    private static final String APPLICATION = "client-portal";

    private static final UiLocator AMOUNT = UiLocator.label("Сумма");

    private static final UiLocator SUBMIT = UiLocator.role("button", "Подтвердить");

    private static final UiLocator STATUS = UiLocator.testId("application-status");

    private NewApplicationPage() {
    }

    public static ScenarioStep open() {
        return UiStep.open(APPLICATION, "/applications/new").id("open-form").injectCorrelationId().build();
    }

    public static ScenarioStep awaitAccepted() {
        return UiStep.expectEventually(APPLICATION, STATUS)
                .id("await-accepted")
                .assertVisible()
                .assertText("Принята")
                .withinSeconds(20)
                .build();
    }
}
```

Nothing here executes: `UiStep` is a lazy builder, so a Page Object method **returns a step**, it does
not perform one. That is what keeps the validator and the guardrails in front of every action.

## Rules

1. **Locators are `private static final` constants of the Page Object.** Never a `UiLocator.*` call in
   a test method, never a locator passed in as a parameter from the test, never a locator assembled
   from a string at run time. A test that needs a new element needs a new constant here.
2. **One class per screen, named after the screen** — `NewApplicationPage`, `ApplicationListPage`.
   Not per test, not per feature, not one god-object per application.
3. **`final` class, private constructor, static members.** These classes hold no state; a Page Object
   with a field is a Page Object two parallel runs will share, and the SDK runs test classes
   concurrently. (Checkstyle's `OneStatementPerLine` also means the private constructor is written on
   two lines, not as `private Foo() {}` on one.)
4. **The application alias is a constant of the class**, spelled once. Every step factory passes it;
   no method takes it as a parameter, because a Page Object belongs to exactly one application.
5. **Step factories return `ScenarioStep` and set their own `id`.** The id is the one from the design's
   step table — it is what the report, the log and the failure message will name.
6. **Parameterise the data, not the structure.** `fillAmount(String amount)` is right;
   `fill(UiLocator field, String value)` is a locator escaping into the test body through the back
   door. A factory may take a value, a timeout in seconds, or an expected text the *case* varies —
   never an element.
7. **Sensitive fields carry `asSensitive()`** — passwords, tokens, one-time codes, personal data:
   `UiLocator.label("Пароль").asSensitive()`. The sign-in form's own fields are not here at all (see
   rule 9), but any in-app field holding a secret or PII is.
8. **Assertions and captures live on `ui.expect` / `ui.expectEventually` factories only.** The builder
   refuses them on `open`/`click`/`fill`, and refuses `within(...)` on anything that does not wait —
   so the shape of the factory follows the step type, not the author's preference.
9. **The sign-in form has no Page Object.** Its locators live in the environment registry
   (`auth.login.*`, spelled `<strategy>=<value>`), and signing in is `UiStep.login(alias).role(...)`.
   A Page Object that re-implements the login form bypasses the account pool and puts a credential in
   test code; both are BLOCK findings.
10. **No waiting helper.** No `waitFor…`, no retry loop, no `Thread.sleep`, no driver-level wait. The
    only wait a Page Object can express is a `ui.expectEventually` factory with a bounded
    `withinSeconds(...)`.
11. **No conditional flows.** A Page Object method does not branch on what is on the screen — it has
    not looked, and it cannot: it builds a step. `if (isVisible(...))` is not expressible and must not
    be simulated by returning different steps from a method that queried nothing.
12. **Checkstyle-clean**, by the consumer's configuration. In an SDK-style repository that means:
    4-space indent, a blank line between members, one statement per line, no `System.out`, no
    non-JetBrains nullability annotations, Java-17-compatible sources.

## Naming

| Thing | Convention | Example |
|---|---|---|
| Class | `<Screen>Page` | `NewApplicationPage` |
| Locator constant | `SCREAMING_SNAKE_CASE`, named after what the user calls it | `AMOUNT`, `SUBMIT`, `STATUS` |
| Action factory | verb + object | `fillAmount(String)`, `submit()` |
| Check factory | `expect…` / `await…` (the second implies a bounded wait) | `expectSubmitDisabled()`, `awaitAccepted()` |
| Step id inside a factory | kebab-case, from the design | `fill-amount`, `await-accepted` |

`await…` is reserved for `ui.expectEventually` and `expect…` for `ui.expect`, so a reader can tell
from the call site whether a step waits — and a reviewer can spot a check that should have waited and
does not, which is the commonest source of UI flakiness.

## Reuse before creation

Before writing a class, look for one: same application, same screen, possibly under another name. If
it exists, **add the constant and the factory to it**; do not fork. If it exists and its locator for
the same element disagrees with the discovery report, the report wins and the existing constant is
updated — with a note in the generation report, because that update touches every test using it.

## Checklist before handing off

- [ ] One class per screen; no duplicate class for a screen the project already models.
- [ ] `final` class, private constructor on two lines, no instance state.
- [ ] Every locator is a `private static final UiLocator` constant sourced from the discovery report.
- [ ] No `UiLocator` type appears in any test method signature or body.
- [ ] Application alias spelled once, as a constant.
- [ ] Every factory sets the step id from the design.
- [ ] Sensitive/PII fields marked `asSensitive()`.
- [ ] Every `await…` factory has a bounded `withinSeconds(...)`; no other wait exists anywhere.
- [ ] No branching, no state queries, no helper that performs IO.
- [ ] Compiles and passes checkstyle.

## Next stage

[`stand-test-ui-java-authoring`](../stand-test-ui-java-authoring/SKILL.md).
