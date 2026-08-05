# UI safety review — gate table and detection patterns

> Appended to the protocol `safety-review-template.md` by skill `stand-test-ui-safety-review`
> (stage 7). Every gate is answered PASS/FAIL with evidence; a FAIL on a BLOCK gate stops the
> workflow.

## Scope reviewed

| Artifact | Path |
|---|---|
| UI test class(es) | |
| Page Object(s) | |
| Discovery report | |
| Scenario design | |
| Registry addition (if any) | |
| Build diff (if any) | |
| Generation report + original-generation snapshot *(re-review only — absent on the first pass)* | |

## Gate table

| # | Gate | Severity | Result | Evidence |
|---|---|---|---|---|
| U1 | Every locator constant has a row in the discovery report | BLOCK | | constant → report row |
| U2 | No `UiLocator` outside `**/ui/pages/**` | BLOCK | | grep output |
| U3 | No address anywhere (test, Page Objects, reports) | BLOCK | | grep output |
| U4 | Every secret/PII locator is `asSensitive()` | BLOCK | | locator list |
| U5 | Sign-in is `UiStep.login(...)` with a declared role; no hand-rolled login form | BLOCK | | |
| U6 | No sleep, no driver wait, no retry loop | BLOCK | | grep output |
| U7 | No XPath anywhere | BLOCK | | grep output |
| U8 | Every type and method is on the SDK surface checklist | BLOCK | | |
| U9 | No `${…}` in a `ui.open` path or in any assertion's expected value | BLOCK | | grep output |
| U10 | Environment is a DEV/IFT key; the alias is whitelisted in it | BLOCK | | registry evidence |
| U11a | Every `ui.click` on an irreversible control is named by the case and acts on data the test owns | BLOCK | | case reference |
| U11b | Discovery performed **no** irreversible action: the *controls stopped-before* list is present and no recorded screen sits behind one | BLOCK | | discovery report |
| U12 | Discovery ran under the discovery account | BLOCK | | report header |
| U13 | No secret, session state or personal value in any report | BLOCK | | grep output |
| U14 | No model/agent call, prompt or description-based locator library at run time | BLOCK | | import + dependency scan |
| U15 | Every value the run must make unique derives from `${testRunId}` or a capture | BLOCK | | fill values |
| U16 | Generation report present with all eight sections; original generation preserved — **re-review only**; on the first pass (stage 7) stage 9 has not run yet, answer `n/a (first pass)` | BLOCK | | paths |
| U17 | Every `ui.expectEventually` sets an explicit `within(...)` | HIGH | | |
| U18 | Every brittle CSS selector is flagged in the report's fragile list | HIGH | | |
| U19 | No `ui.expect` on something the screen renders after an action | HIGH | | |
| U20 | No shared mutable state in the test class or Page Objects | HIGH | | |

## Detection patterns

Run these over the consumer's test sources; adjust the path roots to the project.

```bash
# U2 — a locator that escaped its Page Object (hits outside ui/pages are findings)
grep -rn 'UiLocator\.' src/test/java | grep -v '/ui/pages/'

# U3 — addresses, in code AND in the reports that travel with it
grep -rnE 'https?://|\b[a-z0-9.-]+:[0-9]{2,5}/|jdbc:' src/test/java docs/**/Ui*Report.md

# U6 — sleeps and driver-level waits, including the ones a browser tempts you into
grep -rnE 'Thread\.sleep|Awaitility|waitFor[A-Za-z]*\(|\bsleep\(|while *\(.*(retry|attempt)' src/test/java

# U7 — XPath in any spelling (it cannot compile against this SDK; a hit is smuggled or invented)
grep -rniE 'xpath|//\*\[|\bby\.xpath' src/test/java

# U9 — ${var} where nothing resolves it: ui.open paths and expected values
grep -rn 'UiStep\.open([^)]*\${' src/test/java
grep -rnE 'assert(Text|Value|Attribute|TextContains|TextMatches)\([^)]*\$\{' src/test/java

# U14 — a run-time dependence on a model
grep -rniE 'openai|anthropic|claude|llm|selfheal|self_heal|healenium|prompt' src/test/java build.gradle.kts

# U4 — secret/PII locators that are not marked
grep -rniE 'UiLocator\.(label|role|text|testId|css)\([^)]*(пароль|password|token|otp|код|карт|снилс|инн|паспорт)' src/test/java \
  | grep -v 'asSensitive'

# U15 — a fill value that repeats across runs
grep -rn 'UiStep\.fill(' -A 1 src/test/java | grep -vE '\$\{testRunId\}|\$\{[a-zA-Z]'
```

The greps are a sweep, not the review: the scanner reads text, so a locator assembled from a constant
in a neighbouring file, a fully qualified call, or a value passed in through three layers all slip
past. **U1 in particular cannot be grepped at all** — an invented locator is syntactically perfect.
Read the discovery report and the Page Objects side by side.

## Machine coverage — state it honestly in the report

| Gate | Automated? |
|---|---|
| U3 (addresses), U4 (secret half), U6 (sleeps), U13 (secrets/PII in prose) | yes — the protocol write hook's detectors run over Java and, for disclosure findings, over prose |
| U1, U2, U5, U7–U12, U14–U20 | **no detector in this version of the kit** — eye only |

A clean hook run is therefore **not** a clean UI review, and the report must not present it as one.

## Findings

| # | Gate | Severity | File:line | Finding | Required fix |
|---|---|---|---|---|---|

## Verdict

**PASS | PASS-WITH-NOTES | BLOCK**

Recorded with:

```
node <bundle>/hooks/stand-guard.mjs record-gate --gate safety-review --verdict <verdict> <files>
```

The file list is mandatory and the verdict covers exactly those files. `record-gate` re-runs the
deterministic half and will not write a `PASS` over a blocking finding.
