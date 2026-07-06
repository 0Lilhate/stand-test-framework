---
description: Convert a plain-text business case into a stand-test scenario design: case analysis, environment mapping, technical design. No code generated; human approves registry additions and blocking questions.
---

# /stand-test-design — text case → scenario design

Text case → reviewed technical scenario design. No code is produced in this workflow.

## Input

Plain-text case (ticket / manual regression steps / Cucumber feature / free text)
+ access to the consumer project (registry, existing tests).

## Output

`ScenarioDesign.md` (per [`scenario-design-template.md`](../skills/stand-test-scenario-design/scenario-design-template.md)),
ready for an authoring workflow.

## Steps

1. **Analyse** — run [`stand-test-case-analysis`](../skills/stand-test-case-analysis/SKILL.md).
   Produce `TestCaseAnalysis.md`: goal, preconditions, data, trigger, expected effects per
   transport, timeouts, cleanup, negative paths.
2. **Identify missing information** — split into:
   - *Safe assumptions* (defaults, single environment, SDK-owned correlation, testRunId-scoped
     cleanup) — record and proceed, do not ask.
   - *Blocking items* (missing alias, exact expected values for equals-only checks, write
     permission, correlation strategy, auth identity) — stop and ask the human.
3. **Produce assumptions** — an explicit `Assumptions` list in the analysis; every assumption
   must be visible to the reviewer, never silent.
4. **Map systems to SDK capabilities** — run
   [`stand-test-environment-mapping`](../skills/stand-test-environment-mapping/SKILL.md).
   Produce the mapping report: resolved aliases (+ correlation/auth/write properties),
   missing aliases with proposed registry blocks, forbidden directs, required env vars.
   Flag every `NOT-AUTOMATABLE (current SDK)` check.
5. **Design** — run [`stand-test-scenario-design`](../skills/stand-test-scenario-design/SKILL.md).
   Choose the track (Java DSL default; AI format only if 100% inside the executable subset),
   produce the step table, captures, timeouts, correlation, cleanup.

## Mandatory checks

- [ ] [`before-generating-checklist.md`](../skills/stand-test-scenario-design/before-generating-checklist.md) passes.
- [ ] Every alias in the design is resolved or human-approved for addition.
- [ ] Track decision includes an executable-subset verification when AI format is chosen.

## Human approval points (blocking)

1. **Registry additions** — any new alias/environment block (it is stand configuration).
2. **Blocking missing information** answers.
3. **The design itself** when the case is a regression port or touches DB writes — the human
   confirms coverage equivalence and write permission before generation starts.
