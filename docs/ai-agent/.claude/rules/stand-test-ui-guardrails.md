---
version: 1
---

# Rules: UI autotest generation guardrails

Non-negotiable rules for ANY agent work that creates or modifies **UI** autotests on
`stand-test-ui`. This file **adds to** [`stand-test-guardrails.md`](stand-test-guardrails.md) and
never relaxes it: every protocol rule (aliases only, no secrets, no sleeps, bounded timeouts,
SDK-owned `testRunId`/`correlationId`, no pipeline bypass, no production environments, human merges)
holds for a UI test exactly as written there. What follows is what a browser adds on top.

The stage order of the UI branch lives in [`stand-test-pipeline.md`](stand-test-pipeline.md);
detection patterns live in
[`../skills/stand-test-ui-safety-review/ui-safety-checklist.md`](../skills/stand-test-ui-safety-review/ui-safety-checklist.md).

## The one asymmetry that shapes everything else

A REST contract can be read from a specification. **A screen cannot.** The DOM of a running
application is the only place where a `data-testid`, an accessible name or a label text actually
exists, and no OpenAPI document will ever carry them. That is why the UI branch has a *discovery*
stage that the protocol branch has no equivalent of — and why the source-of-truth order below has
three rungs where the protocol branch has two.

**Source of truth, in order:**

1. **The knowledge base** — screens, elements and flows already curated. Costs nothing, drifts
   silently; a KB element that discovery contradicts is a KB defect, and the live DOM wins.
2. **The live DEV/IFT UI** — authoritative for everything about the DOM: which elements exist, what
   their `data-testid`/role/accessible name/label/text are, which of them are visible or enabled,
   which screen a click leads to. Quote it; never paraphrase it from memory.
3. **A question to the human** — for what neither the KB nor the running application can answer:
   business intent, expected values that the screen does not display, permissions, which of two
   plausible flows the case means.

"Not in the KB" is therefore **not** a reason to ask, and never a licence to invent: it is a reason
to go and look. "Not in the KB *and* not on the screen" is a question. **An invented locator is the
single worst artifact this branch can produce** — it compiles, it passes review by eye, and it fails
at run time in a way that looks exactly like application drift.

## Hard constraints (violations BLOCK, never work around)

**Machine coverage is now partial, not zero** — the "every UI gate is eye-only" claim is obsolete:
- XPath anywhere → `XPATH_LOCATOR`
- a locator outside `**/ui/pages/**` → `UI_LOCATOR_OUTSIDE_PAGES`
- a locator that does not trace to `UiDiscoveryReport.md` → `UI_DISCOVERY_PARITY`
- `UiStep.login(` without `.role(...)` → `UI_LOGIN_WITHOUT_ROLE`
- driver-level waits (`page.waitForSelector`/`waitForTimeout`/`waitForLoadState`/`.waitFor`) → `THREAD_SLEEP`
- `expectEventually` without `within(...)` → `EXPECT_EVENTUALLY_WITHOUT_WITHIN`
- `${…}` in a `ui.open` path or an assertion's expected value → `UI_OPEN_OR_ASSERT_TEMPLATE`
- an address in a `Ui*Report.md` → `UI_REPORT_STAND_ADDRESS`
- a generation report missing a section, or naming a snapshot that is not on disk → `UI_GENERATION_REPORT_INCOMPLETE`
- a static mutable field in a test class or Page Object → `SHARED_MUTABLE_TEST_STATE`

The rest stay human: U4, U8, U10, U11a/b, U12, U14, U15, U18, U19 — enumerated in
[`ui-safety-checklist.md`](../skills/stand-test-ui-safety-review/ui-safety-checklist.md) and in the
`stand-test-ai-schema` test. A clean hook run is still not a clean UI review.

### 1. The live DEV/IFT UI is the source of truth for the DOM

Every **locator** and every **screen transition** in a generated artifact traces to **a row of the
discovery report**. A curated KB element is the hypothesis discovery goes to confirm, never a
substitute for it; the case text is not a source of a locator at all, because a case contains no
`data-testid` and the intake form forbids one from being written there. A locator with no row in
`UiDiscoveryReport.md` is a BLOCK finding, whatever it looks like — that is safety gate U1, and it is
the single rule this branch exists to enforce.

