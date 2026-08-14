---
description: 'Reconnaissance of a live DEV/IFT screen on its own — open the whitelisted application alias under the restricted discovery account, record elements, locators, texts, states and transitions as quoted evidence, perform no irreversible action. Use for the first generation on a screen, or when a merged test starts failing on locators.'
version: 1
---

# /stand-test-ui-discover — look at the screen, record what is there

Stage 3 of the UI branch, invocable on its own. Two occasions:

- **before a first generation** on a screen, as part of
  [`/stand-test-ui-design`](stand-test-ui-design.md);
- **after a merged test starts failing on locators**, to answer the only question that matters then —
  did the UI drift, or was the locator always wrong? An old discovery report cannot answer it; a new
  one can.

## Input

The application alias and the screens to look at (from `UiCase.md`, or named directly), the
environment registry, and a browser-automation channel.

## Output

`UiDiscoveryReport.md` per
[`ui-discovery-report-template.md`](../skills/stand-test-ui-discovery/ui-discovery-report-template.md).

## Steps

1. **Confirm the perimeter before opening anything** — environment key (DEV/IFT, never production),
   the alias declared in that environment's `ui-applications`, `auth.scheme`, `auth.challenge`,
   `auth.discovery-account-ref`. Write them into the report header.
2. **Sign in as the discovery account** if the flow is behind a sign-in. Never a pool account, never
   a privileged role "to see more", never a personal account. No `discovery-account-ref` ⇒ everything
   behind the sign-in is unexplorable: record it and stop.
3. **Walk the path**, screen by screen, stopping before every irreversible control — Delete, Confirm,
   Pay, Send, Approve, or anything whose label or dialog says something will happen. Do not trigger
   native dialogs; they block the channel and end the session.
4. **Record every element** the case touches with **every rung that exists**: `data-testid`, ARIA role
   + accessible name, label, stable attributes, visible text, a CSS fallback. Then the chosen locator
   and why a higher rung was unavailable — see
   [`locator-selection-checklist.md`](../skills/stand-test-ui-discovery/locator-selection-checklist.md).
5. **Check uniqueness** of each chosen locator against the screen state its step will run in. A
   locator matching two elements is a BROKEN run, not a failed assertion.
6. **Quote every text verbatim** — case, punctuation, «ёлочки», non-breaking spaces. Where the screen
   and the case disagree, record both and flag it; that is a finding for a human, not a value to
   average out.
7. **Record the states and transitions**, and then **what you could not see and why**. The unexplored
   list feeds the generation report's *not covered* section directly.
8. **Propose, do not apply**, any registry addition the sign-in needs (`auth.login.*` locator
   expressions, a viewport profile). A human applies stand configuration.

## Hard rules

- Alias only; the base address never appears in the report.
- DEV/IFT only.
- Discovery account only.
- **No irreversible action, no writes, no dialogs.**
- Quote, never conclude: an inferred `data-testid` is an invented locator with a plausible name.
- No credential, session state or personal value in the report — record the *shape* of personal data
  and mark the element for `asSensitive()`.
- No channel ⇒ discovery is **blocked**. A human-exported DOM snapshot is usable at medium confidence
  and must be labelled; a screenshot establishes labels and existence only and **never** a
  `data-testid`, a role or an attribute. An empty report is a correct outcome; a fabricated one is not.

## Mandatory checks

- [ ] Report header complete: environment, alias, account kind, viewport, channel, date.
- [ ] Every element row lists all rungs that exist, plus the chosen locator and its justification.
- [ ] Uniqueness checked and recorded.
- [ ] Texts quoted verbatim; case/screen disagreements flagged.
- [ ] Irreversible controls listed under *stopped before*, with the screens left unexplored.
- [ ] No address, credential or personal value anywhere.
- [ ] Registry proposals listed separately as proposals.

## When this was run because a test started failing

Diff the new report against the old one and say which of the three happened, because the fix differs:

| Finding | Fix |
|---|---|
| the element still exists, its locator changed | update the Page Object constant; note in the generation report that every test using it is affected |
| the element is gone or the flow changed | back to stage 4 — the design is what is wrong |
| the locator never matched what the test assumed | back to stage 3's own rules — this was an invented locator that reached a branch |
