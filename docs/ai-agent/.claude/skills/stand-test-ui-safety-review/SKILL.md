---
name: stand-test-ui-safety-review
description: Adversarial guardrail review of generated UI artifacts — invented locators, literal URLs, locators outside Page Objects, unmarked secret/PII fields, hand-rolled sign-ins, sleeps and driver waits, irreversible actions, unresolvable ${var} placements, API outside the stand-test-ui surface, production stands, LLM dependence at run time. Mandatory gate after every UI generation; any BLOCK stops the workflow.
version: 1
---

# Skill: stand-test-ui-safety-review

Stage 7 of the UI branch. Review the generated UI artifacts adversarially **before** they are
compiled, run or shown to a human. Assume the artifact is hostile until proven safe.

This skill **adds to** [`stand-test-safety-review`](../stand-test-safety-review/SKILL.md); it does not
replace it. Run both: a UI change usually carries Java, sometimes a registry addition and often a
backend step, and every protocol finding still applies to those.

## When to use

Mandatory after stage 6 and after every regeneration. Also before merging a hand-edit to a generated
UI test — the gate binds to content, so an edited artifact is an unreviewed one.

## Input

Paths, not the authoring transcript: the test class(es), the Page Object(s), the discovery report,
the scenario design, any registry addition, the build diff. Reviewing the transcript reviews the
author's intent; this gate exists to review the lines.

## Output

A report following the protocol
[`safety-review-template.md`](../stand-test-safety-review/safety-review-template.md), with the UI
gate table from
[`ui-safety-checklist.md`](../stand-test-ui-safety-review/ui-safety-checklist.md) appended.
Verdict: `PASS` / `PASS-WITH-NOTES` / `BLOCK`.

## What to detect (finding → severity)

| # | Finding | How to detect | Severity |
|---|---|---|---|
| U1 | **Invented locator** — a locator in a Page Object with no row in `UiDiscoveryReport.md` | cross-read every `UiLocator.*` constant against the report's element table | **BLOCK** |
| U2 | `UiLocator` outside a Page Object | `grep -n 'UiLocator\.' <test sources>` — hits are legal only in `**/ui/pages/**` | **BLOCK** |
| U3 | Literal address | `grep -nE 'https?://|:[0-9]{2,5}/|jdbc:'` over test, Page Objects, reports | **BLOCK** |
| U4 | Secret / PII field not marked `asSensitive()` | every locator whose name or label matches `пароль|password|token|otp|код|карт|снилс|инн|паспорт|телефон|ФИО` | **BLOCK** |
| U5 | Hand-rolled sign-in | a `ui.fill`/`ui.click` sequence against a login form instead of `UiStep.login(...)`; any credential-looking literal in a fill value | **BLOCK** |
| U6 | Sleep or driver wait | `grep -nE 'Thread\.sleep|Awaitility|waitFor|sleep\(|while *\(.*retry'` | **BLOCK** |
| U7 | XPath | `grep -n 'xpath'` — it cannot compile against this SDK, so a hit means either an invented API or a selector smuggled into a CSS string | **BLOCK** |
| U8 | API outside the SDK surface | every type/method against [`ui-sdk-surface-checklist.md`](../stand-test-ui-java-authoring/ui-sdk-surface-checklist.md) | **BLOCK** |
| U9 | `${…}` where it is not resolved | `${` inside a `ui.open` path or inside **any** assertion's expected value — resolution happens for `ui.fill` values and for REST/DB/Kafka/gRPC inputs, and nowhere else | **BLOCK** |
| U10 | Production environment or non-whitelisted alias | the scenario's `environment` against the registry; the application alias against that environment's `ui-applications` | **BLOCK** |
| U11 | Irreversible action outside what is permitted | **two halves, because §5 of the guardrails has two.** In the generated test: any `ui.click` on a control the case did not name, or on data the test did not create. In **discovery**: any irreversible action at all — read the report's *controls stopped-before* list against its screen walk, and treat a screen that could only have been reached through such a control, yet is recorded as observed, as the evidence that one was clicked | **BLOCK** |
| U12 | Discovery under the wrong account | the discovery report's header — anything other than the discovery account, or a sign-in performed with no `discovery-account-ref` declared | **BLOCK** |
| U13 | Secret, session state or personal value in a report | credentials, cookies, storage-state content, real customer data in the discovery or generation report | **BLOCK** |
| U14 | Run-time dependence on a model | a model/agent/HTTP call to an assistant, an embedded prompt, a "self-healing" or description-based locator library, a new dependency of that kind in the build diff | **BLOCK** |
| U15 | Fixed value where the run needs uniqueness | a literal external identifier / entity handle in a `ui.fill` value instead of `${testRunId}` or a capture | **BLOCK** |
| U16 | Missing generation report or missing snapshot of the original generation | **re-review only.** On the first pass this gate runs at stage 7, two stages before those artifacts exist — answer `n/a (first pass)`; there the requirement is carried by stage 9's own checklist and by `/stand-test-ui-validate`'s readiness criteria. When the review runs over an already-delivered artifact (a hand-edit after stage 9), `UiGenerationReport.md`, `original/` and `original.sha256` must exist and cover the edited files | **BLOCK** (of the workflow, not of the code) |
| U17 | `ui.expectEventually` without an explicit `within(...)` | the SDK applies a 30 s default, so the wait **is** bounded — but the test then encodes no SLA and a reader cannot tell 30 s was meant | HIGH |
| U18 | Brittle CSS not flagged | a CSS selector with a 3+ step chain, `nth-child`, a generated class, a tag-only step or a sibling combinator, absent from the report's fragile list | HIGH |
| U19 | `ui.expect` where the screen needs a moment | a check on something rendered after an action, without polling — the classic UI flake | HIGH |
| U20 | Shared mutable state | a `static` mutable field or reused mutable object in the test class or a Page Object (which must hold no state at all) | HIGH |

