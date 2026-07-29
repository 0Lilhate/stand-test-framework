---
description: 'Diagnose a failed stand-test autotest: extract ids, read diagnostics, classify the root cause, propose a fix without hiding the failure.'
version: 1
---

# /stand-test-debug — failed test → debugging report

Failed test output → debugging report with a classified root cause and a proposed fix.
The prime directive: **do not hide the failure.**

## Input

Test output/stacktrace, CI logs, Allure results (if any), the test/scenario source, the
registry, the original `ScenarioDesign.md`.

## Output

Debugging report per
[`debugging-report-template.md`](../skills/stand-test-debugging/debugging-report-template.md).

## Steps

1. **Extract identity** — `scenarioId`, `testRunId`, `correlationId`, `environment`, failed
   `stepId`/`stepType` from the exception message / `ScenarioResult` / Allure parameters.
   These are the search keys: `correlationId` across service logs and Kafka messages,
   `testRunId` in DB rows (`test_run_id`) and the Kafka group id
   (`stand-test-<testRunId>-<topicAlias>`).
2. **Read diagnostics** — run
   [`stand-test-debugging`](../skills/stand-test-debugging/SKILL.md) step 2–4: exception type split
   (`StandTestAssertionError` = the check really failed; `StandTestException` = infra/config),
   known message signatures, await `TimeoutDiagnostics`, Kafka `messagesSeen`/`lastMessages`,
   REST `http.*` diagnostics, gRPC status.
3. **Identify the failed step** — which step, which expectation, what was the last observed
   value.
4. **Classify** — exactly one primary class:
   - `test-data` — wrong/missing seed, collision with leftover data, fixed id;
   - `environment` — registry/env-var/classpath/alias problem;
   - `timeout` — SLA vs configured wait (needs evidence the effect DOES happen later);
   - `assertion` — the expectation itself is wrong vs the original case;
   - `adapter bug` — SDK adapter behaves contrary to its documented contract (minimal repro
     required);
   - `framework bug` — core/runner misbehaviour (minimal repro required, rare).
   Plus the honest sub-verdict where applicable: **`system-defect`** — the test is right and
   the stand is wrong.
5. **Suggest fix** — smallest change that addresses the ROOT cause:
   - `environment` → registry/env-var change (human applies);
   - `test-data` → derive ids from `${testRunId}`, fix seed/cleanup pairing;
   - `timeout` → adjust only with SLA evidence, never blindly inflate;
   - `assertion` → re-read the original case first; changing expected values to match observed
     behaviour requires human confirmation;
   - `system-defect` → the test stays red; produce a defect summary with
     scenarioId/testRunId/correlationId and the diagnostics excerpt;
   - `adapter/framework bug` → minimal repro + report to the SDK owners; do not fork/patch
     the SDK from a consumer test.
6. **Do not hide the failure** — forbidden fixes: try/catch around `stand.run`, deleting or
   weakening the assertion, `@Disabled` without a linked ticket and human approval, timeout
   inflation to the 1h cap, retry loops around the test.

## Human approval points (blocking)

- Any fix that weakens a check (expected value change, timeout increase, test disable).
- Any registry/stand configuration change.
- The `system-defect` verdict (it turns into a bug report, not a code change).