Every **asserted text** traces to the discovery report's *Texts observed* table, where what the screen
displays and what the case states sit side by side. The case is the authority on what *should* be
there and the screen on what *is*; where the two differ, that is a finding for the human, never a
value to average out and never a licence to assert the case's wording against a screen that shows
something else.

Record what you observed, not what you concluded: the attribute as spelled, the accessible name as
read, the label verbatim — including its language and its non-breaking spaces.

### 2. Applications are addressed by registry alias only

`UiStep.open("client-portal", "/applications/new")`. There is **no API that takes a URL** — the
builder rejects an absolute `path` at construction, and the validator refuses an alias that the
environment's `ui-applications` section does not whitelist
(`ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION`), before a browser is ever started. Do not
invent a parameter for a host, a port or a full address; there is nowhere to put one, which is the
ban. The base URL exists in exactly one place at run time — the registry's `base-url-ref` — and a
generated artifact never contains an address.

### 3. Production is forbidden — on every one of the three ways in

A UI run is the easiest place in this SDK to hit production by accident, because a browser will
cheerfully open whatever it is given. All three doors are shut:

- the scenario's `environment` must be a DEV/IFT registry key — never a production stand;
- the `ui-applications` alias must be whitelisted in **that** environment;
- the account leased by `ui.login` comes from that environment's pool.

A registry addition that names a production stand, an alias whose `base-url-ref` points at one, or
a discovery session opened against one — each is a BLOCK finding, and none of them is remediable by
"it was read-only".

### 4. Discovery runs under the restricted discovery account

`auth.discovery-account-ref` (SEC-10) names the reconnaissance account, kept apart from the working
pool so exploration cannot pick up an account able to perform the actions §5 forbids. Be precise
about what enforces that separation, because the difference decides how much this rule can be
trusted: the registry loader does **not** check it and cannot — the roster lives in the environment
variable named by `credentials-pool-ref`, so a configuration whose `discovery-account-ref` names a
pool member loads without complaint. The SDK refuses it only when a `ui.login` step parses that
roster (`AccountRoster`, comparing the ref against every entry's account id and username variable).
Discovery goes through the automation channel and never executes `ui.login`, so **at stage 3 nothing
machine-checks this** — the rule rests on the agent, and the discovery report's header is the only
record that it held. Discovery never leases a pool
account, never signs in as a privileged role "just to see the screen", and never uses a personal
account of the engineer running it.

If no `discovery-account-ref` is declared for the application, discovery of anything behind the
sign-in is **blocked** — that is a registry gap for a human to close, not a reason to borrow a pool
account.

### 5. Irreversible actions are forbidden

Scoped precisely, because a UI test that never clicks anything proves nothing:

- **During discovery (stage 3): unconditionally.** Do not click Delete, Confirm, Pay, Send, Approve,
  Revoke, Cancel-the-contract, or any control whose label or confirmation dialog says a thing will
  happen. Read the screen, open the form, note the locators — and stop at the last control before
  the effect. If the flow cannot be mapped without triggering it, record the gap and ask.
- **In an authored scenario: only what the case asks for, and only on data the test owns.** A test
  may submit the form it filled in and may act on the entity it created. It must **never** mutate or
  destroy a row it did not create — the same boundary rule that governs `db.write`. "The screen
  offered the button" is not authorisation.
- **Never at all:** anything outside the case's own flow — bulk operations, administrative screens
  the case does not name, settings that outlive the run, another user's data.

Residual effects that survive the run (a created application, an uploaded document, a changed
status) are declared in the generation report under *what is not covered / residual data*, with the
compensation if one exists and an honest note if none does. The SDK has **no UI-side compensation
mechanism** in this version: a browser action is not undone by the run's undo-log, which reaches
only `db.write`.

### 6. Missing facts are never invented

No invented `data-testid`, no plausible-looking accessible name, no "the button is probably called
Submit", no guessed URL path, no assumed validation message. Every such value is observed, curated
or asked about. This is the protocol branch's *no invented contracts* rule, and the UI branch is
where it is easiest to break, because a fabricated locator is syntactically indistinguishable from a
real one.

Prefer a recorded assumption to a question where the assumption is safe and visible; escalate what
changes the test's meaning (which screen, which role, which expected value, whether an action is
reversible).