## What the machine does and does not do here

The write hook (`detectors.json`) runs the **protocol** findings over every Java artifact, so an
address, a secret, a `Thread.sleep`, a raw client and PII are caught in UI files too — U3, U4 (in its
secret half), U6 and U13 have machine backing. **The UI-specific half — U1, U2, U5, U7–U12, U14–U20 —
has no automated detector in this version of the kit.** Nothing intercepts an invented locator, a
locator in a test body, or a `${var}` in a place that does not resolve; the eye is the only net.

Say so in the report rather than implying a clean hook means a clean review. A UI detector set is
planned work, not shipped work.

Runtime backstops that do exist, and that this review must still catch statically: a non-whitelisted
alias and a `ui.login` naming an undeclared role are refused pre-flight by the validator
(`NON_WHITELISTED_UI_APPLICATION`, `UI_LOGIN_ROLE_REQUIRED`, `UI_LOGIN_ROLE_UNKNOWN`); an absolute
path, an assertion on an action step, a `within(...)` on a non-waiting step and a `pollInterval`
larger than its timeout are refused by the builder. A violation that only explodes at run time is
still a defective artifact.

## Procedure

1. Read the discovery report first, then the Page Objects, then the test. In that order: it is the
   only order in which an invented locator is visible, because you learn what was observed before you
   read what was written.
2. `grep`-sweep the patterns above over test sources **and** Page Objects **and** the reports.
3. Check every locator constant against the discovery report's element table, row by row. This is the
   slow part and it is the point of the gate.
4. Check every `${…}` occurrence against the resolution table in the surface checklist.
5. Check the environment and alias against the registry; check the role against `auth.roles`.
6. Run the protocol [`safety-checklist.md`](../stand-test-safety-review/safety-checklist.md) for
   everything that is not UI-specific.
7. Write the report: verdict, findings with `file:line`, and the exact fix per finding.
8. **Record the verdict**, naming exactly the artifacts it covers:
   `node <bundle>/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <files>`.

## Who runs this, and who records it

A separate context — the `stand-test-safety-reviewer` subagent, which has no `Write`. A context that
has just written a UI test reviews its own intent rather than its lines, and an invented locator is
precisely the error that survives self-review: the author remembers deciding it, not observing it.

The verdict is recorded by the context that **called** the reviewer. `record-gate` re-runs the
deterministic half and refuses a `PASS` laid over a blocking finding; it also refuses one when no
subagent has finished since the artifact was last written. Editing an artifact after its review drops
the coverage automatically, because the record binds to the content hash.

## Rules

- Any BLOCK stops the workflow. Regenerate — do not hand-patch around a guardrail.
- Never "fix" a finding by weakening the check it violates: deleting an assertion, widening a timeout
  without SLA evidence, downgrading `expectEventually` to `expect`, or replacing a failing locator
  with a broader one that matches more elements.
- An invented locator is never repaired by rewording it. Go back to stage 3 and observe.
- Zero findings still requires the checklist run.

## Next stage

[`stand-test-ui-quality-review`](../stand-test-ui-quality-review/SKILL.md) — only after this one
passes.
