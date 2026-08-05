---
name: stand-test-ui-discovery
description: Reconnaissance of a live DEV/IFT UI — open the whitelisted application alias under the restricted discovery account, walk the case's path without performing any irreversible action, and record observed elements, locators (data-testid → role/name → label → stable attribute → text → CSS), texts, states and screen transitions as quoted evidence. The live DOM is the source of truth; nothing is invented. Produces a discovery report, no code.
version: 1
---

# Skill: stand-test-ui-discovery

Stage 3 of the UI branch, and the stage the protocol branch has no equivalent of. A REST contract can
be read from a document; **a screen cannot**. The `data-testid` of the status field, the accessible
name of the confirm button, the exact wording of a validation message — these exist in exactly one
place, the running application, and every one of them is a value a generated test will compare
against character for character.

Discovery is therefore not "having a look". It is evidence collection, and its output is quoted
observation with a timestamp.

## When to use

After [`stand-test-ui-completeness-check`](../stand-test-ui-completeness-check/SKILL.md) returns
`READY` or `READY-WITH-ASSUMPTIONS`. Also on its own, later, when a merged test starts failing on
locators and the question is whether the UI drifted — that is the same skill, run against the same
screens.

Never before the completeness gate: a case with an unanswered blocking question sends discovery to
the wrong screen, and the wrong screen is worse than no screen because it produces confident evidence.

## Input

- `UiCase.md` + `UiCaseCompleteness.md` (its deferred-to-discovery list, class A, is the work list).
- The environment registry: the application's `base-url-ref`, `auth`, viewport profiles.
- The knowledge base's UI entries, if curated — the starting hypothesis, never the verdict.

## Output

`UiDiscoveryReport.md` following
[`ui-discovery-report-template.md`](../stand-test-ui-discovery/ui-discovery-report-template.md).

## The channel

Discovery needs a browser-automation channel. The opencode bundle ships one (`playwright` MCP, with
every call gated by `mcp_*: ask`, so a human sees each browser action); a Claude Code installation
uses whatever browser MCP the project has configured.

**If no channel is available, discovery is blocked** — say so and stop. There are exactly two
fallbacks, both of which must be labelled as such in the report:

| Fallback | Confidence | Rule |
|---|---|---|
| A DOM/accessibility snapshot exported by a human from the same stand | medium | usable, but marked `source: human-snapshot` with the date; a stale snapshot is a stale locator |
| A screenshot | **low** | may establish *that* an element exists and what it is labelled; **never** establishes a `data-testid`, a role or an attribute — those are unobservable in a picture and must not be written down as if they were seen |

Guessing a locator because the channel was unavailable is the single most damaging thing this branch
can do. An empty discovery report is a correct outcome; a fabricated one is not.

## Hard rules for the session

1. **Alias only.** Open the application through its registry alias and a relative path. The base URL
   comes from `base-url-ref` at run time; it is not written into any artifact, not into the report,
   and not into a note. If you need to state which application you looked at, name the alias.
2. **DEV/IFT only.** Confirm the environment key before the first navigation. A production stand is
   never opened, not even to "check the markup is the same".
3. **The discovery account, and only it.** Sign in with `auth.discovery-account-ref` (SEC-10). The
   SDK keeps that account out of the working pool, but the check runs when a `ui.login` step parses
   the roster — and discovery never executes one, so **nothing verifies this at stage 3**. It holds
   because you make it hold, and the report header is the only evidence. Never lease a pool account,
   never use a privileged role to see more, never use a personal account.
   No `discovery-account-ref` for an application behind a sign-in ⇒ everything behind it is
   **unexplorable**; record the gap and stop, do not improvise a way in.
4. **No irreversible action.** Do not click Delete, Confirm, Pay, Send, Approve, Revoke, or anything
   whose label, tooltip or confirmation dialog says something will happen. Walk the path up to the
   last control before the effect, record that control, and stop. If a screen can only be reached
   *through* an irreversible step, the screen is unexplored — say which screen and why. Never resolve
   the ambiguity by clicking to find out.
5. **No dialogs.** Do not trigger native `alert`/`confirm`/`prompt`: they block the automation channel
   and end the session. If a control is known to raise one, stop before it.
