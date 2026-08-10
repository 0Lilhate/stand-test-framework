# The `stand-test-ui` surface — what exists, and what must not be written

> The anti-invention reference for the UI branch. Everything below exists in this SDK version.
> **Anything not below does not exist**, however reasonable it sounds; a case that needs it is a
> missing capability for the generation report's *not covered* section and a conversation with the
> SDK owners — never an improvised API call.
>
> Checked by `stand-test-ui-java-authoring` (stage 6) while writing, and by
> `stand-test-ui-safety-review` (stage 7) afterwards.

## Step types

| Type | Builder entry point | Assertions | Captures | Own wait |
|---|---|---|---|---|
| `ui.open` | `UiStep.open(alias, relativePath)` | **refused** | **refused** | navigation timeout (system property) |
| `ui.click` | `UiStep.click(alias, locator)` | **refused** | **refused** | action timeout (system property) |
| `ui.fill` | `UiStep.fill(alias, locator, value)` | **refused** | **refused** | action timeout (system property) |
| `ui.expect` | `UiStep.expect(alias, locator)` | **≥1 required** | allowed | none |
| `ui.expectEventually` | `UiStep.expectEventually(alias, locator)` | **≥1 required** | allowed | `within(...)` / `withinSeconds(...)`, `pollInterval(...)` |
| `ui.login` | `UiStep.login(alias)` | **refused** | **refused** | `within(...)` for the sign-in, `accountTimeout(...)` for a free account |

"Refused" means the builder throws at `build()` — the mistake surfaces while the test is being
written, not while it runs.

## Builder methods

| Group | Methods |
|---|---|
| identity | `id(String)`, `description(String)` |
| boolean assertions | `assertVisible()`, `assertVisible(boolean)`, `assertEnabled(boolean)` |
| string assertions | `assertText(String)`, `assertTextContains(String)`, `assertTextMatches(String)`, `assertValue(String)`, `assertAttribute(String name, String expected)` |
| open assertion | `assertProperty(UiProperty, AssertionMatcher, Object)` |
| captures | `capture(String var)`, `capture(String var, UiLocator from)`, `capture(String var, UiLocator from, UiCaptureSource)`, `captureAttribute(String var, UiLocator from, String attribute)` |
| waits | `within(Duration)`, `withinSeconds(long)`, `pollInterval(Duration)` |
| sign-in only | `role(String)`, `accountTimeout(Duration)` |
| correlation | `injectCorrelationId()`, `injectCorrelationId(boolean)` |
| terminal | `build()` |

`capture(var)` with no source element reads the **step's own** locator, so it exists only on steps
that have one.

## Locators

`UiLocator.testId(String)` · `UiLocator.role(String role, String accessibleName)` ·
`UiLocator.label(String)` · `UiLocator.text(String)` · `UiLocator.css(String)` ·
`.asSensitive()` · `.fragile()` · `.describe()`.

`LocatorStrategy`: `TEST_ID`, `ROLE`, `LABEL`, `TEXT`, `CSS`. **There is no XPath** — no constant, no
factory, no `xpath=` registry spelling.

## Properties and matchers

`UiProperty`: `TEXT`, `VALUE`, `ATTRIBUTE`, `VISIBLE`, `ENABLED`.
`UiCaptureSource`: `TEXT`, `VALUE`, `ATTRIBUTE`.

| Property | Matchers accepted |
|---|---|
| `TEXT`, `VALUE`, `ATTRIBUTE` | `EQUALS`, `CONTAINS`, `MATCHES` (full-string regex), `EXISTS`, `NOT_NULL` |
| `VISIBLE`, `ENABLED` | **`EQUALS` only** — enforced at `build()` and again at execution |

`EXISTS` / `NOT_NULL` take a boolean expected value.

## Where `${var}` is resolved — and where it is NOT

This table decides whether a generated test works, and it is not symmetric. Read it before writing a
dynamic value.

| Place | Resolved? |
|---|---|
| `ui.fill` **value** | **yes** — `"ext-${testRunId}"` becomes the real value at execution |
| `ui.open` **path** | **no** — the path is used verbatim; `"/applications/${number}"` navigates to a literal `${number}` |
| assertion **expected values** (UI and every other adapter) | **no** — `assertText("Заявка ${number}")` compares against the literal string |
| capture **variable names** | n/a — they are names, not values |
| REST path / query / headers / body, DB `param(...)`, Kafka body/key/headers, gRPC request | **yes** |

Consequences for the author:

- Bind a captured id into a **REST/DB/gRPC** step (those resolve), not into a `ui.open` path.
- To reach a screen whose address contains a run-specific id, **click through the UI** to it — that
  is what a user does anyway — rather than trying to construct the path.
- Assert a run-specific value by **shape** (`assertTextMatches("AP-\\d+")`) or by presence
  (`assertProperty(UiProperty.TEXT, AssertionMatcher.NOT_NULL, true)`), never by an expected string
  containing `${…}`.

## Sign-in

`UiStep.login(alias).role(role).withinSeconds(n).accountTimeout(Duration)`. Schemes come from the
registry: `NONE`, `FORM`, `STORAGE_STATE`, `SSO` (declared, refuses with "not implemented"). The
sign-in form's locators live in the registry (`auth.login.*`, spelled `<strategy>=<value>`), never in
test code. Accounts come from the pool behind `credentials-pool-ref`; the roster holds ids, roles and
the **names** of credential variables — never a login or a password.