### 7. Locator priority — and the honest mapping onto the SDK's five strategies

Choose the highest rung the screen actually supports:

| # | Rung | SDK spelling | Fragile? |
|---|---|---|---|
| 1 | `data-testid` | `UiLocator.testId("application-status")` | **no** — the only non-fragile strategy |
| 2 | role + accessible name | `UiLocator.role("button", "Подтвердить")` | yes |
| 3 | label | `UiLocator.label("Сумма")` | yes |
| 4 | other stable attribute | `UiLocator.css("[data-qa='submit']")` — **there is no attribute strategy**; an attribute selector is a CSS selector, and the SDK counts it as CSS | yes |
| 5 | visible text | `UiLocator.text("Заявка принята")` | yes |
| 6 | CSS | `UiLocator.css(".form__submit")` — last resort | yes |

Two consequences follow, and both must reach the report rather than be smoothed over:

- **Rung 4 is not a cheaper rung 1.** `css=[data-qa=…]` is more durable than a class chain and less
  durable than a test id, but `UiLocator.fragile()` — the SDK's single definition, `false` only for
  `TEST_ID` — calls it fragile, and so does the report. Do not describe it as stable.
- **The fragile-locator section of the report is not optional.** Every locator above rung 1 is
  listed with its rung and why the higher rung was unavailable. That list is the input to the
  `data-testid` escalation (BRD D-5); KPI-9 is counted separately and statically over merged Page
  Objects, and the report's self-assessment must never be presented as KPI-9.

### 8. XPath cannot be written; long CSS must be marked fragile

There is no XPath in `stand-test-ui` — no `LocatorStrategy` constant, no `UiLocator` factory, no
`xpath=` spelling in the registry grammar. The ban is enforced by having nowhere to put one. An agent
that finds itself wanting XPath has reached the end of a rung and must either go back up (ask the
team for a `data-testid`) or drop to a CSS selector and declare it.

Machine enforcement of the XPath ban lives in the kit's write hook: XPath signatures (`xpath=`,
`//*[`, `by.xpath`, `.xpath(`) over the projection *without comments* are caught by detector
`XPATH_LOCATOR` (BLOCK, appliesTo java). The kit's own reference corpus must stay mute — the canonical
UI artefacts carry no XPath, so a clean hook run over them exercises the negative case. The detector
over the projection without comments does not replace human reading of commented-out or string-built
spellings.

A CSS selector is **long/brittle**, and must be listed as such in the report when it carries any of:
a descendant chain of three or more steps, `nth-child`/`nth-of-type`, a generated/hashed class name
(`.css-1x2y3z`, `.MuiBox-root`), a tag-only step (`div > span`), a sibling combinator (`+`, `~`), or a
positional index of any kind. The list names the cases seen so far and is **not** closed: a selector
that is durable only because the markup happens not to have changed yet is brittle whether or not it
matches an entry — say so rather than reading the enumeration as an exhaustive test (the safety gate's
U18 applies the same predicate). A short attribute selector (`[data-qa='submit']`) is fragile but not
brittle — say which it is.

### 9. No `Thread.sleep` — and no browser-level equivalent

The protocol rule, restated because the browser offers new ways to break it: no `Thread.sleep`, no
`Awaitility`, no manual retry loop, **and** no driver-level wait of any kind — the adapter exposes
no `waitFor`, no `waitForTimeout`, no `waitForSelector`, and none may be reached for. Every wait in a
UI scenario is `ui.expectEventually` with an explicit bounded `within(...)`, driven by the SDK's
`Awaiter`. `ui.login` has its own bounded waits (`within(...)` for the sign-in, `accountTimeout(...)`
for a free account); a single click or fill is bounded by
`stand.test.ui.action.timeout.millis`, which is configuration and not a scenario field.

