---
description: 'UI business case → validated UI autotest: intake, completeness gate, live DEV/IFT discovery, scenario design, Page Objects, Java authoring, safety and quality gates, generation report with the preserved original generation. The umbrella workflow of the UI branch — start here.'
version: 1
---

# /stand-test-generate-ui-test — UI case → validated UI test

Runs the whole UI branch, stages 1–9, as one workflow. The narrower commands
([`/stand-test-ui-design`](stand-test-ui-design.md), [`/stand-test-ui-discover`](stand-test-ui-discover.md),
[`/stand-test-ui-java`](stand-test-ui-java.md), [`/stand-test-ui-validate`](stand-test-ui-validate.md))
each run a slice of it; invoking one of those does not license skipping the stages before it.

This is the **UI** umbrella. For a REST/Kafka/DB/gRPC case with no browser in it, use
[`/stand-test-generate-java-test`](stand-test-generate-java-test.md) instead. A case with both a UI
path and backend effects belongs here — the branch binds them in one scenario.

## Input

A UI business case: a ticket, manual regression steps, a screenshot walkthrough, or free text. The
form that needs no round of questions is
[`ui-case-template.md`](../skills/stand-test-ui-case-intake/ui-case-template.md) — give it to whoever
writes the cases.

Plus access to the consumer project: the environment registry (`ui-applications`), the knowledge
base, existing UI tests and Page Objects, and a browser-automation channel for stage 3.

## Output

| Artifact | From |
|---|---|
| `UiCase.md` | stage 1 |
| `UiCaseCompleteness.md` | stage 2 |
| `UiDiscoveryReport.md` | stage 3 |
| `UiScenarioDesign.md` | stage 4 |
| Page Object class(es) | stage 5 |
| JUnit 5 UI test class | stage 6 |
| UI safety review report | stage 7 |
| UI quality review report | stage 8 |
| `ui-generation/<scenario-id>/UiGenerationReport.md` + `original/` + `original.sha256` | stage 9 |
| `knowledge-base/mappings/<case-id>.yml` — the case → test link | stage 9 |

## Steps

1. **Intake** — [`stand-test-ui-case-intake`](../skills/stand-test-ui-case-intake/SKILL.md).
   Application alias, environment, role, entry screen, user path with irreversible steps marked,
   observable expectations with exact texts, backend effects, data ownership, residual effects,
   negative paths, timeouts. **No browsing, no locators, no code.**
2. **Completeness gate** —
   [`stand-test-ui-completeness-check`](../skills/stand-test-ui-completeness-check/SKILL.md).
   Every gap into one of four classes: answerable by discovery (defer), answerable from the
   registry/KB (resolve now), safe assumption (record), blocking question (**stop and ask**).
   `BLOCKED` ends the run here — no browser, no draft.
3. **Discovery** — [`stand-test-ui-discovery`](../skills/stand-test-ui-discovery/SKILL.md).
   The live DEV/IFT UI under the **discovery account**, alias only, **no irreversible action**.
   Every element recorded with every locator rung that exists; every text quoted verbatim; every
   unexplored screen listed with its reason.
4. **Scenario design** —
   [`stand-test-ui-scenario-design`](../skills/stand-test-ui-scenario-design/SKILL.md).
   Step table with ids, `ui.login` first with an explicit role, assertions with matchers, bounded
   awaits, captures, the UI↔backend binding, `${testRunId}` scoping, the residual-data verdict, the
   parallelism budget against the account pool.
5. **Page Objects** —
   [`stand-test-ui-page-object-design`](../skills/stand-test-ui-page-object-design/SKILL.md).
   One class per screen; every locator a constant there and nowhere else.
6. **Authoring** —
   [`stand-test-ui-java-authoring`](../skills/stand-test-ui-java-authoring/SKILL.md).
   One `Scenario` composed from the Page Object factories, run through the injected `StandClient`.
   Nothing outside
   [`ui-sdk-surface-checklist.md`](../skills/stand-test-ui-java-authoring/ui-sdk-surface-checklist.md).
7. **UI safety review** —
   [`stand-test-ui-safety-review`](../skills/stand-test-ui-safety-review/SKILL.md) **in a separate
   context** (the `stand-test-safety-reviewer` subagent), plus the protocol
   [`stand-test-safety-review`](../skills/stand-test-safety-review/SKILL.md) for everything that is
   not UI-specific. Any BLOCK ⇒ regenerate, never work around. Record the verdict with `record-gate`.
   **Compile and run** — mechanical, so it carries no stage number of its own:
   `./gradlew compileTestJava checkstyleTest` for the test **and** the Page Objects; then the test
   itself if a stand is configured. Read `build/test-results/test/TEST-*.xml`: `skipped="1"` means the
   environment gate fired and no browser was ever opened — a green build that proves nothing.
8. **UI quality review** —
   [`stand-test-ui-quality-review`](../skills/stand-test-ui-quality-review/SKILL.md) in a separate
   context, against the **original case**. `REWORK` re-enters at stage 6, 5, 4 or 3.
9. **Generation report** —
   [`stand-test-ui-generation-report`](../skills/stand-test-ui-generation-report/SKILL.md).
   Eight sections, plus the snapshot of the generation as first emitted (`original/` +
   `original.sha256`) — the diff base for KPI-4, written with the Write tool, never with `cp`.
   Then **record the case → test link** in `knowledge-base/mappings/`, exactly as the protocol branch
   requires: a reviewed test that no mapping record claims does not let the session end, and the
   half of the record that matters (`matched`, `missing`, `assumptions`) is knowledge the hook does
   not have.

## Mandatory checks

- [ ] Environment is DEV/IFT; the application alias is whitelisted there; **production nowhere**.
- [ ] Discovery ran under the discovery account and performed no irreversible action.
- [ ] Every locator traces to a row of the discovery report.
- [ ] No `UiLocator` outside a Page Object.
- [ ] `ui.login` first, with a declared role; no hand-rolled sign-in.
- [ ] Every wait is `ui.expectEventually` with an explicit bounded `within(...)`; no sleep, no driver
      wait.
- [ ] No `${…}` in a `ui.open` path or in an assertion's expected value (they are not resolved there).
- [ ] Secret/PII locators marked `asSensitive()`.
- [ ] Nothing outside the SDK surface checklist.
- [ ] The test depends on no model at run time.
- [ ] Test and Page Objects compile and pass checkstyle.
- [ ] Safety PASS + quality APPROVE + eight-section report + preserved original generation.
- [ ] Case → test mapping recorded in `knowledge-base/mappings/`.

## Human approval points (blocking)

1. **Blocking questions from stage 2** — the run stops until they are answered.
2. **Registry additions** — a new `ui-applications` alias, sign-in locators, a
   `credentials-pool-ref` (or, for an application with exactly one account, the direct
   `credentials-username`/`credentials-password` pair of format version 4), a
   `discovery-account-ref`: all stand configuration, all human-approved. Propose them as references —
   a proposed block carrying a password VALUE is a finding (`SECRET_IN_SOURCE`), not a shortcut.
3. **Permission for an irreversible flow** when the case creates or changes business data and nobody
   has said the stand is for that.
4. **The merge**, on the generation report. The workflow recommends; it never merges.
