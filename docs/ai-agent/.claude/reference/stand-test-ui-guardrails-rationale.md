---
version: 1
---

# Reference: why the UI guardrails are what they are

This is **not** a rule. The rule is [`../rules/stand-test-ui-guardrails.md`](../rules/stand-test-ui-guardrails.md):
it says what may and may not be produced, and it is loaded into every session automatically. This
file carries the arguments behind it — what each constraint is defending against, what enforces it
and what merely appears to, and where the SDK's behaviour differs from the obvious reading.

Not auto-loaded. Read it before CHANGING a rule, or when one looks excessive; following a rule needs
only the rule. Numbering follows the rule's sections.

---

## The asymmetry, in full

The protocol branch resolves a contract from a document: an OpenAPI file names the path, the fields
and the status codes, and a case can be authored without touching the system. **A screen has no such
document.** No specification carries a `data-testid`, an accessible name or the exact wording of a
validation message; they exist only in the DOM of a running application, and they change with the
markup rather than with the contract.

That single fact produces everything the protocol branch has no equivalent of: a discovery stage, a
discovery account, a rule against irreversible actions during reconnaissance, a report whose rows are
the source every locator must trace to, and a third rung in the source-of-truth order.

It also produces the branch's worst failure mode. **A fabricated locator is syntactically
indistinguishable from a real one.** `UiLocator.testId("requestStatus")` compiles exactly like the
real `"request-status"`, passes review by eye, and fails at run time in the shape of application
drift — so the team spends the debugging on the application before suspecting the test. Nothing else
this branch can produce is that expensive, which is why U1 is the gate the whole discovery stage
exists to feed, and why the case text is not a source of a locator at all: a case contains no
`data-testid`, and the intake form deliberately forbids one from being written there.

## §3 — why production gets three doors rather than one

A browser is the least discriminating client in this SDK. A REST step cannot reach an address the
registry does not resolve; a browser opens whatever string it is handed, renders it, and signs in.
The environment key, the `ui-applications` whitelist and the account pool are therefore three
independent doors, each of which must be shut, and "it was read-only" remediates none of them: a
read-only session against production still leases an account, still leaves traces in the
application's audit log, and still puts a production screenshot into a report.

## §4 — what actually enforces the discovery-account separation

This is the rule with the largest gap between how it reads and what holds it up, and the gap is worth
knowing exactly, because it decides how much weight the rule can carry.

The registry loader does **not** check the separation, and cannot: the roster of accounts lives in
the environment variable named by `credentials-pool-ref`, so a configuration whose
`discovery-account-ref` names a member of that pool loads without a word of complaint. The SDK
refuses it only when a `ui.login` step parses the roster — `AccountRoster` compares the ref against
every entry's account id and username variable.

Discovery, however, goes through the automation channel and never executes `ui.login`. **So at stage
3 nothing machine-checks this at all.** The rule rests on the agent, and the discovery report's
header is the only surviving record that it held. That is why the rule states the gap rather than
implying enforcement: a reviewer who believes the loader checks it will not look at the header, and
the header is the only place to look.

## §5 — why irreversibility is scoped rather than banned

A UI test that never clicks anything proves nothing: the whole point of the branch is that a business
flow is exercised the way a user exercises it. So the ban is placed where the cost is
asymmetric — during *reconnaissance*, where an action buys nothing that reading the screen does not,
and where the account doing it is the restricted one.

Inside an authored scenario the boundary is the same one that governs `db.write`: the test may act on
what it created, never on a row it found. "The screen offered the button" is not authorisation,
because the screen offers buttons for the whole role, not for the test.

The residual-data section of the report is not bookkeeping either. The SDK has **no UI-side
compensation mechanism**: the run's undo-log reaches `db.write` and nothing else, so a created
application or an uploaded document survives the run. Declaring it is the only mitigation that
exists.

## §7 — why rung 4 is called fragile

`UiLocator.fragile()` has a single definition in the SDK, and it returns `false` for `TEST_ID` alone.
A `[data-qa='submit']` attribute selector is more durable than a class chain and less durable than a
test id, and the temptation is to describe it as "stable enough". The report must not: the whole
point of the fragile list is that it is the input to the `data-testid` escalation (BRD D-5), and a
locator described as stable never reaches that conversation.

KPI-9 is counted separately, statically, over merged Page Objects. The report's own fragile-locator
list is a self-assessment of one generation and must never be presented as the metric — one is what
the author believed, the other is what the repository contains.

## §8 — the XPath ban is enforced by having nowhere to put one

There is no `LocatorStrategy` constant, no `UiLocator` factory and no registry spelling for XPath.
That is the cheapest possible ban: an agent cannot write one without writing something that does not
compile. The write hook's `XPATH_LOCATOR` detector exists for the spellings that compile anyway —
a string built at run time, a Playwright call reached through the driver — and it reads the projection
of the file *without comments*, so a commented-out or string-assembled spelling still needs a human.

