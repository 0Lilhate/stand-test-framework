# UI Generation Report: <scenario-id>

> Output of skill `stand-test-ui-generation-report` (stage 9), required by BR-07. **All eight
> sections are mandatory** — write `нет` / `none` explicitly rather than deleting one. No addresses,
> no credentials, no personal values, no session state.

| Field | Value |
|---|---|
| Case | `<case-id>` — `<title>` |
| Scenario id | `<scenario-id>` |
| Application alias | `<alias>` |
| Environment | `<dev\|ift>` |
| Role | `<role>` |
| Generated on | `YYYY-MM-DD` |
| Prompt-set version | output of `node <bundle>/hooks/stand-guard.mjs doctor` |
| Model | `<model id>` |

---

## 1. What is covered

One row per expectation of the **original case** — including the negative ones.

Rows follow the case's own numbering, so a missing number is visible at a glance.

| # | Expectation (case wording) | Covering step | Assertion | Waited? |
|---|---|---|---|---|
| 1 | поле «Сумма» видимо и доступно | `form-is-ready` | `VISIBLE·EQUALS·true`, `ENABLED·EQUALS·true` | no |
| 2 | кнопка недоступна, пока сумма пуста | `submit-disabled-when-empty` | `ENABLED·EQUALS·false` | no |
| 3 | поле «Сумма» содержит `100000` | `amount-was-typed` | `VALUE·EQUALS·100000` | no |
| 4 | поле «Статус» показывает `Принята` | `await-accepted` | `TEXT·EQUALS·Принята` | 20 s |
| 5 | поле «Номер заявки» формата `AP-<цифры>` | `number-issued` | `TEXT·MATCHES·AP-\d+` (full-string) | no |
| 6 | заявка найдена в сервисе заявок | `check-backend` | `$.status·EQUALS·ACCEPTED` | no |

| Tally | Count |
|---|---|
| Expectations in the case | |
| Covered | |
| Not covered (§2) | |

## 2. What is not covered, and why

| # | Expectation | Cause | Disposition |
|---|---|---|---|
| 1 | скриншот при падении | **SDK** — screenshots/traces/attachments are absent from this version of `stand-test-ui` | raised with the SDK owners; the failure message and await diagnostics are what a red run gives today |
| 2 | экран поданной заявки | **unexplored** — reachable only through an irreversible control, which discovery may not perform | check limited to what the source screen shows; would need an out-of-band prepared application |
| 3 | — | **case** — the case deliberately excludes it | — |
| 4 | — | **blocked** — waiting on a human answer | see §3 and the completeness report |

Causes are `SDK` / `unexplored` / `case` / `blocked`. If nothing is uncovered, write **none** — an
empty section reads as a forgotten one.

## 3. Assumptions

Every assumption carried from intake, completeness, discovery and design, restated here.

| # | Assumption | Made at | Why it is safe | What breaks if it is wrong |
|---|---|---|---|---|
| 1 | ожидание статуса — 20 с | intake | кейс говорит «почти сразу»; меньший предел взять не из чего | тест падает по таймауту, а не молча зеленеет |
| 2 | внешний номер уникален через `${testRunId}` | design | иначе повторный и параллельный прогон получают дубликат | первый же повторный прогон краснеет |

## 4. Fragile locators

> **This list is the agent's self-assessment.** KPI-9 — the share of fragile locators in the suite —
> is counted **statically over merged Page Objects**, deliberately from a different source, because a
> metric that triggers an architectural escalation must not depend on the generator's own report.
> The two numbers are not the same number.

One row per locator constant of every merged Page Object — **all of them**, not only the fragile
ones, or the tally below cannot be checked against the file.

| # | Constant | Locator | Rung | Fragile | Brittle | Why not a higher rung |
|---|---|---|---|---|---|---|
| 1 | `STATUS` | `testId=application-status` | 1 | **no** | no | — |
| 2 | `NUMBER` | `testId=application-number` | 1 | **no** | no | — |
| 3 | `SUBMIT` | `role=button:Подтвердить` | 2 | yes | no | no `data-testid` on the control |
| 4 | `AMOUNT` | `label=Сумма` | 3 | yes | no | no `data-testid`; the label is not associated with the control, so the role carries no accessible name |
| 5 | `EXTERNAL_ID` | `label=Внешний номер` | 3 | yes | no | the same, on the same form |

