---
name: stand-test-ui-completeness-check
description: The completeness gate of the UI branch — classify every gap in a structured UI case into answerable-by-discovery, answerable-from-the-knowledge-base, safe-assumption or blocking-question, and stop the run when a blocking one remains. Runs between UI case intake and UI discovery; produces a decision, never code.
version: 1
---

# Skill: stand-test-ui-completeness-check

Stage 2 of the UI branch, and the gate that decides whether the run continues. Its whole job is to
tell four kinds of gap apart, because treating them alike is how a UI generation goes wrong in both
directions: asking a human what the screen could have answered wastes the one resource this pipeline
is meant to save, and inventing what only a human knows produces a test that proves nothing.

## When to use

Immediately after [`stand-test-ui-case-intake`](../stand-test-ui-case-intake/SKILL.md), on the
`UiCase.md` it produced. Re-run it after a human answers, before proceeding.

## Input

- `UiCase.md`.
- The environment registry (`ui-applications` for the environment named in the case).
- The knowledge base, if the project keeps one — UI screens/elements/flows if curated, plus the
  service/endpoint/topic/table entries any backend expectation needs.

## Output

`UiCaseCompleteness.md` — the four classified lists, the verdict, and (when blocking) the exact
questions, following
[`ui-completeness-checklist.md`](../stand-test-ui-completeness-check/ui-completeness-checklist.md).

Verdict is one of:

| Verdict | Meaning | What happens next |
|---|---|---|
| `READY` | no gap needs a human; the deferred ones are all DOM facts | stage 3 (discovery) |
| `READY-WITH-ASSUMPTIONS` | same, plus recorded assumptions the human will see in the report | stage 3 |
| `BLOCKED` | at least one gap changes the test's meaning | **stop**; ask, do not proceed |

## The four classes

Take every unknown in the case and put it in exactly one.

### A. Answerable by discovery — defer, do not ask

Anything that exists in the running application's DOM: whether an element carries a `data-testid`,
its accessible name, its label text, the exact wording of a validation message, whether a control is
disabled until a field is filled, which screen a link leads to, the relative path of a screen, in
what order fields appear.

These are *not* questions and *not* assumptions. They are work for stage 3. A case that names no
locators is complete; a case that names no *screens* is not.

### B. Answerable from the knowledge base — resolve now

The application alias and its registry properties (`auth.roles`, `auth.scheme`, `challenge`,
`discovery-account-ref`, viewport profiles); any REST/Kafka/DB/gRPC contract a backend expectation
needs. Resolve them here so stage 3 knows which application it may open and under which account, and
so a missing contract surfaces before a browser is started rather than after.

A KB miss for a **backend** contract is blocking exactly as in the protocol branch — no entry means
`missing`, never a guess.

### C. Safe assumption — record and proceed

A gap with one obviously correct answer whose being wrong would be visible immediately: a wait bound
the case did not state (take the smallest realistic one), uniqueness of a value the case calls an
external identifier, using a regular expression for a system-generated number, the default viewport
profile of the application. Every assumption goes into the report; a silent assumption is a defect
even when its content is right.

### D. Blocking question — stop

Escalate only what changes what the test *means*:

- **which application or which screen** the case is about, when the text admits two readings;
- **the role**, when the flow is behind a sign-in and the case does not name one (the role decides
  what is on the screen — a wrong guess produces a test that passes against the wrong page);
- **an expected value the screen does not display** — the case says "the correct amount is shown"
  without saying which;
- **whether an action is irreversible**, when the case did not mark it and the label is ambiguous
  (Send? Submit? Confirm?). Discovery may not click it to find out; that is precisely the point;
- **a precondition entity** that can neither be created in-run through a curated create-endpoint nor
  verified by a read-probe — the entity-instance-handle rule, unchanged from the protocol branch;
- **write/irreversible permission** on the stand, when the flow creates or changes business data and
  nobody has said the stand is for that;
- **a missing registry alias** or a missing `discovery-account-ref` for an application behind a
  sign-in — both are human-approved configuration, never improvised;
- **a declared `challenge` (MFA/OTP/CAPTCHA)** with no `UiLoginChallengeHandler` on the classpath and
  no `STORAGE_STATE` session prepared — external gate G-1; there is no bypass to design.

## Rules

- **A gap of class A is never escalated.** "The case does not say what the button is called" is not
  a question — it is stage 3's first task.
- **A gap of class D is never assumed.** Not "probably `client`", not "presumably 30 seconds is
  fine", not "the button is most likely reversible". If it changes the meaning, it goes to the human.
- **One question, one decision.** Each blocking item states what is unknown, what it blocks, and what
  the possible answers change — a question a busy human can answer in one line.
- **The gate stops the run.** `BLOCKED` means no discovery, no design, no code. Producing "a draft
  while we wait" is how an unanswered question becomes an invented fact three stages later.
- **Re-run after answers.** A human answer can create new gaps (a named role may turn out to be
  undeclared in the registry); the gate is cheap and re-running it is the point.

## Checklist before handing off

- [ ] Every unknown in `UiCase.md` appears in exactly one of the four lists.
- [ ] No DOM fact is in the blocking list; no meaning-changing fact is in the assumptions list.
- [ ] The application alias is resolved in the environment named by the case, and the environment is
      a DEV/IFT key.
- [ ] The role is declared in the application's `auth.roles`, or the flow needs no sign-in.
- [ ] `discovery-account-ref` exists if anything behind the sign-in must be explored.
- [ ] `challenge` is `NONE`, or a handler/`STORAGE_STATE` path is confirmed.
- [ ] Every backend expectation resolves to a KB entry or is blocking.
- [ ] Verdict recorded, with the question list when it is `BLOCKED`.

## Next stage

[`stand-test-ui-discovery`](../stand-test-ui-discovery/SKILL.md) — but only on `READY` /
`READY-WITH-ASSUMPTIONS`.