The kit's own reference corpus deliberately carries no XPath: a clean hook run over the canonical UI
artefacts is the negative case, and a corpus that tripped its own detector would train everyone to
ignore it.

## §9 — why the browser needs the sleep ban restated

The protocol branch bans `Thread.sleep`, and a UI author who has internalised that still reaches for
`page.waitForSelector` — it is not a sleep, it is what the driver's own documentation recommends, and
it is a wait the SDK cannot see, bound by nothing the scenario declares and reported in no
diagnostics. A UI test that "passes locally and flakes on CI" is nearly always a missing
`expectEventually`.

## §10 and §11 — why compile-cleanliness and LLM-independence are rules rather than expectations

"It will be fixed on review" is how an unrunnable test reaches a branch: the reviewer reads it as a
draft, the author has moved on, and the artifact sits there compiling in nobody's build.

The LLM rule protects a measurement as much as a run. A test whose locator is resolved "by
description" at run time behaves differently depending on a model's availability and mood — so a
flaky-rate measured over such tests measures the model, not the application, and the KPI the whole
line is built on stops meaning anything.

## §12 — Page Objects are the only mitigation against markup drift

One screen changes, one file changes. That is the entire argument, and it is also what makes the
static KPI-9 count possible: the count reads merged Page Objects, so a locator in a test body is
invisible to it twice over — it does not survive drift and it does not appear in the metric.
`UI_LOCATOR_OUTSIDE_PAGES` reads source, not intent: a locator smuggled through a constant in a
neighbouring file still needs the human.

## §13 — what `asSensitive()` does not cover

The mark keeps a value out of assertion messages and await diagnostics, and it paints the field over
in the failure screenshot *before* the grab. What it does not reach is the **Playwright trace**: a
trace records the parameters of the actions it saw, and a `fill`'s parameter is the typed value.
Neither the screenshot mask, nor the exception-message sanitiser, nor the report's masker (a text
channel; a trace is a ZIP) touches it — which is why `ui.login` brackets its credential fills with
`suspendTracing()`/`resumeTracing()`, so that no recording chunk is open while a password is typed.

The saved session file is the same category of secret for the same reason: it is a live
authentication, and a file that is attached once is a file that lives in the report's storage.

## §14 — sign-in, the pool, and the relaxation that was accepted knowingly

A hand-written sign-in costs three things at once: the credential lands in the scenario, the account
pool is bypassed (two parallel runs then share one account and log each other out), and the saved
session is never reused, so every run pays the full form.

The registry's two shapes are not equivalent in exposure, and the difference is deliberate. The
roster behind `credentials-pool-ref` could never express a credential VALUE — it holds account ids,
roles and the NAMES of variables. The direct `credentials-username`/`credentials-password` pair
(format version 4) also accepts the `${var:value}` spelling, where the part after the colon is a
value and not the name of a fallback variable. That makes a credential expressible in a file for the
first time — a knowing relaxation of ADR-UI-006 §5, accepted by the line owner, which is why "never
give a default to a password" had to become a written rule instead of remaining a property of the
construction.

`SECRET_IN_SOURCE` was extended to catch it. The original pattern required quotes on both sides,
which is a JSON-ish shape, and a registry is written in block YAML without a single quote — so the
one document where this could now happen was the one document the detector could not read. The
exemptions were derived by scanning the whole repository rather than guessed: the single false
positive was `password-locator` (a locator for the field, not a credential), and it is exempted by
name.

There is no MFA/OTP/CAPTCHA bypass and there will not be one — external gate G-1. An application that
declares a `challenge` either has a handler on the test classpath or is refused with a message naming
the gate and `STORAGE_STATE` as the alternative. "Temporarily" disabling a factor on a stand is the
kind of change that outlives the test that asked for it.

## §15 — why the preserved snapshot is not optional

KPI-4 measures how much of a generated artifact a human had to change. Without a copy of the
generation as first emitted, the measurement has no diff base: it is not imprecise, it is
unobservable, and BRD says so outright. The snapshot costs a file copy and a hash; the metric it
enables is the only evidence that the generator is improving.

## Machine coverage: why partial, and why that must be said out loud

Eight UI detectors close what a regular expression over source can decide. What no expression can
decide is whether a field holds personal data, whether an assertion's expected value came from the
screen or from the case, and whether a report's sections contain anything — the detector counts the
headings, not the content. Those, plus U8, U10, U11a/b, U12, U14, U15, U18 and U19, are closed by the
stage-7 subagent and by a human.

The split is pinned in BOTH directions — `UiHumanGateCensusTest` and `UiMachineGateCensusTest` — so a
gate cannot be counted as machine-covered in one document and eye-only in another, which is exactly
how the previous version of the coverage table went stale. The rule's sentence "a clean hook run is
still not a clean UI review" is transported letter for letter into the readiness report for the same
reason: a retelling stays readable while quietly becoming false.
