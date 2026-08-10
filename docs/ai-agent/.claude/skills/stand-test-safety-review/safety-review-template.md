# Safety Review: <scenario-id / test class>

> Output of skill `stand-test-safety-review`. One report per generated change set.

## Verdict

**PASS | PASS-WITH-NOTES | BLOCK**

## Scope reviewed

| Artifact | Path |
|---|---|
| Test class | |
| Scenario document | |
| Fixtures | |
| Registry additions | |
| Build diff | |

## Gate results

| # | Gate | Result | Evidence |
|---|---|---|---|
| 1 | No arbitrary URLs / direct hosts / JDBC / brokers | PASS/FAIL | grep output summary |
| 2 | No secrets / inline auth headers / value-shaped refs | PASS/FAIL | |
| 3 | No destructive SQL; writes only seed/cleanup; cleanup has no own WHERE + `whereTestRunId`; every seed declares `taggedByTestRunId` = the cleanup's column; no `param("testRunId", ...)` | PASS/FAIL | |
| 4 | No production environment | PASS/FAIL | registry evidence |
| 5 | Every async wait has bounded timeout (≤1h; AI grammar respected) | PASS/FAIL | |
| 6 | No Thread.sleep / manual polling / SQL sleep functions | PASS/FAIL | |
| 7 | No scripts / unknown fields (schema run for AI docs) | PASS/FAIL | validator output |
| 8 | No raw HTTP/Kafka/JDBC/gRPC clients | PASS/FAIL | import scan |
| 9 | No validator/pipeline bypass (manual runner, bean overrides, one-arg validate as gate) | PASS/FAIL | |
| 10 | No fixed ids without `${testRunId}` | PASS/FAIL | |
| 11 | No hardcoded correlation values | PASS/FAIL | |
| 12 | No caught SDK failures outside `assertThatThrownBy` | PASS/FAIL | |
| 13 | No reliance on Allure masking for secrets | PASS/FAIL | |
| 14 | No PII / production data in fixtures | PASS/FAIL | |
| 15 | No unsanctioned dependencies | PASS/FAIL | build diff |
| 16 | Every `kafka.expect` has a per-run discriminator (`fromContext` or `${...}`-key; no constant-key-only) | PASS/FAIL | |
| 17 | No shared mutable static/instance state in the test class (parallel-safe) | PASS/FAIL | |
| 18 | No failure concealment — needs the artifact's PREVIOUS version (the write hook has it; in CI pass `scan --against <base>`): no assertion count dropped, no `@Disabled` without a ticket, no new `catch`, no timeout grown at an unchanged number of waits | PASS/FAIL/**NOT-RUN** | which previous version was compared, or that there was none |

## Findings

| # | Severity (BLOCK/HIGH) | File:line | Finding | Required fix |
|---|---|---|---|---|

## Commands executed

```
<grep sweeps, schema validation command, parse-gate result>
```

## Notes for the human reviewer

<residual risks, assumptions relied upon>
