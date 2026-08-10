---
name: stand-test-ui-quality-review
description: Quality review of a generated UI autotest against the ORIGINAL business case — coverage of every expectation, assertion correctness under the boolean/string matcher asymmetry, non-flaky awaits, locator durability, the UI-to-backend binding, residual data, Page Object hygiene, negative paths and reporting metadata. Runs after the UI safety gate passes, before human approval.
version: 1
---

# Skill: stand-test-ui-quality-review

Stage 8 of the UI branch. Safety asked "may this artifact exist"; quality asks **"does it prove what
the case asked, and will it keep proving it next month"**.

## When to use

After [`stand-test-ui-safety-review`](../stand-test-ui-safety-review/SKILL.md) returns PASS or
PASS-WITH-NOTES. Never before: reviewing the quality of an artifact that is about to be regenerated
wastes the review and, worse, lends it credibility.

## Input — and the one that matters most

- **`UiCase.md` and the original case text.** Read the *case*, not the design. A check is most often
  lost in the design, and a review conducted against the design cannot see that loss: the artifact
  matches the design perfectly, and the design is the thing that is incomplete.
- The test class, the Page Objects, `UiDiscoveryReport.md`, `UiScenarioDesign.md`.
- The UI safety review report (its notes are inputs, not conclusions).

## Output

`UiTestReview.md` following
[`ui-quality-checklist.md`](../stand-test-ui-quality-review/ui-quality-checklist.md), with a verdict
of `APPROVE` / `APPROVE-WITH-NOTES` / `REWORK` and, for `REWORK`, the stage to re-enter at.

## What to review

### 1. Coverage against the original case

Walk the case's expectation list and find each one in the test. For every expectation, one of:

| Outcome | What it means |
|---|---|
| **covered** | there is a step asserting it, and the assertion actually distinguishes pass from fail |
| **weakened** | asserted, but by something laxer than the case asked — `CONTAINS` where the case gave an exact string, presence where the case gave a value, one field of three |
| **not covered — SDK** | the SDK cannot express it (URL/title/console assertion, file upload, network assertion…); belongs in the generation report's *not covered*. A failure **screenshot** is not one of these — the executor attaches it, together with the console, the network story and, where the registry opts in, a trace |
| **not covered — unexplored** | discovery could not reach the screen (usually an irreversible control); same |
| **dropped** | none of the above — a real gap, and a `REWORK` |

Count the negative paths separately. They are the checks a UI suite is best at and the first to be
silently dropped.

### 2. Assertions that can actually fail

- An `assertVisible()` on an element that is always visible proves nothing. Ask, for each assertion,
  what would have to break for it to fail — if the answer is "nothing plausible", it is decoration.
- Exact text where the case gave exact text. `assertTextContains("Прин")` passing on «Принята» and on
  «Принято к рассмотрению» is a check that survives the defect it was written for.
- `assertTextMatches` is a **full-string** match, not a search: `AP-\\d+` does not match
  `Заявка AP-42`. A regex written as if it were a search is a test that fails on its first run.
- The asymmetry: `VISIBLE` / `ENABLED` accept EQUALS only. A `CONTAINS` on a boolean is refused at
  `build()` — but an author who wanted one has misunderstood the check they were writing.
- Numbers, dates and money read off a screen are **strings**. `100000` and `100 000` and `100 000,00`
  are three different assertions; the report says which one the screen shows.

### 3. Awaits that will not flake

- Every expectation that follows an action should be `ui.expectEventually`. This is the single
  biggest source of UI flakiness, and a `ui.expect` that passes today because the machine was fast is
  a red build on a loaded CI agent.
- Timeouts sized from the case's SLA, not from the wish to make the test green. A bound raised from
  20 s to 120 s without evidence is hiding a defect.
- `pollInterval` (when set) meaningfully smaller than the timeout.
- The sign-in's three budgets are separate: waiting for a free account (`accountTimeout`), checking a
  restored session (the action timeout), and the sign-in itself (`within`). The worst case is their
  sum — check the arithmetic against the suite's parallelism and the pool size.

### 4. Locator durability

- Every locator at the highest rung the screen supports, with the discovery report's justification
  where it is not rung 1.
- Fragile locators listed in the generation report; brittle CSS flagged separately.
- Every locator unique on the screen state its step runs against — a locator matching two elements is
  a BROKEN run, not a failed assertion, and a table with one row today may have two tomorrow.
- Locator constants named after what the user calls the element, not after the selector.

### 5. The UI↔backend binding

- The design named a binding — correlation, a captured screen value, or none. Check the artifact
  implements the one it named.
- A captured value is consumed somewhere; a capture nothing reads is dead weight that still costs a
  DOM read and still fails the run when the element is absent.
- `injectCorrelationId()` is on the steps whose requests need tracing, and the backend steps that
  correlate use `correlationIdFromContext()`.
- Where the binding is **none**, the test asserts the UI and the backend independently and the report
  says the link is unproven. That is an acceptable design and an unacceptable silence.

### 6. Page Object hygiene

One class per screen, no state, no `UiLocator` in test bodies, factories parameterised by data and
never by element, `await…` naming reserved for steps that wait, reuse of an existing class for a
screen the project already models.

### 7. Residual data and repeatability

- Can the test run twice in a row on the same stand? A form that rejects a duplicate external
  identifier answers this immediately; a `${testRunId}`-derived value is what makes the answer yes.
- Can two runs run at once? All run-varying data derives from `${testRunId}` or a capture; nothing
  static is shared.
- What stays on the stand is stated — in the class javadoc **and** in the generation report.

### 8. Reporting metadata

`@DisplayName` states the behaviour in the language of the case (it is what reaches Allure — the
scenario's `.title(...)` and `.description(...)` are read by no reporter). The class javadoc carries
the case link, the assumptions and the not-covered list. Step ids are readable in a failure message:
`await-accepted` tells a story, `step-7` does not.

## Verdict

| Verdict | When | Next |
|---|---|---|
| `APPROVE` | every case expectation is covered or explicitly declared not covered; no flake risk; locators justified | stage 9 |
| `APPROVE-WITH-NOTES` | the same, with improvements the human should see but which do not block | stage 9, notes carried into the report |
| `REWORK` | a dropped expectation, a decorative assertion, a `ui.expect` that must poll, an unjustified fragile locator, an unimplemented binding | re-enter at stage 6, or at stage 4 when the design is what is wrong, or at stage 3 when a locator has to be observed again |

The review never merges. It produces a verdict and a recommendation; the human decides.

## Who runs this

A separate context — the `stand-test-quality-reviewer` subagent, which has no `Write` and which is
given **the original case**, not the design. That is the whole design of the stage: the loss this
review is looking for happened in the design, and a reviewer holding only the design cannot see it.

## Next stage

[`stand-test-ui-generation-report`](../stand-test-ui-generation-report/SKILL.md).
