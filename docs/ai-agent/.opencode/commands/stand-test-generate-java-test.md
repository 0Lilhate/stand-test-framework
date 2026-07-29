---
description: End-to-end pipeline - text test scenario → KB-grounded scenario design → Java DSL test + fixtures → safety review → compile → run → readiness report. Umbrella over /stand-test-design, /stand-test-java and /stand-test-validate; every contract detail must resolve through the knowledge base.
version: 1
---

# /stand-test-generate-java-test — text scenario → validated Java autotest

The single entry point for "here is a case, give me a test". It runs the three underlying
workflows in their mandatory order and stops at every gate they define. Use the underlying
commands directly when you need only one phase.

## Input

- The text test scenario (ticket / manual steps / free text).
- Target module and base package for the test class.
- Environment id (optional — resolved from the KB when unambiguous).
- Mode: `draft` (default) — artifacts are proposed in the reply, nothing written into the module;
  `apply` — files are written after gates pass.

## Pipeline (fixed order, no stage may be skipped)

```
Text scenario
  → 1. Case analysis            (stand-test-case-analysis)
  → 2. KB lookup                (subagent stand-test-kb-resolver → KnowledgeBaseLookupResult)
  → 3. Missing info / blocking questions   (analysis blockers + lookup `missing`)
  → 4. Environment mapping      (same subagent, same call)
  → 5. Scenario design          (stand-test-scenario-design; contract details cite KB entry ids)
  → 6. Java test generation     (stand-test-java-dsl-authoring)
  → 7. Fixture generation       (stand-test-fixture-authoring)
  → 8. Safety review            (subagent stand-test-safety-reviewer; BLOCK → regenerate)
  → 9. Compile                  (./gradlew compileTestJava [checkstyleTest] in the consumer project)
  → 10. Test run                (skip-gate always; real run when the stand is configured)
  → 11. Readiness report        (subagent stand-test-quality-reviewer → verdict + human approval)
```

Stages 2, 4, 8 and 11 run in a SEPARATE context — a subagent — and the reason differs by stage: 2 and
4 to keep the knowledge base out of the authoring context, 8 and 11 because a review by the context
that wrote the code is a review of its own intent. The rules file states the split; do not fold these
stages back into this context because it would be quicker.

The safety verdict is recorded HERE, not by the reviewer:
`node <bundle>/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <files>`.
It refuses a PASS when the deterministic scan disagrees, and it refuses one when no subagent has
finished since the artifact was last written — so stage 8 cannot be quietly performed in place.

Steps 1–5 are `/stand-test-design` with the KB stage; steps 6–9 are `/stand-test-java`;
steps 10–11 are `/stand-test-validate`. On a failed run: `/stand-test-debug`, then re-enter at
step 6 (or 5 if the design was wrong).

## Steps

1. Run [`/stand-test-design`](stand-test-design.md) — it includes the KB lookup stage. Do not
   proceed while the lookup reports blocking `missing` items or `low` confidence: ask the human.
2. Run [`/stand-test-java`](stand-test-java.md) on the produced `ScenarioDesign.md` (Java DSL is
   the default track; if the design chose the AI format, run [`/stand-test-yaml`](stand-test-yaml.md)
   instead — same gates).
3. Run [`/stand-test-validate`](stand-test-validate.md) over the full change set.
4. Update the case's `testCaseMapping` entry (`generatedTest` block, status `generated` →
   `validated` per the validate verdict).
5. In `draft` mode present all artifacts + the readiness report in the reply; in `apply` mode
   write them into the module and hand the report to the human.

## Forbidden (in addition to every underlying gate)

- Endpoints/topics/DB queries/gRPC methods **not present in the KB lookup result** — a contract
  detail that appears in the generated test but not in `matched` (nor as a cited case-text value
  or recorded assumption) is a BLOCK finding.
- Raw clients, direct URLs/hosts/JDBC/bootstrap strings, inline secrets, `Thread.sleep`,
  destructive SQL, validator bypasses — per
  [`stand-test-guardrails.md`](../rules/stand-test-guardrails.md).

## Output report

```markdown
## Verdict            READY / READY-WITH-NOTES / NOT-READY (from /stand-test-validate)
## Case               <caseId, source>
## KB lookup          matched / missing / assumptions / confidence
## Artifacts          test class, fixtures, mapping entry (paths)
## Gates              analysis, kb-lookup, mapping, design, safety, compile, run - PASS/FAIL/NOT-RUN each
## Open questions     blocking items awaiting the human
```

## Human approval points (blocking)

- Blocking `missing`/questions after step 3.
- Registry or KB additions surfaced by mapping/lookup.
- The final merge decision on the readiness report — the agent never merges on its own authority.
