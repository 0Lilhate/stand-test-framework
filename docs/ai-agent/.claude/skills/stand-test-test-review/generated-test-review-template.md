# Generated Test Review: <scenario-id / test class>

> Output of skill `stand-test-test-review` (quality; safety is a separate report).

## Verdict

**APPROVE | APPROVE-WITH-NOTES | CHANGES-REQUIRED**

## Traceability

- Original case: <link/id>
- `ScenarioDesign.md`: <path>
- Coverage: <N of M expected effects asserted; list anything dropped and where that is documented>

## Dimension results

| Dimension | Result | Notes |
|---|---|---|
| Coverage vs the case (incl. negative paths) | OK/Issues | |
| Scenario readability (ids, order; `@DisplayName`+javadoc on the Java track, `title`/`description` in an AI document) | OK/Issues | |
| Assertion correctness (matchers, JSON types, definite paths, equals-only respected) | OK/Issues | |
| Non-flaky awaits (bounded timeouts, same-scenario kafka trigger+expect, single-row DB polls) | OK/Issues | |
| Correlation usage (inject/fromContext vs registry config; no hardcoded values) | OK/Issues | |
| Variable capture/resolve (every ${var} produced; no dead captures; no fixed ids) | OK/Issues | |
| Cleanup (every seed paired; testRunId-scoped; residual-data note) | OK/Issues | |
| Diagnostics & reporting (step ids, tags, no assertions on Allure content) | OK/Issues | |
| Wiring & gating (StandClient injection, @EnabledIfEnvironmentVariable, isSuccessful happy path) | OK/Issues | |

## Findings

| # | Severity (CRITICAL/HIGH/MEDIUM/LOW) | File:line | Finding | Suggested fix |
|---|---|---|---|---|

## Checklists applied

- [ ] `checklists/review-checklist.md`
- [ ] `checklists/flakiness-checklist.md`

## Recommendation to the human approver

<one paragraph: merge / fix first / discuss>
