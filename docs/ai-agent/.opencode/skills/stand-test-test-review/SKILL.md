---
name: stand-test-test-review
description: Quality review of a generated stand-test autotest — coverage vs the original case, assertion correctness (matcher/type/equals-only asymmetry), non-flaky awaits, correlation, captures, cleanup, reporting metadata, negative cases. Use after safety review passes, before human approval.
version: 1
---

# Skill: stand-test-test-review

Review the **quality** of a generated autotest (safety is a separate, earlier gate —
`stand-test-safety-review`). Output feeds the human approval decision.

## When to use

After safety review passes and the artifact compiles / validates.

## Input

Generated test/scenario + fixtures + the `ScenarioDesign.md` it was built from + the original
text case.

## Review dimensions

### 1. Coverage vs the case
- Every expected effect from the analysis is asserted by exactly one step; nothing silently
  dropped (check the `NOT-AUTOMATABLE` flags are documented in the test's javadoc/comments,
  not just lost).
- Negative paths from the design exist (`assertThatThrownBy(...).isInstanceOf(StandTestAssertionError.class)`),
  or their absence is justified.

### 2. Scenario readability
- Scenario id and step ids are business-meaningful kebab-case; step order tells the story
  (`seed → trigger → await → verify → cleanup`).
- The behaviour is stated, not the mechanics — on the Java track in `@DisplayName` + the class
  javadoc, on the AI track in the document's `title`/`description`. Do NOT demand
  `Scenario.builder(...).title(...)` on the Java track: the field exists but no reporter reads
  it (neither `ScenarioEvent` nor `StepEvent` carries it), so `@DisplayName` is the only
  metadata that reaches Allure.
- Traceability: for regression ports, each step references the manual case step it automates —
  javadoc on the Java track, step `description` in an AI document. Note the step description
  is read by humans only; Allure names steps by type + id.

### 3. Assertion correctness
- Matchers match intent: "equals" vs "contains" vs regex; `MATCHES` is FULL-string; `EXISTS`
  vs `NOT_NULL` chosen deliberately (JSON `null` counts as *present* for EXISTS).
- Types match JSON types: `"100"` ≠ `100`; numbers compare by value (`100` == `100.0`);
  gRPC enums asserted as protobuf JSON names.
- Definite JSONPaths with presence matchers (no `$..x` / `[*]`).
- Kafka/DB assertions are equals-only — verify nobody smuggled a `matcher` key into hand-built
  kafka wire params (it would be **silently ignored**; only builders/parsers are safe). gRPC is
  NOT equals-only: `GrpcStepParameters` reads the `MATCHER` key, so all 5 matchers apply there
  exactly as on REST.
- No assertions on sensitive fields (failure messages echo expected/actual leaf values).

### 4. Non-flaky awaits
- Every async check goes through `expectEventually`/`kafka.expect` with an explicit, realistic
  timeout (not 1h "to be safe"; not 1s "to be fast").
- Kafka: the trigger and the `expect` are in the SAME scenario (prepare-phase arming);
  selection uses a per-run-unique discriminator — `correlationIdFromContext` or a
  `${testRunId}`-derived key (a selection-free expect, or a constant key alone, is refused at run
  time and flakes on a shared stand); one expect per expected message.
- DB polling SELECT is written to return at most one row (id predicate present).
- **Each `db.expectEventually` reads the store the SUT actually writes, bound by the run's OWN key.**
  Confirm against the consumer's write path that the probe's table/link/join is populated by the
  pipeline under test (a store populated by a DIFFERENT path, or never populated at all, gives a
  permanent `lastObserved=0` that reads as a defect). Bind the run by `decisionlabel`/`${testRunId}`,
  never by a value the SUT may reuse: where the SUT can reuse an existing entity, assert the
  **format** of its code/id (regex), not equality to the current run's key. A value that lives only
  in a store the run never reaches must be proven by an observable proxy in the store it does, with
  the gap recorded as an explicit boundary — not chased with a longer timeout.
- Full checklist: [`flakiness-checklist.md`](../stand-test-test-review/flakiness-checklist.md).

### 5. Correlation usage
- `.injectCorrelationId()` on the initiating step whenever a downstream expect correlates;
  the alias's registry entry carries the matching `correlation:` config (HEADER for
  REST/Kafka, METADATA for gRPC).
- No hardcoded correlation values anywhere.

### 6. Variable capture/resolve
- Every `${var}` has an upstream producer (`capture`) or is a built-in
  (`scenarioId|testRunId|correlationId|environment`).
- Captured values are actually used (dead captures are noise) and non-null by construction
  (a null capture fails the run).
- No fixed system-generated ids where a capture should be.

### 7. Cleanup, data hygiene & parallel isolation
- Every `db.seed` has a matching `db.cleanup` scoped by `whereTestRunId`, and the seed declares
  `taggedByTestRunId("<col>")` naming the SAME column; seeded rows carry `test_run_id`; every
  run-varying id derives from `${testRunId}`/a capture (a fixed literal primary key collides across
  concurrent runs).
- Parallel-safe: no shared mutable static/instance state in the test class; NO `@StandParallelSafe`
  on an ordinary test; `@StandIsolated`/`@ResourceLock` present only for a resource that cannot be
  `testRunId`-isolated (fixed port, shared file, process-wide singleton).
- Residual-data note present: cleanup does not run after an earlier failed step
  (short-circuit) — leftover rows must be identifiable and harmless.

### 8. Diagnostics & reporting
- Step ids make Allure step names readable (`<stepType> <stepId>`).
- Tags set (they become Allure labels); scenario id/environment meaningful in reports.
- The test does not assert on Allure content (reporting is best-effort by contract).

### 9. Wiring & gating
- Correct consumer shape (`@SpringBootTest` + `@Autowired StandClient`, or `@StandTest`
  parameters passed into the builder explicitly).
- Run gate optional: a missing `@EnabledIfEnvironmentVariable` is NOT a finding. If present, it must
  name a variable the registry actually requires and that has no default — otherwise it silently
  skips a runnable test.
- Happy path asserts `result.isSuccessful()`; no inspection of `StepStatus.FAILED` as a
  pass/fail channel (the runner throws — a returned result is diagnostics).

## Output

Report per [`generated-test-review-template.md`](../stand-test-test-review/generated-test-review-template.md):
verdict `APPROVE` / `APPROVE-WITH-NOTES` / `CHANGES-REQUIRED`, findings ranked
CRITICAL/HIGH/MEDIUM/LOW with file:line and concrete fixes.

## Rules

- CHANGES-REQUIRED on any CRITICAL/HIGH; loop back to the authoring skill.
- Do not fix findings by deleting checks or widening timeouts.
- The final merge decision is always human.
