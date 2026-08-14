---
name: stand-test-ui-generation-report
description: Produce the eight-section generation report that closes every UI generation — what is covered, what is not and why, assumptions, fragile locators, the UI-to-backend binding, safety and quality gate results, the list of created files, and the preserved original generation (copy or hash) that is the diff base for KPI-4. Nothing is delivered to a human without it.
version: 1
---

# Skill: stand-test-ui-generation-report

Stage 9 of the UI branch, and the one that makes the rest reviewable. A generated UI test on its own
tells a reviewer what it checks; it does not tell them what it *stopped* checking, what was assumed,
which locators will break first, or whether the gates ran. The report is where those live, and BR-07
requires all of it.

## When to use

After [`stand-test-ui-quality-review`](../stand-test-ui-quality-review/SKILL.md) returns `APPROVE` or
`APPROVE-WITH-NOTES`, and before the artifacts are handed to a human. A `REWORK` verdict has no
report — it has a re-entry stage.

## Input

`UiCase.md`, `UiCaseCompleteness.md`, `UiDiscoveryReport.md`, `UiScenarioDesign.md`, the generated
files, the safety review report, the quality review report.

## Output

Two things, and the second is not optional:

```
ui-generation/<scenario-id>/
  UiGenerationReport.md      ← the eight sections
  original/                  ← the generation exactly as first emitted
    <TestClass>.java
    <PageObject>.java
    …
  original.sha256            ← path + SHA-256 of every file in original/
```

Template:
[`ui-generation-report-template.md`](../stand-test-ui-generation-report/ui-generation-report-template.md).

Place `ui-generation/` at the consumer repository root unless the project has already chosen a
location — then follow it, and record the choice in the report so the next run agrees.

## The eight sections — none may be omitted

| # | Section | What makes it real rather than nominal |
|---|---|---|
| 1 | **Covered** | one row per expectation of the **original case**, naming the step that covers it — not a prose summary |
| 2 | **Not covered, and why** | split by cause: the SDK cannot express it / discovery could not reach it / the case ruled it out / it is blocked on a human answer. "Nothing" is a legitimate value and must be written |
| 3 | **Assumptions** | every one carried from stages 1–4, restated here where a reviewer will actually see it |
| 4 | **Fragile locators** | every locator above rung 1, with its rung, the reason a higher rung was unavailable, and brittle CSS flagged separately |
| 5 | **UI↔backend binding** | which of the three (correlation / captured screen value / none), and what remains unproven under it |
| 6 | **Gate results** | safety verdict, quality verdict, compile/checkstyle result, whether the test was actually run and against what — each as it happened |
| 7 | **Files created or changed** | every path, with its role, including the Page Objects and the reports themselves |
| 8 | **Original generation** | the copy under `original/` **and** the hashes in `original.sha256` |

## Section 8 in detail — why it exists and how to make it

KPI-4 is *the share of generated tests accepted without edits*, measured by diffing what was
generated against what was merged. Without a preserved base there is nothing to diff against, and the
metric is not merely imprecise — it is unobservable. BRD says so outright, which is why this is a
deliverable and not a nicety.

**Copy or hash?** Both, and they answer different questions:

| Artifact | Answers |
|---|---|
| `original.sha256` | *was it edited at all* — which is exactly what KPI-4 counts |
| `original/` copies | *what was edited, and how much* — which is what the author needs in order to improve the prompts |

The hash alone would keep KPI-4 measurable; only the copy makes the result actionable. Ship both
unless the project has ruled the copies out, and if it has, say so in section 8 — a missing copy is a
recorded decision, not a silent gap.

**How to write it:**

1. Copy each generated file into `ui-generation/<scenario-id>/original/` **with the Write tool**, at
   the moment the file is first complete — before compilation fixes, before review edits. The
   snapshot is of the *generation*, not of the merged result.
   Do **not** use `cp`, `cat >`, `tee` or `sed -i`: the guard refuses a file written by the shell,
   because that route delivers content past the scanner and past the artifact registry.
2. Hash them:
   ```bash
   shasum -a 256 ui-generation/<scenario-id>/original/*    # macOS/BSD
   sha256sum      ui-generation/<scenario-id>/original/*    # Linux
   ```
   and write the output into `original.sha256` with the Write tool.
3. Record in section 8 the date, the prompt-set version the kit reports (`doctor`), and the model, so
   a later "did it get better after the prompt edit" comparison has both sides.

**The snapshot is never edited afterwards.** If regeneration happens, the snapshot is replaced
wholesale and the report says the run was regenerated — an updated snapshot that tracks the merged
file would make every test look accepted-without-edits, which is the metric inverted.

## Rules

- **Report what happened.** A gate that did not run, a test that was never executed because no stand
  is configured, a screen that was never seen — each is stated as such. "READY" over an unrun gate
  spends the reviewer's trust on an unverified artifact.
- **The fragile-locator list is the agent's self-assessment, and it says so.** KPI-9 is counted
  statically over merged Page Objects, deliberately from a different source. Presenting one as the
  other is a reporting defect, and the report carries the sentence that keeps them apart.
- **Coverage is measured against the case, not the design.** A design that lost a check produces an
  artifact that matches it perfectly; the report is the second place that loss can be caught, after
  stage 8.
- **No addresses, no credentials, no personal values, no session state** in the report — it is
  committed and read widely; see the UI guardrails.
- **The report recommends; the human merges.**

## Checklist before handing off

- [ ] All eight sections present and filled (explicit "none" where that is the truth).
- [ ] Section 1 lists every expectation of the original case, with the covering step.
- [ ] Section 2 gives a cause for every uncovered expectation.
- [ ] Section 4 lists every locator above rung 1, and marks brittle CSS separately.
- [ ] Section 6 states verdicts and whether the test actually ran (`skipped="0"` checked in the JUnit
      XML, not the build's exit code).
- [ ] Section 8: `original/` written with the Write tool, `original.sha256` matches it, date and
      prompt-set version recorded.
- [ ] Nothing secret, personal or address-shaped anywhere in the report.

## Next

Human review. On approval, the artifacts are merged and — per the protocol branch's rule — the case →
test link is recorded in `knowledge-base/mappings/`, so the generation is traceable to the case that
asked for it.
