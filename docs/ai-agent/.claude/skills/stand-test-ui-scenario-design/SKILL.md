---
name: stand-test-ui-scenario-design
description: Turn a structured UI case plus a discovery report into a technical UI scenario design — scenario id, step order and ui.* types, sign-in by role, assertions with the boolean/string matcher asymmetry, bounded awaits, captures and the UI-to-backend binding, test data scoping, residual-data verdict, parallelism budget, and the screen-to-Page-Object map. Use after UI discovery and before any code.
version: 1
---

# Skill: stand-test-ui-scenario-design

Stage 4 of the UI branch. Convert `UiCase.md` + `UiDiscoveryReport.md` into `UiScenarioDesign.md`:
the exact step list the authoring stage transcribes. **Still no code.**

## When to use

After discovery. If discovery is missing, this stage has no locators to design with and must not
invent them — go back to stage 3, or record the screen as unexplored and design around it.

## Input

- `UiCase.md`, `UiCaseCompleteness.md` (assumptions and answered questions).
- `UiDiscoveryReport.md` — the **only** source of locators, texts and states.
- The registry: `ui-applications.<alias>` (`auth.roles`, `auth.scheme`, viewport profiles) and, for
  backend expectations, the service/topic/datasource/gRPC aliases plus their KB entries.

## Output

`UiScenarioDesign.md` following
[`ui-scenario-design-template.md`](../stand-test-ui-scenario-design/ui-scenario-design-template.md).

## Design decisions, in order

1. **Track — Java, always.** `ui.*` steps do not exist in the AI (JSON/YAML) format: the schema does
   not accept them and `AiScenarioParser` cannot produce them. There is no track choice to make on
   this branch, and a design that proposes a declarative UI document is proposing something that
   cannot be executed. A case with a UI part and a backend part is **one Java scenario**, not two.

2. **Scenario id** — kebab-case, stable, business-meaningful (`ui-application-submitted`). Matches
   `^[A-Za-z0-9][A-Za-z0-9._-]*$`.

3. **Environment** — the registry key from the case, verbatim. DEV/IFT only.

4. **Tags** — `ui` plus `integration` and domain tags; they become Allure labels.

5. **Sign-in placement.** If the application declares a sign-in, the scenario **opens** with
   `ui.login` — before the first `ui.open` of that application. A saved session can only be restored
   while the browsing context is being created, so a later sign-in cannot reuse one and says so
   instead of quietly taking the slow path. Name the role explicitly: mandatory once the application
   declares `auth.roles`, refused pre-flight otherwise (`UI_LOGIN_ROLE_REQUIRED` /
   `UI_LOGIN_ROLE_UNKNOWN`). "Any account" is deliberately inexpressible.

6. **Step order** — `login → open → (fill | click)* → expect / expectEventually → backend checks`.
   Steps run sequentially on one thread and share one browsing context; the steps of one scenario are
   never parallel. Two rules that follow from the step types:
   - **actions carry no assertions and no captures.** `ui.open`, `ui.click` and `ui.fill` refuse both
     at `build()`. A check is its own step, with its own number in the report and its own duration.
   - **a `ui.expect` / `ui.expectEventually` needs at least one assertion.** A step that expects
     nothing cannot fail and is not a check.

7. **Step ids** — explicit, unique, kebab-case (`open-form`, `fill-amount`, `await-accepted`).
   Duplicate ids fail validation at run time. The default derived id is built from type + locator, so
   a form with two `ui.fill` steps on the same field would collide; name them.

8. **Assertions per step, with the matcher.** Declare the property and the matcher, and respect the
   asymmetry — it is enforced twice, at `build()` and again at execution:

   | Property | Matchers |
   |---|---|
   | `TEXT`, `VALUE`, `ATTRIBUTE` | EQUALS, CONTAINS, MATCHES (full-string regex), EXISTS, NOT_NULL |
   | `VISIBLE`, `ENABLED` | **EQUALS only** |

   Use `MATCHES` for a system-generated value whose exact form is unknowable (`AP-\d+`), `CONTAINS`
   only when the surrounding text is genuinely variable, `EQUALS` everywhere else — a `CONTAINS` used
   to dodge a text you did not observe carefully is a check that will pass on the wrong screen.

9. **Awaits.** Every expectation that is not true the instant the previous step returns is
   `ui.expectEventually` with an explicit `within(...)`. Pick the smallest realistic bound from the
   case's SLA; a `pollInterval(...)` is optional and must never exceed the timeout (the builder
   refuses that pair). A single `ui.click`/`ui.fill` is bounded by the run's action timeout, which is
   configuration (`stand.test.ui.action.timeout.millis`) and not a step field — do not design a
   "timeout" onto an action step; the builder refuses `within(...)` there.

   **The default assumption for a UI check is `expectEventually`, not `expect`.** Rendering is
   asynchronous whether or not the case says so, and the commonest cause of a UI test that passes
   locally and flakes on CI is a `ui.expect` where the screen needed a moment.

