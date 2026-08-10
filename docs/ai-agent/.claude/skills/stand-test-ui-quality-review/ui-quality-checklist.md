# UI Quality Review: <case-id> / <test class>

> Output of skill `stand-test-ui-quality-review` (stage 8). Reviewed against the **original case**,
> not against the design.

## Verdict

**APPROVE | APPROVE-WITH-NOTES | REWORK**

Re-entry stage when `REWORK`: `6 (authoring)` / `5 (Page Objects)` / `4 (design)` / `3 (discovery)`.

## Coverage against the original case

One row per expectation in the case — including the negative ones.

| # | Expectation (case wording) | Step that covers it | Outcome | Note |
|---|---|---|---|---|
| 1 | поле «Статус» показывает `Принята` | `await-accepted` | covered | exact text, polled 20 s |
| 2 | кнопка недоступна при пустой сумме | `submit-disabled-when-empty` | covered | negative path |
| 3 | адресная строка после отправки | — | not covered — SDK | нет проверок URL/заголовка/консоли; в отчёте генерации |
| 4 | экран поданной заявки | — | not covered — unexplored | irreversible control; in the generation report |

| Tally | Count |
|---|---|
| covered | |
| weakened | |
| not covered — SDK | |
| not covered — unexplored | |
| **dropped** | **must be 0 to approve** |

## Assertions

| # | Step | Property · matcher · expected | Could it fail? | Comment |
|---|---|---|---|---|
| 1 | `await-accepted` | `TEXT·EQUALS·Принята` | yes — any other status | |
| 2 | `form-is-ready` | `VISIBLE·EQUALS·true` | marginal — the field is always rendered | decoration? |

Checks:

- [ ] No assertion that nothing plausible could break.
- [ ] Exact text where the case gave exact text; `CONTAINS` only where the surroundings genuinely vary.
- [ ] Every `assertTextMatches` written as a **full-string** match (`AP-\d+`, not a fragment).
- [ ] `VISIBLE` / `ENABLED` only with EQUALS.
- [ ] Numeric/date/money values compared in the exact format the screen renders.

## Awaits and flakiness

| # | Step | Type | Timeout | Sized from | Flake risk |
|---|---|---|---|---|---|
| 1 | `await-accepted` | `expectEventually` | 20 s | case SLA «почти сразу» | low |

Checks:

- [ ] Every expectation that follows an action polls (`expectEventually`), not `expect`.
- [ ] No timeout inflated without SLA evidence.
- [ ] `pollInterval`, where set, is meaningfully smaller than the timeout.
- [ ] Sign-in budgets accounted: `accountTimeout` + session check + `within` is the worst case.
- [ ] Parallelism vs pool size checked: `T × (P/N − 1)` fits inside `accountTimeout`.

## Locators

| # | Constant | Rung | Fragile | Brittle | Unique on the screen state used | Justified in the report |
|---|---|---|---|---|---|---|
| 1 | `STATUS` | 1 (testId) | no | no | yes | n/a |
| 2 | `SUBMIT` | 2 (role+name) | yes | no | yes | yes — no testId on the control |

- [ ] Every locator at the highest available rung, with a recorded reason otherwise.
- [ ] Every fragile locator in the generation report; every brittle CSS flagged separately.
- [ ] Uniqueness verified against the screen state each step runs in.
- [ ] Constants named after the element as users know it.

## UI ↔ backend binding

| Field | Value |
|---|---|
| Binding the design named | correlation / captured value / none |
| Binding the artifact implements | |
| Every capture consumed? | |
| Where the binding is `none`, is the limit stated? | |

## Page Object hygiene

- [ ] One class per screen; no duplicate for a screen already modelled in the project.
- [ ] No state; `final` class; private constructor.
- [ ] No `UiLocator` in any test body or method signature.
- [ ] Factories parameterised by data, never by element.
- [ ] `await…` names only steps that wait; `expect…` only steps that do not.

## Repeatability and residual data

- [ ] The test can run twice in a row on the same stand (no value collides with the previous run).
- [ ] Two runs can run at once (everything run-varying is `${testRunId}`/capture-derived; nothing
      static is shared).
- [ ] What stays on the stand is stated in the class javadoc **and** in the generation report.
- [ ] Where a compensation exists, it is a backend step and its non-execution after a failed step is
      noted.

## Reporting metadata

- [ ] `@DisplayName` states the behaviour in the case's language.
- [ ] Class javadoc carries the case link, the assumptions and the not-covered list.
- [ ] No `.title(...)` / `.description(...)` on the scenario (no reporter reads them).
- [ ] Step ids readable in a failure message.

## Notes for the human

<what would improve the artifact without blocking it, and anything the next generation on this screen
should know>
