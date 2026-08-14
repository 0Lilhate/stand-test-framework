---
name: stand-test-debugging
description: Diagnose a failed stand-test autotest — extract scenarioId/testRunId/correlationId, split StandTestAssertionError vs StandTestException, match known failure signatures (registry/alias, unresolved env var, kafka messagesSeen, await diagnostics, gRPC status), classify the root cause and propose a fix without hiding the failure. Use when a generated test fails.
version: 1
outputSchema: stand-test-ai-schema/src/main/resources/schema/failure-analysis.schema.json
---

# Skill: stand-test-debugging

Diagnose a failed generated test from its output, classify the failure, and propose a fix —
**without hiding the failure**.

## When to use

A generated test failed (locally or in CI).

## Input

Test output/stacktrace, Allure results if present, the scenario/test source, the registry.

## Step 1 — extract identity

From the exception message, `ScenarioResult`, or Allure test-case parameters:
`scenarioId`, `testRunId`, `correlationId`, `environment`, failed `stepId`/`stepType`.
`correlationId` is the cross-system search key (service logs, Kafka messages);
`testRunId` finds the run's DB rows (`test_run_id` column) and Kafka consumer group
(`stand-test-<testRunId>-<topicAlias>`).

## Step 2 — classify by exception type FIRST

| Signal | Class | Meaning |
|---|---|---|
| `StandTestAssertionError` | assertion | The stand really returned/reached a different value, or the wait timed out. The test may be RIGHT — treat as a possible system defect first. |
| `StandTestException` | infra/config | Registry, env vars, classpath, transport, guardrails. The stand's business logic is NOT refuted. |

## Step 3 — match the message against known signatures

| Message contains | Diagnosis | Fix direction |
|---|---|---|
| `Scenario validation failed: NON_WHITELISTED_ENVIRONMENT` | env not in registry — OR the registry file is missing/empty (silent empty registry when `stand-test-config` is present but no `stand-test-environments.yml`) | check file exists & env key spelling |
| `No EnvironmentRegistry provider found` | `stand-test-config` (or starter) not on test classpath | add the module |
| `... is not whitelisted in environment` (service/topic/target/datasource) | alias missing/typo — surfaces at execution for service/topic/grpc, pre-flight for datasource | fix alias or registry (human approves registry changes) |
| `did not resolve` / `environment variable not set` | `*-ref` env var unset | export the variable; verify the `@EnabledIfEnvironmentVariable` gate lists it |
| `configured as a literal value but it is empty` | Spring-starter endpoint value twin (`base-url`/`url`/`target`/`bootstrap-servers`/`security-protocol`) whose `${VAR:}` env var is unset — Spring bound the empty default at startup, the SDK fails lazily at step execution; the message names NO variable | find the variable inside the twin's placeholder in `application.yml`; export it; verify the gate lists it |
| `sets both '...' and '...-ref' — configure exactly one` | registry alias configured both an endpoint value twin and its `*-ref` — ambiguous, fails Spring context startup before any step | keep exactly one: `${ENV_VAR:...}` in the value field OR the env-var NAME in `*-ref` (human applies) |
| `carries the SDK-internal literal marker` | a `literal://` value was hand-written into a `*-ref` field — the marker is internal-only | use the sibling value field with a `${ENV_VAR:...}` placeholder, or a plain env-var NAME in the `*-ref` |
| `No step executor registered for step type` | adapter module missing from test classpath | add `stand-test-rest/-kafka/-db/-grpc` |
| `Unresolved variable: '${...}'` | capture missing/typo, or step order wrong | add/fix the producing capture |
| `Multiple <SPI> providers` | two registries/publishers on classpath | remove one dependency |
| `rest.expectEventually ... did not observe` + last mismatch (`Expected HTTP status 200 but got 503`) | timed out polling; 5xx polls through by design | check SLA vs timeout; check stand health via correlationId |
| kafka expect timeout + diagnostics `messagesSeen=0` | nothing arrived OR trigger fired outside this scenario (consumers arm at prepare; earlier messages invisible — start-from-now) | trigger and expect must share the scenario; check correlation config/name |
| kafka `messagesSeen=N>0` + `lastMessages=[...]` sample | messages arrived but selection didn't match (correlation header name/value, key discriminator) | compare sample headers vs registry `correlation.name` |
| `buffered more than 10000` | selection matches nothing on a busy topic | fix selection; narrower topic |
| db `ambiguous` (>1 row) | polling SELECT under-constrained | add id predicate |
| `JSONPath assertion failed at '...': expected X but got Y` | value mismatch — possibly a REAL defect | verify expected value against the case before touching the test |
| `Response body is not valid JSON` (REST) / `Message value is not valid JSON` (Kafka) / `Response is not valid JSON` (gRPC) | payload is non-JSON/empty; JSONPath assertions need JSON | assert status-only (REST), or fix the expectation |
| gRPC `Server Reflection (grpc.reflection.v1) is not implemented by the target` | server lacks Reflection v1 (or exposes v1alpha only / reflection disabled) | not callable by the SDK today — flag |
| gRPC `failed with status UNIMPLEMENTED` (no reflection hint) | the called method/service is not implemented on the target | check `package.Service/Method` spelling |
| gRPC `DEADLINE_EXCEEDED` | deadline too small or stand slow | check SLA; deadline covers reflection + call |
| `db.seed INSERT must declare its testRunId tag column` / `must tag its rows in the declared testRunId column` | seed missing `taggedByTestRunId(...)`, or tagging a column absent from the INSERT list — fails closed so rows are reapable and parallel runs stay isolated | add `taggedByTestRunId("<col>")` naming the SAME column the cleanup filters, present in the INSERT column list |
| `kafka.expect ... has no per-run discriminator` / `uses a constant key ... not per-run-unique` | expect not discriminated per run — refused before arming so concurrent runs don't match each other's messages | add `correlationIdFromContext()` or a `${testRunId}`-derived `key(...)` |
| duplicate/primary-key `SQLException` on a `db.seed`, or db `ambiguous (>1 row)` that appears only under parallel load | a fixed literal id / under-scoped predicate collides with a CONCURRENT run's rows (not just leftover data) | derive the seed PK from `${testRunId}`; scope reads by `${testRunId}` / a captured per-run id |
| `must not carry its own WHERE` / `DESTRUCTIVE_SQL_WITHOUT_ALLOW` / `SECRET_IN_SOURCE` / `UNBOUNDED_TIMEOUT` | generated artifact violated a guardrail (authoring bug) | regenerate via the authoring skill |