A UI test that "passes locally and flakes on CI" is nearly always a missing `expectEventually` —
the rendering is asynchronous whether or not the case says so.

Machine enforcement extends `THREAD_SLEEP` (still BLOCK) with the driver-side vocabulary the browser
tempts an agent into: `page.waitForSelector(`, `page.waitForTimeout(`, `page.waitForLoadState(` and
`.waitFor(` are now caught over Java without comments, on the same footing as `Thread.sleep`.
`expectEventually` without a bounded `within(...)`/`withinSeconds(...)` before `.build()` is a separate
machine finding — `EXPECT_EVENTUALLY_WITHOUT_WITHIN` (HIGH).

### 10. The Java test and its Page Objects pass the linter

The generated artifacts compile and pass checkstyle in the consumer project
(`./gradlew compileTestJava checkstyleTest`). In an SDK-style repository that specifically means:
AssertJ only (`org.junit.jupiter.api.Assertions` and JUnit 4 `org.junit.Test` are banned imports),
no `System.out`/`System.err`, no non-JetBrains `@NotNull`/`@Nullable`, one statement per line, a
blank line between members, Java-17-compatible sources. A generated test that does not compile is
not a deliverable, and "it will be fixed on review" is how an unrunnable test reaches a branch.

### 11. The test must not depend on an LLM at run time

The artifact CI executes is plain Java. No model call, no agent invocation, no network call to any
assistant, no locator resolved dynamically "by description" at run time, no prompt text embedded in
the test, no self-healing selector library. The agent's involvement ends when the file is written;
everything a run needs is in the repository. A test that would behave differently depending on a
model's availability is not reproducible, and a flaky-rate measurement over such tests measures
nothing.

### 12. Locators live in Page Objects, never in the test body

A `UiLocator` constant belongs to a Page Object class; the test body reads as business steps. This
is the SDK's only mitigation against markup drift — one screen changes, one file changes — and it is
also what makes the static KPI-9 count possible at all, since that count reads merged Page Objects.
A `UiLocator.*` call inside a test method is a finding even when it works. Machine enforcement:
`UiLocator` appearing in a Java file that does not declare itself a Page Object (no
`package … ui.pages;` / `…ui.pageobject` line) is caught by detector `UI_LOCATOR_OUTSIDE_PAGES`
(BLOCK, appliesTo java). The detector reads source, not intent — a locator smuggled through a constant
in a neighbour file still needs the human.

### 13. Sensitive fields are marked `asSensitive()`

Any locator addressing a password, a token, a one-time code, or a field holding personal data is
marked `UiLocator.label("Пароль").asSensitive()`. The mark is what keeps the value out of assertion
failure messages and await diagnostics — that is, out of the report, the log and the CI output. It
costs one call and it is the difference between a failed assertion and a credential in a build
artifact. The saved browser session file (`STORAGE_STATE`) is likewise a secret: never attached,
never logged, never printed — only its path.

### 14. Sign-in is `ui.login`, by role, from the pool

Never a hand-written sequence of `ui.fill` + `ui.click` against the login form: that route puts the
credential in the scenario, bypasses the account pool (so two parallel runs share one account), and
skips the session reuse the scheme provides. `UiStep.login("<alias>").role("<role>")` — the role is
**mandatory** once the application declares roles, and the validator refuses an undeclared one
pre-flight (`UI_LOGIN_ROLE_REQUIRED` / `UI_LOGIN_ROLE_UNKNOWN`). Place it **before** the first
`ui.open` of that application; a later sign-in can no longer restore a saved session and says so.
`UiStep.login(` without a `.role(...)` before `.build()` is caught by detector `UI_LOGIN_WITHOUT_ROLE`
(BLOCK).