An application that has exactly ONE account may name it directly instead, with the pair
`auth.credentials-username` / `auth.credentials-password` (registry format version 4, mutually
exclusive with the roster). It answers every declared role with the same account, so a suite's
parallelism there is one run per role, not the pool size. Both spellings are still references — a bare
word is the name of an environment variable — but this one also accepts the `${var:value}` form, which
puts a VALUE in a file that lives in git. Hence the one rule an authoring stage must not lose:
**a password is never given a default.** Nothing about any of this reaches test code: the test names a
role, and that is all it ever knows about credentials.

`challenge: mfa | otp | captcha` is a **declaration, not a bypass**: either a
`UiLoginChallengeHandler` is registered on the test classpath, or the application moves to
`STORAGE_STATE` with a session prepared outside the SDK. There is no third option and none may be
designed (external gate G-1).

## Run configuration (system properties, not scenario fields)

`stand.test.ui.headless` (`true`) · `stand.test.ui.browser` (`chromium`) ·
`stand.test.ui.action.timeout.millis` (`10000`) · `stand.test.ui.navigation.timeout.millis`
(`30000`) · `stand.test.ui.artifacts.dir` (`build/stand-test-ui`).

The viewport comes from the registry (`ui-applications.<alias>.default-viewport` /
`viewport-profiles`). **`Scenario` carries no UI field at all** — that is pinned by a test in the SDK
repository, so a "viewport" or "browser" parameter on a step does not exist to be written.

## Failure classification

| What happened | Thrown | Reported |
|---|---|---|
| an expectation was not met (text, visibility, enabled) | `StandTestAssertionError` | FAILED |
| the element never became actionable within the action timeout | `StandTestAssertionError` | FAILED |
| `expectEventually` ran out of time | `StandTestAssertionError` + await diagnostics | FAILED |
| a capture from an element that is not there | `StandTestAssertionError` | FAILED |
| the locator matched **several** elements | `StandTestException` (names locator + match count) | BROKEN |
| browser would not start, page would not load, alias not whitelisted, pool exhausted, unknown driver error | `StandTestException` | BROKEN |

## What a failing step leaves behind — automatic, never authored

Nothing below is written into a scenario: the executor attaches it when a `ui.*` step fails, and a
green step leaves nothing at all. Know it anyway, because it decides what "not covered — SDK" may say
in the generation report, and because it is what a red run is diagnosed from.

| Artefact | When | Note |
|---|---|---|
| `ui-screenshot` (PNG) | every failing step, if a driver still exists | the zones of that step's `asSensitive()` locators are painted over **before** the grab; a zone that cannot be masked cancels the screenshot rather than risk it |
| `ui-console` (text) | when the page logged anything | rides the text channel, so the sink's secret masker runs over it |
| `ui-network` (text) | when any request was observed | method/path/status only, credential headers already masked |
| `ui-trace` (ZIP) | only where the registry declares `trace: on-failure` | Playwright Trace Viewer; off by default, it is the heavy one. `ui.login` suspends recording around the credential fills |

All four are best-effort — a capture that fails is a WARN and never replaces the step's own failure —
and everything written lives under `stand.test.ui.artifacts.retention.days` (7 by default).

## Wiring — what the consumer must declare

| Consumer | What it declares |
|---|---|
| plain JUnit (`stand-test-junit` + `stand-test-ui` + `stand-test-config`) | nothing — `StandTestExtension` loads every `StepExecutor` through `ServiceLoader`, and `stand-test-ui` registers `UiStepExecutor` in `META-INF/services` |
| Spring Boot starter | nothing either, since ADR-UI-008 — `StepExecutorDiscovery` loads SPI-registered executors beside the beans the starter declares. A `UiStepExecutor` bean is **optional**: declared, it wins by ordering, and the SPI copy of the same class is de-duplicated. **Never block a generation for the want of it** |

This table sits above the absence list on purpose. It used to be an ABSENCE — "the starter does not
auto-configure the UI executor" — and stayed one for a release after ADR-UI-008 made it false, which
turned four assets into a false blocker: two of them told the agent to stop. An absence that becomes a
capability has to move out of the absence table, not be reworded inside it.

## Absent from this version — do not write it

| Wanted | Status |
|---|---|
| a screenshot or a trace taken **on demand** | absent — the artefacts above happen on failure only; no step, builder method or configuration key orders one |
| masking sensitive zones in the DOM before an artefact is taken | absent — the mask is a screenshot option, the DOM is left untouched (`asSensitive()` both hides values in messages/diagnostics and masks the zone in the screenshot, which does work) |
| assertions about the URL, the page title, the console | absent — the console is attached as **evidence**, and evidence is not an assertable surface |
| network interception / asserting the requests a page makes | absent (`injectCorrelationId()` adds a header; the `ui-network` attachment is evidence in the report, not something a step can assert on) |
| `select`, `hover`, `press`, keyboard input beyond `fill`, file upload, drag-and-drop, scrolling | absent |
| navigating back/forward, multiple tabs, iframes, new windows | absent |
| visual regression, pixel comparison | absent |
| `ui.*` in the AI (JSON/YAML) format | absent — **the UI track is Java-only** |
| browser reuse between runs, a browser pool | absent (one browsing context per run, closed in the runner's `finally`) |
| a UI-side compensation / undo for a browser action | absent — the run's undo-log reaches `db.write` only |
| `SSO` sign-in | declared in the registry, refuses with a speaking "not implemented" |
| any MFA / OTP / CAPTCHA bypass | **deliberately absent and will not be added** (external gate G-1) |

## The one-line self-check

> Every type, method and constant in the generated artifact appears somewhere above — and every
> `${…}` sits in a place the middle table marks **yes**.
