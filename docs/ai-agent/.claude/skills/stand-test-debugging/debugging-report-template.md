# Debugging Report: <test class / scenario-id> @ <date>

> Output of workflow `failed-test-debugging`. The failure is documented, never hidden.

## Identity

| Key | Value |
|---|---|
| scenarioId | |
| testRunId | |
| correlationId | |
| environment | |
| Failed step (id / type) | |
| Exception type | `StandTestAssertionError` (assertion) / `StandTestException` (infra/config) |

## Failure evidence

```
<exception message + relevant stacktrace lines>
```

Diagnostics extracted:

| Source | Content |
|---|---|
| Await TimeoutDiagnostics (attempts/elapsed/lastValue/lastError) | |
| Kafka (realTopic/partition/offset/messagesSeen/lastMessages sample) | |
| REST (http.method/path/status; last poll mismatch) | |
| DB (query, expected vs lastObserved) | |
| gRPC status | |
| Allure attachments consulted | |

## Matched signature

<row from the known-signatures table in skills/stand-test-debugging.md, or "none">

## Root cause classification (exactly one primary)

**test-data | environment | timeout | assertion | adapter bug | framework bug**

Sub-verdict: `system-defect`? **yes/no** — if yes, the test stays red and this report doubles
as the defect summary.

Reasoning: <why this class and not the neighbours>

## Proposed fix

- Change: <smallest change addressing the root cause>
- Owner: agent / human (registry, env vars, expected-value changes and disables are human calls)
- Evidence required before applying: <e.g. SLA doc for a timeout increase; case re-read for an
  expected-value change>

## Explicitly NOT done (prime directive)

- No try/catch around `stand.run`; no assertion deleted/weakened; no `@Disabled` without a
  ticket; no blind timeout inflation; no retry wrapper.

## Follow-ups

<defect ticket to file / SDK limitation to report / registry change request>