There is **no MFA/OTP/CAPTCHA bypass** and there will not be one (external gate G-1). An application
declaring a `challenge` either has a `UiLoginChallengeHandler` on the test classpath or is refused
with a message naming the gate and `STORAGE_STATE` as the alternative. Do not design around it; do
not "temporarily" disable a factor; record it as blocking.

### 15. The result carries a generation report, and the original generation is preserved

No UI generation is complete without
[`ui-generation-report-template.md`](../skills/stand-test-ui-generation-report/ui-generation-report-template.md),
filled in all eight sections, and without the snapshot of the generation as first emitted. The
snapshot is the diff base for KPI-4 (BR-07): without it the metric is unobservable, and BRD says so
outright. A report that claims coverage the artifact does not have is worse than no report.

## The executable surface of `stand-test-ui` — do not exceed it

Everything below exists in this version. **Nothing outside it may appear in a generated artifact**,
however reasonable it sounds; if a case needs it, that is blocking missing capability, to be recorded
in the report's *not covered* section and raised with the SDK owners.

| Step type | Purpose | Assertions | Captures | Own wait |
|---|---|---|---|---|
| `ui.open` | navigate to a **relative** path | forbidden | forbidden | navigation timeout (configuration) |
| `ui.click` | click an element | forbidden | forbidden | action timeout (configuration) |
| `ui.fill` | type a value (resolves `${var}`) | forbidden | forbidden | action timeout (configuration) |
| `ui.expect` | assert element properties once | **≥1 required** | allowed | none |
| `ui.expectEventually` | poll until the assertions hold | **≥1 required** | allowed | `within(...)` **required in practice**, `pollInterval(...)` optional |
| `ui.login` | sign in as a pooled account of a role | forbidden | forbidden | `within(...)` + `accountTimeout(...)` |

Builder surface: `id`, `description`, `assertVisible()`, `assertVisible(boolean)`,
`assertEnabled(boolean)`, `assertText`, `assertTextContains`, `assertTextMatches`, `assertValue`,
`assertAttribute(name, expected)`, `assertProperty(UiProperty, AssertionMatcher, Object)`,
`capture(var)`, `capture(var, from)`, `capture(var, from, UiCaptureSource)`,
`captureAttribute(var, from, attribute)`, `within(Duration)`, `withinSeconds(long)`,
`pollInterval(Duration)`, `role(String)` *(login only)*, `accountTimeout(Duration)` *(login only)*,
`injectCorrelationId()`, `injectCorrelationId(boolean)`, `build()`.

Matcher asymmetry, enforced at `build()` and again at execution: `TEXT`, `VALUE` and `ATTRIBUTE`
accept all five core matchers (EQUALS, CONTAINS, MATCHES, EXISTS, NOT_NULL); `VISIBLE` and `ENABLED`
accept **EQUALS only**.

**Not in this version — do not write it:** screenshots, traces and report attachments (the registry
parses `trace:` but nothing consumes it yet); network interception or request assertions from the
browser; visual regression; `select`/`hover`/`press`/`upload`/drag-and-drop/back-forward/multi-tab/
iframe steps; assertions about the URL, the page title or the console; scrolling; `ui.*` in the
AI (JSON/YAML) format — **the UI track is Java-only**; a Spring-starter auto-configuration for the UI
executor (see the authoring skill for what a Spring consumer must declare); DOM masking of sensitive
zones before an artefact is taken; browser reuse across runs; a `SSO` sign-in scheme (declared,
refuses with "not implemented").

## Definition of done for a generated UI test

- Java test **and** Page Objects compile and pass checkstyle in the consumer project.
- Gated with `@EnabledIfEnvironmentVariable` on the application's `base-url-ref` variable, so it
  skips rather than fails without stand configuration.
- Every locator and every asserted text traces to a row of the discovery report; every fragile locator is
  listed in the generation report.
- Every wait is `ui.expectEventually` with a bounded `within(...)`; no sleep, no driver wait.
- Sign-in is `ui.login` with an explicit role; secrets nowhere, `asSensitive()` on secret/PII fields.
- UI safety review PASS + UI quality review APPROVE + the eight-section generation report + the
  preserved original generation.
- A human makes the merge decision.