## Step 4 — read the diagnostics payloads

- Await timeouts carry `TimeoutDiagnostics`: attempts, elapsed, pollInterval, lastValue,
  lastError.
- Kafka step diagnostics: `kafka.realTopic`, `kafka.partition/offset`, `kafka.messagesSeen`,
  bounded `lastMessages` sample — `partition@offset`, record key and the configured
  correlation header's value only (payloads and other headers are deliberately excluded;
  a correlation header-name mismatch shows as `<name>=null` in the sample).
- REST diagnostics: `http.method/path/status` (bodies/headers never echoed).
- Allure attachments: masked request/response/diagnostics bodies per step.

## Step 5 — classify the root cause (exactly one primary)

`test-data` | `environment/config` | `timeout-too-small` | `assertion-wrong-expectation` |
`system-defect` | `adapter-limitation` | `framework-bug` (rare — needs a minimal repro).

## Step 6 — propose the fix

Report per [`debugging-report-template.md`](../stand-test-debugging/debugging-report-template.md),
written to **`debug/<scenario-id>-<YYYY-MM-DD>.md`** — a file, with the Write tool, and not only a
reply: the next failure of the same test is read against this one. `<scenario-id>` is step 1's; when
the run carried none, name the failing test class.
Rules:

- **Never hide the failure**: no try/catch, no `@Disabled` without a linked ticket, no
  deleting the assertion, no blind timeout inflation (a timeout increase needs SLA evidence).
- If the diagnosis is `system-defect`: the test stays red; the output is a defect summary
  with scenarioId/testRunId/correlationId for the report — not a test change.
- If `environment/config`: propose the registry/env-var fix — human applies it.
- A fix that changes expected values requires re-reading the original case, not just making
  the test green.
