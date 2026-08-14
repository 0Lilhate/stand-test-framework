# Locator selection — the priority, the SDK spelling, and what counts as fragile

> Used by `stand-test-ui-discovery` (stage 3) when choosing a locator, and by
> `stand-test-ui-safety-review` / `stand-test-ui-quality-review` when checking one. The rungs are
> fixed by BRD D-5; the spellings are fixed by what `stand-test-ui` actually offers.

## The ladder

Take the **highest rung the screen actually supports**, and record why the ones above it were
unavailable. "I preferred CSS" is not a reason; "no `data-testid`, and the button has no accessible
name because it is an icon" is.

| # | Rung | SDK spelling | Registry spelling | Fragile? | Breaks when |
|---|---|---|---|---|---|
| 1 | `data-testid` | `UiLocator.testId("application-status")` | `testId=application-status` | **no** | someone deletes the test id — a deliberate, reviewable act |
| 2 | ARIA role + accessible name | `UiLocator.role("button", "Подтвердить")` | `role=button:Подтвердить` | yes | the visible label is reworded, or the control changes element type |
| 3 | label | `UiLocator.label("Сумма")` | `label=Сумма` | yes | the label text or its `for`/nesting association changes |
| 4 | other stable attribute | `UiLocator.css("[data-qa='submit']")` | `css=[data-qa=submit]` | yes | the attribute is renamed; **note there is no attribute strategy** — this is CSS |
| 5 | visible text | `UiLocator.text("Заявка принята")` | `text=Заявка принята` | yes | any copy edit, any translation, any trailing-space cleanup |
| 6 | CSS selector | `UiLocator.css(".form__submit")` | `css=.form__submit` | yes | the markup is restructured or the CSS framework regenerates class names |

There is **no XPath**: no `LocatorStrategy` constant, no `UiLocator` factory, no `xpath=` spelling.
An agent that wants one has run out of rungs and must either request a `data-testid` from the
product team (a finding for the report) or drop to CSS and declare it.

## Fragile vs brittle — two different words, both needed

**Fragile** is the SDK's own predicate, `UiLocator.fragile()`: `false` for `TEST_ID`, `true` for
everything else. It is a single definition on purpose, so a report and a static count cannot drift
apart. Every locator on rungs 2–6 is fragile, including a neat `[data-qa=…]`. Do not soften this:
calling rung 4 "stable" in a report is exactly the self-assessment BRD refuses to let KPI-9 depend on.

**Brittle** is a stronger claim about a CSS selector specifically, and it must be flagged separately
in the generation report. A CSS selector is brittle if it carries **any** of:

- a descendant chain of three or more steps — `.page .card .body .value`;
- `nth-child` / `nth-of-type` / any positional index;
- a generated or hashed class name — `.css-1x2y3z`, `.MuiBox-root`, `.styles__btn___2Kj9`;
- a tag-only step — `div > span`, `form input`;
- a selector that depends on sibling order — `+`, `~`.

A short attribute selector (`[data-qa='submit']`) or a single semantic class (`.application-status`)
is fragile but **not** brittle. Say which one it is; the two need different fixes.

## Choosing between rung 2 and rung 3

Both address the same control often enough that the choice matters:

- Prefer **role + accessible name** for controls a user *operates*: buttons, links, checkboxes, tabs.
  It is what an assistive technology sees, so it degrades in the same direction accessibility does.
- Prefer **label** for form fields a user *fills*. `UiLocator.label("Сумма")` says what the field is
  for; the same field addressed by role would need the accessible name anyway, and that name usually
  *is* the label.
- Do not use rung 2 for an icon-only control with no accessible name — there is nothing to match, and
  a guessed name is an invented locator.

## Uniqueness is part of the choice

A locator that matches **more than one element** is not a weaker locator — it is a broken one. The
adapter raises a `StandTestException` naming the locator and the number of matches, and the run is
recorded as broken rather than failed. Check uniqueness during discovery, on the screen state the
step will actually run against (a table row's «Подтвердить» is not unique when the table has two
rows), and record the check.

If an element is not uniquely addressable at any rung, that is a finding for the human — the product
needs a `data-testid` — not a licence to write a positional selector.

## Sensitivity is orthogonal to the rung

Any locator addressing a password, token, one-time code or personal data is marked
`.asSensitive()` regardless of which rung it sits on:

```java
private static final UiLocator PASSWORD = UiLocator.label("Пароль").asSensitive();
```

The mark keeps the value out of assertion failure messages and await diagnostics — that is, out of
the report, the log and the CI output. The sign-in form's own username/password locators are marked
automatically by the adapter; everything else is the author's responsibility.

## Recording the choice

For every element, the discovery report carries: all rungs that exist, the chosen one, why not a
higher one, fragile yes/no, brittle yes/no, unique yes/no. The generation report then carries the
fragile ones with the same justification — the author reads it to decide whether to ask for test ids,
and the human reads it to decide whether to merge.

## What this checklist does **not** decide

KPI-9 — the share of fragile locators in the suite — is counted **statically over merged Page
Objects**, deliberately not from these reports. A metric that triggers an architectural escalation
must not depend on the agent's self-assessment. Both numbers exist; they are not the same number, and
presenting one as the other is a reporting defect.
