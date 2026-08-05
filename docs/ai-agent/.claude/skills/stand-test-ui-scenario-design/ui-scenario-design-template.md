# UI Scenario Design: <scenario-id>

> Output of skill `stand-test-ui-scenario-design` (stage 4). The step list stage 6 transcribes.
> No code here. Every locator cites a row of `UiDiscoveryReport.md`.

## Identity

| Field | Value |
|---|---|
| Scenario id | `ui-application-submitted` |
| Environment | `ift` |
| Tags | `ui`, `integration`, `<domain>` |
| Application alias | `client-portal` |
| Role | `client` (declared in `auth.roles`) |
| Track | **Java DSL — `ui.*` has no declarative surface in this SDK version** |
| Source case | `UiCase.md` (`UI-201`) |
| Discovery report | `UiDiscoveryReport.md`, observed `YYYY-MM-DD` |

## Step table

| # | Step id | Type | Locator (discovery row) | Purpose | Assertions (property · matcher · expected) | Captures | Timeout |
|---|---|---|---|---|---|---|---|
| 1 | `login` | `ui.login` | — | sign in as `client` | — | — | `within 30s`, `accountTimeout 60s` |
| 2 | `open-form` | `ui.open` | path `/applications/new` | open the form | — | — | navigation timeout (config) |
| 3 | `form-is-ready` | `ui.expect` | AMOUNT (row 1) | the form rendered | `VISIBLE·EQUALS·true`, `ENABLED·EQUALS·true` | — | — |
| 4 | `submit-disabled-when-empty` | `ui.expect` | SUBMIT (row 2) | negative check | `ENABLED·EQUALS·false` | — | — |
| 5 | `fill-amount` | `ui.fill` | AMOUNT (row 1) | value `100000` | — | — | action timeout (config) |
| 6 | `amount-was-typed` | `ui.expect` | AMOUNT (row 1) | case §6 row 3 | `VALUE·EQUALS·100000` | — | — |
| 7 | `fill-external-id` | `ui.fill` | EXTERNAL_ID (row 4) | value `ext-${testRunId}` | — | — | action timeout (config) |
| 8 | `submit` | `ui.click` | SUBMIT (row 2) | **irreversible** — creates the application | — | — | action timeout (config) |
| 9 | `await-accepted` | `ui.expectEventually` | STATUS (row 3) | the outcome | `VISIBLE·EQUALS·true`, `TEXT·EQUALS·Принята` | `applicationNumber ← NUMBER (row 5), TEXT` | `within 20s` |
| 10 | `number-issued` | `ui.expect` | NUMBER (row 5) | case §6 row 5 — asserted **after** the poll, so the value is already on screen | `TEXT·MATCHES·AP-\d+` | — | — |
| 11 | `check-backend` | `rest.get` | `applications-service` `/api/applications/${applicationNumber}` | the backend agrees | `$.status·EQUALS·ACCEPTED` | — | — |

Coverage check against the case: all five expectations of `example-ui-case.md` §6 have a step —
rows 1→3, 2→4, 3→6, 4→9, 5→10. A design that silently drops one produces an artifact that matches
the design perfectly and the case not at all, which is the loss stage 8 exists to catch. Note the
matcher on step 10: `MATCHES` is a **full-string** match, so `AP-\d+` holds for a field whose entire
text is the number and fails for `Заявка AP-42`.

Rules this table already encodes: actions carry no assertions and no captures; every expect step
carries at least one; `within(...)` appears only on the polling step and on the sign-in.

## Locator provenance

| Constant | Locator | Rung | Fragile? | Discovery row |
|---|---|---|---|---|
| `AMOUNT` | `UiLocator.label("Сумма")` | 3 | yes | 1 |
| `SUBMIT` | `UiLocator.role("button", "Подтвердить")` | 2 | yes | 2 |
| `STATUS` | `UiLocator.testId("application-status")` | 1 | **no** | 3 |
| `EXTERNAL_ID` | `UiLocator.label("Внешний номер")` | 3 | yes | 4 |
| `NUMBER` | `UiLocator.testId("application-number")` | 1 | **no** | 5 |

## Variables

| Variable | Produced by | Read from | Consumed by |
|---|---|---|---|
| `applicationNumber` | step 8 | `NUMBER`, source `TEXT` | step 9 (REST path) |

Built-ins available without capture: `${scenarioId}`, `${testRunId}`, `${correlationId}`,
`${environment}`.

## UI ↔ backend binding

| Field | Value |
|---|---|
| Chosen binding | **captured screen value** (`applicationNumber`) |
| Why not correlation | no confirmation that the backend propagates the header from the front end (external gate G-6) |
| What is therefore unproven | that the request the browser made is the one the backend served; the link rests on the identifier both sides show |

## Test data

| Field | Class | Value strategy |
|---|---|---|
| Внешний номер | run-unique | `ext-${testRunId}` |
| Сумма | constant | `100000` verbatim from the case |

No calendar literals. No entity-instance handle copied from the case.

## Residual data verdict

| Entity created | Compensation | Verdict |
|---|---|---|
| заявка в статусе «Принята» | **none** — the SDK's undo-log reaches `db.write` only, never a browser action; no delete endpoint is curated | rows accumulate on the stand; stated in the generation report |

## Parallelism budget

| Input | Value |
|---|---|
| Account pool size for role `client` | `N = <…>` |
| Suite parallelism | `P = <…>` |
| Scenario duration estimate | `T = <…> s` |
| Worst-case queue wait `T × (P/N − 1)` | `<…> s` |
| `accountTimeout` chosen | `<…> s` — default 60 s is enough / raised because the arithmetic exceeds it |

Ceiling reminder: parallelism above the pool size makes runs **queue**, not fail — but only within
`accountTimeout`; beyond it the run is `BROKEN`. `maxParallelForks` stays 1 (the pool is in-process).

## Screen → Page Object map

| Screen | Page Object class | Locator constants | Step factories |
|---|---|---|---|
| Новая заявка | `NewApplicationPage` | `AMOUNT`, `EXTERNAL_ID`, `SUBMIT`, `STATUS`, `NUMBER` | `open()`, `expectFormIsReady()`, `expectSubmitDisabled()`, `fillAmount(String)`, `fillExternalId(String)`, `expectAmountTyped(String)`, `submit()`, `awaitAccepted()`, `expectNumberIssued()` |
| Вход | — | handled by `ui.login`; the form's locators live in the **registry**, not in a Page Object | — |

## Negative paths

| # | Check | Construct |
|---|---|---|
| 1 | «Подтвердить» is disabled while «Сумма» is empty | step 4 — an ordinary `assertEnabled(false)`, not an expected exception |

## Assumptions carried into authoring

1. <from `UiCaseCompleteness.md` §C, restated so the authoring stage sees them>

## Capabilities the case asked for and this SDK version lacks

| Asked for | Status | Disposition |
|---|---|---|
| <e.g. screenshot on failure> | absent in `stand-test-ui` | recorded in the generation report as *not covered*; raised with the SDK owners |