6. **No writing.** Discovery does not fill and submit forms that persist anything, does not upload,
   does not change settings, does not create test data. Filling a field to observe a validation
   message is allowed **only** when the form is not submitted and the field is not persisted as a
   draft; if drafts are possible, treat it as irreversible.
7. **Quote, do not conclude.** Record the attribute as spelled, the accessible name as read, the text
   verbatim — including case, punctuation, «ёлочки», non-breaking spaces and trailing spaces. A text
   you normalised is a text the test will not match.
8. **No secrets in the report.** No credential, no session cookie, no storage-state content, no
   token in a URL fragment, no personal data read off the screen. If a screen shows real customer
   data, record the *shape* (`ФИО клиента, формат «Фамилия И. О.»`), never the value, and mark the
   element for `asSensitive()`.

## Procedure

1. **Confirm the perimeter** — environment key, alias, `base-url-ref` present, `auth.scheme`,
   `challenge`, `discovery-account-ref`. Write them into the report header before opening anything.
2. **Sign in** as the discovery account, if the flow is behind a sign-in. Record the sign-in form's
   own elements too: the SDK's `ui.login` needs `username-locator`, `password-locator`,
   `submit-locator` and `signed-in-locator` in the registry, and if any of them is missing, this is
   where the values for it come from — as a **proposed registry addition**, for a human to apply.
3. **Walk the case's path**, screen by screen, stopping before every irreversible control.
4. **For every element the case's steps and expectations touch**, record: what the case calls it,
   its `data-testid` if present, its ARIA role and accessible name, its label, its stable attributes,
   its visible text, and a CSS selector as the fallback — *all of them that exist*, not only the one
   you intend to use. The report is also a knowledge-base candidate, and the next generation on this
   screen should not have to re-run discovery to find the rung below.
5. **Choose the locator** for each element by the priority in
   [`locator-selection-checklist.md`](../stand-test-ui-discovery/locator-selection-checklist.md), and
   record *why* a higher rung was unavailable when it was.
6. **Record the states** the expectations need: which controls are disabled before the form is
   valid, what appears only after an action, what is visible without scrolling at the application's
   default viewport.
7. **Record the transitions**: which control leads to which screen, and the relative path of each.
8. **Record what you could not see** and why — the unexplored list is a first-class output, and it
   feeds the generation report's *not covered* section directly.

## What discovery must never produce

- A locator for an element it did not observe.
- A `data-testid` inferred from a naming convention ("the others are `app-*`, so this one is
  `app-submit`"). Conventions are hypotheses; the DOM is the answer.
- An expected text reconstructed from the case instead of read from the screen — the case says what
  *should* be there, discovery says what *is*, and where they differ that is a finding for the human,
  not a value to average out.
- An XPath. There is none in the SDK; see the UI guardrails.
- A claim that an element "is stable" — `UiLocator.fragile()` decides that, and only `TEST_ID` is
  not fragile.

## Checklist before handing off

- [ ] Header records environment, alias, account kind (`discovery`), viewport, date and channel.
- [ ] Every class-A unknown from stage 2 is answered or explicitly listed as unexplored.
- [ ] Every recorded element carries every rung that exists for it, plus the chosen one and its
      justification.
- [ ] Every expected text is quoted verbatim; differences from the case text are flagged, not merged.
- [ ] Every fragile and every brittle locator is marked as such (checklist definitions).
- [ ] No irreversible action was performed; the list of controls stopped-before is written down.
- [ ] No address, credential or personal value appears anywhere in the report.
- [ ] Proposed registry additions (sign-in locators, viewport profile) are listed separately as
      **proposals for a human**, not applied.

## Next stage

[`stand-test-ui-scenario-design`](../stand-test-ui-scenario-design/SKILL.md).

## Feeding the knowledge base

A discovery report is the natural input for a UI knowledge-base entry, so the next case on the same
screen costs no browsing. The KB's UI collections are not part of this version of the kit contract;
until they are, the report itself is the durable artifact — keep it beside the test, and re-run
discovery rather than trusting an old report when a run starts failing on locators.
