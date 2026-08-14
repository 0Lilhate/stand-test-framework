---
description: 'UI business case → reviewed technical design: intake, completeness gate, live DEV/IFT discovery, scenario design and the Page Object map. No code generated; the human answers blocking questions and approves registry additions.'
version: 1
---

# /stand-test-ui-design — UI case → scenario design + Page Object map

Stages 1–5 of the UI branch. **No test code is produced here** — the output is the design an
authoring workflow transcribes.

## Input

A UI business case (ticket / manual regression steps / free text; ideally written to
[`ui-case-template.md`](../skills/stand-test-ui-case-intake/ui-case-template.md)), plus the consumer
project: the environment registry, the knowledge base, existing Page Objects, and a
browser-automation channel for stage 3.

## Output

`UiCase.md`, `UiCaseCompleteness.md`, `UiDiscoveryReport.md`, `UiScenarioDesign.md`, and the Page
Object classes (stage 5 does write Java — Page Objects are structure, not test logic, and the design
is not checkable until the locators have a home).

## Steps

1. **Intake** — [`stand-test-ui-case-intake`](../skills/stand-test-ui-case-intake/SKILL.md).
   Resolve the application to a registry alias; refuse a production stand here rather than at the
   safety gate; extract the user path with every irreversible step marked; quote every expected text
   character for character. No browsing, no locators.
2. **Completeness gate** —
   [`stand-test-ui-completeness-check`](../skills/stand-test-ui-completeness-check/SKILL.md).
   Classify every gap: deferred to discovery / resolved from the registry-KB / safe assumption /
   **blocking question**. A DOM fact is never a question; a meaning-changing fact is never an
   assumption. `BLOCKED` stops the workflow — do not open a browser "while we wait".
3. **Discovery** — [`stand-test-ui-discovery`](../skills/stand-test-ui-discovery/SKILL.md).
   Live DEV/IFT, alias only, discovery account only, **no irreversible action, no dialogs, no
   writes**. Record every rung that exists for every element, not only the one you intend to use —
   the next generation on this screen reads the report instead of browsing again. Record what you
   could not see and why.
4. **Scenario design** —
   [`stand-test-ui-scenario-design`](../skills/stand-test-ui-scenario-design/SKILL.md).
   Track is Java (there is no declarative UI format). `ui.login` first with an explicit role; actions
   carry no assertions or captures; every expect step carries at least one; every await bounded;
   `${testRunId}` scoping; the UI↔backend binding named; the residual-data verdict written; the
   parallelism budget computed against the account-pool size.
5. **Page Objects** —
   [`stand-test-ui-page-object-design`](../skills/stand-test-ui-page-object-design/SKILL.md).
   One class per screen, `final`, no state, every locator a `private static final` constant, factories
   parameterised by data and never by element. Reuse an existing class before creating one.

## Mandatory checks

- [ ] Environment is a DEV/IFT registry key; the alias is whitelisted in it.
- [ ] The role is declared in the application's `auth.roles`; `auth.scheme` is `FORM` or
      `STORAGE_STATE`; `challenge` is `NONE` or has a handler / prepared session.
- [ ] Discovery header records the discovery account, the viewport and the observation date.
- [ ] Every design locator cites a discovery-report row; every fragile one is flagged.
- [ ] Every `${var}` consumed is produced earlier or is a built-in.
- [ ] No `${…}` planned for a `ui.open` path or an assertion's expected value.
- [ ] Every await has an explicit bounded timeout.
- [ ] Page Objects compile and pass checkstyle.

## Human approval points (blocking)

1. **Blocking questions** from stage 2.
2. **Registry additions** — a missing `ui-applications` alias, sign-in locators discovered at stage 3,
   a `discovery-account-ref` that does not exist yet. Discovery *proposes*; a human applies.
3. **The design itself** when the flow performs an irreversible business action, so that permission
   is given before any code exists.

## Next

[`/stand-test-ui-java`](stand-test-ui-java.md).