10. **Captures and the UI→backend binding.** `capture(var, from)` reads text (or `VALUE` /
    `ATTRIBUTE`) off the page into the run's variable store; every later step of **any** adapter sees
    it as `${var}`. That is the whole binding mechanism — no new machinery, and the reason a UI case
    with backend expectations stays one scenario.

    Choose the binding deliberately and record which one, because BR-13 is about exactly this:

    | Binding | When | How |
    |---|---|---|
    | SDK correlation id | the backend propagates the header end to end | `injectCorrelationId()` on the UI steps; `correlationIdFromContext()` on `kafka.expect` |
    | a value read off the screen | the screen shows an id the backend also knows | `capture("applicationNumber", NUMBER)` → `${applicationNumber}` in the REST path / SQL param |
    | none | neither is available | say so; the UI and backend halves are then checked independently, and the design states that the link is unproven |

    Captures live only on `ui.expect` / `ui.expectEventually` — a capture reads the page *after* a
    check, so the builder refuses one on an action step.

11. **Correlation on UI steps.** `injectCorrelationId()` adds the SDK-owned correlation id as an
    `X-Correlation-Id` header to **every request the page makes**. The flag applies per step that asks
    for it, not once per session — put it on the step that opens the flow, and on any later step whose
    requests must be traceable. The header name is a module constant in this version: the registry has
    no per-application `correlation` section for UI applications, unlike a REST service. Never
    fabricate a correlation value.

12. **Test data.** Every value the run must make unique derives from `${testRunId}` — `ui.fill`
    resolves `${var}` through the same resolver a REST body goes through. A literal external
    identifier repeats on the second run and collides between two parallel runs. Dates are computed
    in plain Java above the builder, never calendar literals. Business codes and amounts stay verbatim
    from the case.

13. **Residual data — a verdict, not a hope.** The SDK has **no UI-side compensation**: the run's
    undo-log reaches `db.write` and nothing else, and a browser action is never undone. For every
    entity the flow creates, the design records one of:
    - a backend cleanup step (`db.cleanup` on a tagged table, or a REST delete) — subject to all the
      protocol rules, including that cleanup does not run after a failed step;
    - a `db.write`-provisioned precondition, undone by the undo-log per `cleanupPolicy`;
    - **nothing** — the row stays on the stand. This is an acceptable answer and an unacceptable
      omission: it goes into the design and then into the generation report.

14. **Parallelism budget.** UI classes run concurrently under the consumer's JUnit configuration; the
    ceiling is the **size of the account pool**, not the thread count. Above it runs queue, bounded by
    `accountTimeout` (default 60 s). The arithmetic is worth doing in the design rather than
    discovering it in CI: at parallelism `P`, pool size `N` and scenario duration `T`, the last run in
    the queue waits about `T × (P/N − 1)` — `P=8`, `N=2`, `T=45 s` gives ≈135 s, so the 60 s default
    fails as `BROKEN`. Either raise `accountTimeout(...)`, or grow the pool, or lower parallelism —
    and say which in the design. The `ui.account.waitMillis` diagnostic in `StepResult` shows the real
    idle time once it runs.

15. **The screen → Page Object map.** One Page Object per screen (not per test), named after the
    screen, holding the locator constants and the step factories the test composes. The design names
    the classes and which step belongs to which — stage 5 turns that map into files.

16. **Negative paths.** A UI negative check is usually an ordinary assertion (`assertEnabled(false)`,
    a validation message via `ui.expect`), not an expected exception. Reserve
    `assertThatThrownBy(() -> stand.run(...))` for the case where the *run* must fail — and remember
    the classification: an unmet expectation is `StandTestAssertionError` (FAILED); a locator matching
    several elements, a browser that would not start, an exhausted pool, an unknown alias are
    `StandTestException` (BROKEN).

## Forbidden in this skill

- Emitting Java (that is stages 5–6).
- Designing a step type, builder method or capability that
  [`stand-test-ui-guardrails.md`](../../rules/stand-test-ui-guardrails.md) lists as absent — no
  screenshot or trace on demand, no URL/console assertions, no `select`/`hover`/`upload`, no network
  interception. (Failure artefacts themselves are not designed either: the executor attaches the
  screenshot, console, network and — where the registry opts in — the trace by itself.)
- Designing a locator that is not in the discovery report.
- Designing a hand-rolled sign-in out of `ui.fill` + `ui.click` instead of `ui.login`.
- Designing any wait other than `ui.expectEventually` / `ui.login`'s own bounded waits.

## Checklist before handing off

- [ ] Track is Java, stated with the reason (`ui.*` has no declarative surface).
- [ ] `ui.login` is first, names a declared role, and precedes the first `ui.open`.
- [ ] Step table complete: id, type, application alias, purpose, locator (from the report), assertions
      with matchers, captures, timeout for every await.
- [ ] No assertion or capture on an action step; every expect step has ≥1 assertion.
- [ ] Every locator cites its row in the discovery report; every fragile one is flagged.
- [ ] Every `${var}` consumed is produced earlier (or is a built-in: `${scenarioId}`, `${testRunId}`,
      `${correlationId}`, `${environment}`).
- [ ] The UI↔backend binding is named — correlation, captured value, or explicitly none.
- [ ] Every run-unique value derives from `${testRunId}`; no calendar literal; no entity-instance
      handle copied from the case.
- [ ] Residual-data verdict recorded for every created entity.
- [ ] Parallelism budget computed against the pool size; `accountTimeout` set if the default fails.
- [ ] Screen → Page Object map complete.
- [ ] Negative paths listed with the construct that expresses them.

## Next stage

[`stand-test-ui-page-object-design`](../stand-test-ui-page-object-design/SKILL.md).
