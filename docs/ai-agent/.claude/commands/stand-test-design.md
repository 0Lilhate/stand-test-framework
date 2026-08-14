---
description: 'Convert a plain-text business case into a stand-test scenario design: case analysis, knowledge-base lookup, environment mapping, technical design. No code generated; human approves registry additions, KB gaps and blocking questions.'
version: 1
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
2. **Knowledge-base lookup** — run [`stand-test-kb-lookup`](../skills/stand-test-kb-lookup/SKILL.md)
   when the project keeps a KB (`knowledge-base/`). Produce the
   `KnowledgeBaseLookupResult`: matched entry ids per transport, `missing` items, assumptions,
   confidence. Contract details (paths, fields, tables, gRPC methods) come from here or from
   the case text — never from plausibility. No KB in the project → record that explicitly; the
   case text is then the only contract source and everything it does not state is blocking.
3. **Identify missing information** — split into:
   - *Safe assumptions* (defaults, single environment, SDK-owned correlation, testRunId-scoped
     cleanup) — record and proceed, do not ask.
   - *Blocking items* (missing alias, exact expected values for equals-only checks, write
     permission, correlation strategy, auth identity, unknown operation contract — a KB
     `missing` row for a needed method/path/schema/table/gRPC method) — stop and ask the human.
4. **Produce assumptions** — an explicit `Assumptions` list in the analysis; every assumption
   must be visible to the reviewer, never silent.
5. **Map systems to SDK capabilities** — run
   [`stand-test-environment-mapping`](../skills/stand-test-environment-mapping/SKILL.md).
   Produce the mapping report: resolved aliases (+ correlation/auth/write properties),
   missing aliases with proposed registry blocks, forbidden directs, required env vars.
   Flag every `NOT-AUTOMATABLE (current SDK)` check.
6. **Design** — run [`stand-test-scenario-design`](../skills/stand-test-scenario-design/SKILL.md).
   Choose the track (Java DSL default; AI format only if 100% inside the executable subset),
   produce the step table, captures, timeouts, correlation, cleanup — every contract detail
   citing its KB entry id / case-text value / recorded assumption.

## Mandatory checks

- [ ] [`before-generating-checklist.md`](../skills/stand-test-scenario-design/before-generating-checklist.md) passes.
- [ ] Every alias in the design is resolved or human-approved for addition.
- [ ] KB lookup ran (or its absence is recorded); no blocking `missing` rows remain; every
      contract detail in the design cites a KB id, a case-text value or an assumption.
- [ ] Track decision includes an executable-subset verification when AI format is chosen.

## Human approval points (blocking)

1. **Registry additions** — any new alias/environment block (it is stand configuration).
2. **Blocking missing information** answers — including KB `missing` rows (a new KB entry goes
   through [`/stand-test-kb-update`](stand-test-kb-update.md), not through improvisation).
3. **The design itself** when the case is a regression port or touches DB writes — the human
   confirms coverage equivalence and write permission before generation starts.
