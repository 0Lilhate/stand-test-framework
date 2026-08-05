---
name: stand-test-ui-case-intake
description: Intake of a business case for a UI autotest — turn free-form text (a ticket, manual regression steps, a screenshot walkthrough) into the structured UI case the rest of the UI branch consumes — application alias, role, entry screen, user path, observable expectations, data ownership, residual effects, negative paths. Produces analysis only, no code and no browsing. Use FIRST whenever asked to generate a UI test.
version: 1
---

# Skill: stand-test-ui-case-intake

Stage 1 of the UI branch. Convert whatever the case actually is — a ticket, five lines in a chat, a
manual regression script, a Confluence table — into `UiCase.md`: the structured description the rest
of the branch reads. **No browsing here, no code, no locators.** Discovery happens at stage 3, and
doing it early is how a case gets shaped around the first screen the agent happened to see.

## When to use

The moment a UI autotest is requested. Before the KB, before the browser, before the design.

The protocol sibling is `stand-test-case-analysis`; use that one when the case is REST/Kafka/DB/gRPC
with no browser in it. A case that has both a UI path *and* backend effects starts here — the UI
branch can bind to the backend through captured screen values (see stage 4), and rewriting the case
twice loses the link.

## Input

- The raw case text, verbatim.
- Whatever the author attached: screenshots, a screen recording, a link to the manual case.
- Access to the consumer project: the environment registry (`ui-applications`), the knowledge base,
  existing UI tests and Page Objects.

## Output

`UiCase.md`, following [`ui-case-template.md`](../stand-test-ui-case-intake/ui-case-template.md).

That template is also the form to hand a **manual tester** who will write the next case: it is the
input shape this branch can consume without a round of questions. Worked example:
[`example-ui-case.md`](../stand-test-ui-case-intake/example-ui-case.md).

## What to extract (in order)

1. **Application** — the business name of the system, and the registry alias it maps to. Read the
   environment's `ui-applications` section; if no alias exists for it, that is blocking (a registry
   addition is a human decision, exactly as for a service alias). Never a URL: if the case gives one,
   record it as *evidence of which application is meant* and resolve it to an alias — the address
   itself must not survive into any later artifact.
2. **Environment** — a DEV/IFT registry key. A case naming a production stand is refused here, at the
   first stage, not at the safety gate.
3. **Role and account** — which role the flow is performed as. The application's `auth.roles` is the
   closed list; "any user" is not expressible once roles are declared, and "the tester's own account"
   is never the answer. If the case does not say, and the flow is behind a sign-in, ask — the role
   changes what the screen shows and is therefore part of the case's meaning.
4. **Entry point** — the screen the flow starts on, as a *relative path* plus what the case calls the
   screen. If the case starts "from the main page and then three clicks", write the three clicks;
   a shortcut path is a discovery finding, not an intake assumption.
5. **The user path** — one numbered step per user action, each in the vocabulary of the person doing
   it ("enter the amount", "press Confirm"), not in the vocabulary of the DOM. Locators do not exist
   yet. Mark every step that is **irreversible** (§5 of the UI guardrails) — the mark decides what
   discovery may and may not do at stage 3.
6. **Observable expectations** — what the *screen* must show, and where. Each expectation needs the
   element it is about (in human terms), the exact expected text or state, and whether it appears
   immediately or after a wait. "The application is created" is not an expectation; "the status field
   shows «Принята» within 20 seconds" is.
7. **Backend effects, if the case has them** — a UI case may also assert a REST/Kafka/DB/gRPC effect.
   Record them here in the protocol vocabulary (see `stand-test-case-analysis` for the shape), and
   note **how the two halves will be joined**: an id read off the screen and captured, or the
   SDK-owned correlation id, or nothing (see stage 4's binding decision). This is the single most
   commonly omitted part of a UI case and the one BR-13 exists about.
8. **Test data and ownership** — which entities the flow creates, which it merely reads, and which it
   needs to exist beforehand. Every value the run must make unique is flagged here; the design turns
   the flag into `${testRunId}`. A concrete client/account/contract id copied from the case is an
   *entity-instance handle*, never a constant — the same rule as the protocol branch, and it is
   blocking unless the entity can be provisioned in-run or verified by a read-probe.
9. **Residual effects and cleanup** — what survives the run. The SDK has no UI-side compensation:
   what the browser created stays unless a backend cleanup step removes it. Say so explicitly; an
   empty cleanup section is a decision, and it must be a visible one.
10. **Negative paths** — validation messages, forbidden transitions, a role that must *not* see a
    screen. These are the cases the UI branch is best at and the ones most often dropped.
11. **Timeouts** — every "within a minute", "instantly", "after the page reloads" turned into a
    number of seconds. A missing SLA is a safe assumption (pick the smallest realistic bound and
    record it), not a question — but an unrecorded one is a defect.

## Rules

- **No browsing.** Not one navigation. If the case is unintelligible without seeing the screen, say
  which screen and why, and let stage 3 answer it — with the discovery account, under the discovery
  rules.
- **No locators, no `data-testid`, no CSS.** They do not exist until stage 3 observes them, and a
  locator written here is an invented locator wearing an early timestamp.
- **No code, no Page Objects, no step ids.**
- **Quote, do not summarise.** Expected texts are copied character for character, including case,
  punctuation, «ёлочки» and non-breaking spaces — a UI assertion is an exact-string comparison, and a
  silently normalised quote is a test that fails on its first run.
- **Prefer a recorded assumption to a question**, and make every assumption visible. Escalate what
  changes the test's meaning: which application, which role, an expected value the screen does not
  display, whether an action is reversible, whether a precondition entity can be created.

## Checklist before handing off

- [ ] Application resolved to a registry alias (or the missing alias recorded as blocking).
- [ ] Environment is a DEV/IFT key; production explicitly ruled out.
- [ ] Role named and present in the application's `auth.roles` (or blocking).
- [ ] Every user step is a human action; every irreversible one is marked.
- [ ] Every expectation names its element, its exact expected value, and immediate vs. awaited.
- [ ] Backend effects, if any, carry the intended UI↔backend binding.
- [ ] Data ownership recorded; every entity-instance handle classified (provisioned / read-probed /
      blocking) — never copied as a literal.
- [ ] Residual effects stated, including "nothing is cleaned up" when that is the truth.
- [ ] Every wait has a number.
- [ ] Assumptions and blocking questions listed separately.

## Next stage

[`stand-test-ui-completeness-check`](../stand-test-ui-completeness-check/SKILL.md) — the gate that
decides whether this case can be built at all without a human answer.
