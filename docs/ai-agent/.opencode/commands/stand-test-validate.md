---
description: 'Final readiness gate for a generated stand-test artifact: schema/compile/run(skip-gate)/safety/quality reviews producing a READY/NOT-READY report for human approval.'
version: 1
---

# /stand-test-validate — generated test → readiness report

Generated test/scenario → readiness report for human approval. This is the last gate before a
commit is proposed.

## Input

The full generated change set: test class and/or scenario document, fixtures, registry
additions (if any, already human-approved), build-file diff (if any).

## Output

Validation report: verdict `READY` / `READY-WITH-NOTES` / `NOT-READY`, evidence per gate.

## Steps

1. **Schema validation** (AI-format artifacts only):
   - networknt `V202012` vs `AiSchemaResources.scenarioSchemaJson()` → empty message set;
   - `new AiScenarioParser().parse(...)` → no exception;
   - `new DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid()` → no
     exception (registry overload only — the one-arg form skips all guardrails).
2. **Compile** — `./gradlew compileTestJava checkstyleTest` in the consumer project
   (Java artifacts; the runner test for AI-format artifacts counts too).
3. **Run the module test** — `./gradlew test --tests '<generated class>'`:
   - with stand env vars absent, the test must be **SKIPPED** (gate works, does not fail);
   - with a configured stand available, run for real; record pass/fail. A failure here goes
     to [Workflow 5](stand-test-debug.md) — do not "adjust" the test inside this workflow.
   - **Prove the real run actually executed.** `BUILD SUCCESSFUL` alone does not mean the test
     ran: `@EnabledIfEnvironmentVariable` skips silently, and a skipped test leaves the build
     green. Exporting the variable in the shell is NOT a reliable channel to the forked test JVM
     (observed: the Gradle JVM saw the variable while the test still skipped). Read the count out
     of `build/test-results/test/TEST-<class>.xml` and require `tests="1" skipped="0"`; if it says
     `skipped="1"`, the stage is NOT-RUN, never PASS. Wire the variable explicitly in the
     consumer's `build.gradle.kts` rather than relying on `export`:
     ```kotlin
     tasks.withType<Test>().configureEach {
       listOf("SHOWCASE_MOCK_BASE_URL").forEach { name ->
         providers.environmentVariable(name).orNull?.let { environment(name, it) }
       }
     }
     ```
4. **Safety review** — [`stand-test-safety-review`](../skills/stand-test-safety-review/SKILL.md)
   over the final artifacts (yes, again — post-compilation edits happen), in the
   `stand-test-safety-reviewer` SUBAGENT. BLOCK = NOT-READY. Record the verdict from THIS context:
   `node <bundle>/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <files>`.
   The re-review is not ceremony: the gate is bound to the CONTENT's hash, so an edit made after the
   earlier verdict has already dropped its coverage, and a "gates: PASS" table over an uncovered
   artifact is the one shape of clean report nobody can tell from a real one.
5. **Quality review** — [`stand-test-test-review`](../skills/stand-test-test-review/SKILL.md),
   in the `stand-test-quality-reviewer` SUBAGENT, against the ORIGINAL case text
   + [`review-checklist.md`](../skills/stand-test-test-review/review-checklist.md)
   + [`flakiness-checklist.md`](../skills/stand-test-test-review/flakiness-checklist.md).
6. **Report readiness** — assemble the report:
   gates table (each gate: PASS/FAIL + evidence), open notes, residual risks
   (e.g. leftover seed rows if a run dies before cleanup), and the exact commands a human can
   replay. Apply [`before-committing-checklist.md`](../skills/stand-test-test-review/before-committing-checklist.md).

## Verdict rules

- `READY` — all gates pass, no CRITICAL/HIGH findings.
- `READY-WITH-NOTES` — gates pass, only MEDIUM/LOW notes remain.
- `NOT-READY` — any gate failed or CRITICAL/HIGH finding open; loop back to the authoring
  workflow. Never downgrade a finding to reach READY.

## Human approval points (blocking)

- The commit itself: a generated test is never merged without an explicit human decision on
  this report.