| Tally | Count |
|---|---|
| Locators total | |
| Fragile (everything except `TEST_ID`) | |
| Brittle CSS | |

**Requested of the product team:** `data-testid` on `<elements>` — that would move `<n>` locators to
rung 1.

## 5. How the UI is bound to the backend

| Field | Value |
|---|---|
| Binding used | **captured screen value** / SDK correlation id / **none** |
| Mechanism | `capture("applicationNumber", NUMBER)` on `await-accepted` → `${applicationNumber}` in the `check-backend` REST path |
| Why not the correlation id | no confirmation that the front end's header is propagated end to end (external gate G-6) |
| What remains unproven | that the request the browser issued is the one the backend served; the link rests on the identifier both sides display |
| Correlation injected on UI steps | yes — `injectCorrelationId()` on `open-form` (header `X-Correlation-Id`) |

Where the binding is **none**, say so plainly: the UI and the backend are then asserted
independently, and nothing proves they were the same transaction.

## 6. Gate results

| Gate | Verdict | Evidence |
|---|---|---|
| UI safety review (stage 7) | `PASS` / `PASS-WITH-NOTES` / `BLOCK` | report path; `record-gate` output |
| Protocol safety review | `PASS` / … | report path |
| UI quality review (stage 8) | `APPROVE` / `APPROVE-WITH-NOTES` / `REWORK` | report path |
| Compile | `./gradlew compileTestJava` — PASS/FAIL | |
| Checkstyle | `./gradlew checkstyleTest` — PASS/FAIL | |
| Test executed | **yes against `<environment>`** / **no — no stand configured (gate skipped it)** | `build/test-results/test/TEST-<class>.xml`: `tests=`, `skipped=` |

> A build that is green because the environment gate skipped the test is **not** a passing test. Read
> the JUnit XML, not the exit code, and write here what it said.
>
> Automated coverage, stated honestly: the write hook's protocol detectors run over the Java
> artifacts (addresses, secrets, sleeps, PII); the **UI-specific findings have no automated detector
> in this version of the kit** and were checked by eye.

## 7. Files created or changed

| Path | Role | New / changed |
|---|---|---|
| `src/test/java/…/ui/ApplicationSubmittedUiTest.java` | the test | new |
| `src/test/java/…/ui/pages/NewApplicationPage.java` | Page Object | new |
| `ui-generation/<scenario-id>/UiGenerationReport.md` | this report | new |
| `ui-generation/<scenario-id>/original/…` | snapshot of the generation (§8) | new |
| `UiCase.md`, `UiCaseCompleteness.md`, `UiDiscoveryReport.md`, `UiScenarioDesign.md` | stage artifacts | new |
| `knowledge-base/mappings/<case-id>.yml` | case → test link | new |

Registry additions proposed and **not** applied (a human applies them):

| Path | Field | Proposed value |
|---|---|---|
| `ui-applications.<alias>.auth.login.signed-in-locator` | | `testId=user-menu` |

## 8. Original generation (diff base for KPI-4)

| Field | Value |
|---|---|
| Snapshot location | `ui-generation/<scenario-id>/original/` |
| Hash file | `ui-generation/<scenario-id>/original.sha256` |
| Taken at | the moment each file was first complete — **before** compile fixes and review edits |
| Written with | the Write tool (a shell-written file bypasses the scanner and the artifact registry) |
| Regenerated since? | no / yes on `YYYY-MM-DD`, snapshot replaced wholesale |

```
<contents of original.sha256 — one "sha256  path" line per generated file>
```

KPI-4 is *the share of tests accepted without edits*: the hashes answer **whether** the merged file
differs from the generated one, and the copies answer **how much** and **where** — which is what the
prompt author needs. If this project has decided not to keep the copies, record that decision here;
the metric survives on the hashes alone, the improvement loop does not.

---

## Recommendation

**MERGE / MERGE-WITH-FOLLOW-UPS / DO NOT MERGE**, and why — in one paragraph a reviewer can act on.
This report recommends; the human decides.
