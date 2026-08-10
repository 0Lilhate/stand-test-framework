# AI-Agent Authoring Kit for stand-test-sdk

This directory ships a **deployable agent bundle** — as `.claude/` (Claude Code) and `.opencode/`
(opencode), same assets — that lets an AI agent convert a plain-text business test case into a
correct, safe automated test built on `stand-test-sdk`. Nothing here is runtime code — the SDK
itself is never modified by these assets.

## Final layout (what a consumer project gets)

```
docs/ai-agent/
  README.md          ← this guide (stays in the SDK repo; not part of the bundle)
  usage-guide.md     ← worked walkthrough (RU): OpenAPI spec → KB → env → Java test
  example-test-case-specification.md  ← how to WRITE the input case so the run needs no
                       blocking questions (stays in the SDK repo; not part of the bundle)
  install.mjs        ← installer: copies strictly by MANIFEST.json, dry-run by default
  MANIFEST.json      ← every shipped path + content hash; travels with the kit so `doctor` can
                       answer "which version is this, and what has been edited since". The hashes
                       answer the second question by themselves; the first needs the `version`
                       field to actually MOVE — see "Versioning the kit" below
  knowledge-base/    ← THE KB CONTRACT — schemas + worked examples. NOT installed by install.mjs
                       and not in MANIFEST.json: only `schema/` is generic, and it is copied by
                       hand (Installation step 2). The rest is THIS repository's own entries
    README.md, schema/*.schema.json,
    services/ endpoints/ kafka/ db/ grpc/ environments/ mappings/   (example-*.yml + README each)
    candidates/      ← staging area written by spec ingestion; never the curated KB
  .claude/           ← THE BUNDLE — copy its contents into the consumer repo's .claude/
    skills/          ← 26 self-contained skills (17 protocol + 9 UI), each with its templates/checklists/examples
      stand-test-case-analysis/        SKILL.md + test-case-analysis-template.md + example-text-case.md
      stand-test-kb-lookup/            SKILL.md + kb-lookup-result-template.yml + example-kb-lookup-result.yml
      stand-test-kb-bootstrap/         SKILL.md — cold start: the registry's aliases into an empty KB
      stand-test-kb-update/            SKILL.md + kb-update-report-template.md + kb-entry-review-checklist.md
      stand-test-spec-ingestion/       SKILL.md + unstructured-spec-ingestion-checklist.md + extraction-report-template.md + source-document-template.yml
      stand-test-spec-extraction/      SKILL.md + business-flow-candidate-template.yml + business-rule-candidate-template.yml
                                       + test-scenario-candidate-template.yml + unresolved-item-template.yml
      stand-test-kb-candidate-review/  SKILL.md + kb-candidate-review-checklist.md + conflict-item-template.yml
      stand-test-kb-candidate-apply/   SKILL.md
      stand-test-env-generation/       SKILL.md + env-generation-report-template.md + application-yml-generation-checklist.md
      stand-test-scenario-design/      SKILL.md + scenario-design-template.md + before-generating-checklist.md + example-scenario-design.md
      stand-test-yaml-authoring/       SKILL.md + yaml-scenario-template.yaml + example-generated.yaml
      stand-test-java-dsl-authoring/   SKILL.md + java-test-template.java + sdk-boundary-checklist.md
                                       + example-generated.java + example-provisioned-prelude.java
      stand-test-fixture-authoring/    SKILL.md + fixture-template.json
      stand-test-environment-mapping/  SKILL.md
      stand-test-safety-review/        SKILL.md + safety-checklist.md + safety-review-template.md
      stand-test-test-review/          SKILL.md + review-checklist.md + flakiness-checklist.md
                                       + generated-test-review-template.md + before-committing-checklist.md + example-review.md
      stand-test-debugging/            SKILL.md + debugging-report-template.md
      ── the UI branch (9), for cases that live on a screen ──
      stand-test-ui-case-intake/       SKILL.md + ui-case-template.md (the form for a MANUAL TESTER)
                                       + example-ui-case.md
      stand-test-ui-completeness-check/ SKILL.md + ui-completeness-checklist.md
      stand-test-ui-discovery/         SKILL.md + ui-discovery-report-template.md
                                       + locator-selection-checklist.md
      stand-test-ui-scenario-design/   SKILL.md + ui-scenario-design-template.md
      stand-test-ui-page-object-design/ SKILL.md + page-object-template.java + example-page-object.java
      stand-test-ui-java-authoring/    SKILL.md + ui-test-template.java + example-generated-ui-test.java
                                       + ui-sdk-surface-checklist.md (the anti-invention list)
      stand-test-ui-safety-review/     SKILL.md + ui-safety-checklist.md
      stand-test-ui-quality-review/    SKILL.md + ui-quality-checklist.md
      stand-test-ui-generation-report/ SKILL.md + ui-generation-report-template.md (BR-07's eight sections)
    commands/        ← 19 workflows as slash commands (14 protocol + 5 UI)
      stand-test-generate-java-test.md /stand-test-generate-java-test — TEXT CASE → VALIDATED TEST (umbrella, start here)
      stand-test-design.md      /stand-test-design   — text case → KB lookup → scenario design
      stand-test-yaml.md        /stand-test-yaml     — design → AI-format scenario (+ gates)
      stand-test-java.md        /stand-test-java     — design → Java DSL test (+ gates)
      stand-test-validate.md    /stand-test-validate — final readiness gate before commit
      stand-test-review-generated-test.md /stand-test-review-generated-test — existing test → KB-alignment + review
      stand-test-bootstrap-kb.md /stand-test-bootstrap-kb — empty KB → candidates from the registry + existing SDK tests
      stand-test-kb-update.md   /stand-test-kb-update — spec (OpenAPI/proto/SQL/...) → KB entries
      stand-test-ingest-spec.md /stand-test-ingest-spec — unstructured spec (PDF/DOCX/ФС/ТЗ) → KB candidates
      stand-test-review-kb-candidates.md /stand-test-review-kb-candidates — staged candidates → review report (human gate)
      stand-test-apply-kb-candidates.md  /stand-test-apply-kb-candidates  — approved candidates → curated KB
      stand-test-generate-env.md /stand-test-generate-env — KB → registry config (yml/application.yml)
      stand-test-debug.md       /stand-test-debug    — failed test → debugging report
      stand-test-kit-doctor.md  /stand-test-kit-doctor — is this installation the kit, and can it run?
      stand-test-generate-ui-test.md /stand-test-generate-ui-test — UI CASE → VALIDATED UI TEST (umbrella)
      stand-test-ui-design.md   /stand-test-ui-design    — UI case → discovery → design + Page Objects
      stand-test-ui-discover.md /stand-test-ui-discover  — look at the live screen; also the drift check
      stand-test-ui-java.md     /stand-test-ui-java      — design → UI test (+ compile gate)
      stand-test-ui-validate.md /stand-test-ui-validate  — UI gates + the generation report
    rules/           ← the only auto-loaded part of the bundle (Claude reads .claude/rules/**)
      stand-test-guardrails.md  ← non-negotiable constraints (mirrors ForbiddenOperation)
      stand-test-ui-guardrails.md ← what a browser adds: live UI as the DOM's source of truth, alias
                                  only, no PROD, discovery account, no irreversible actions, locator
                                  priority, no XPath, no sleeps, the SDK's UI surface, the report
      stand-test-pipeline.md    ← binding stage order + gates for BOTH branches; the .claude
                                  counterpart of .opencode/AGENTS.md, which points at this same file.
                                  States the rules only — the reasoning lives in reference/ below,
                                  because a rule file is loaded into every session and an argument
                                  for a rule is read once, by whoever is about to change it
    reference/       ← the non-auto-loaded half of the rules: why each one is what it is
      stand-test-pipeline-rationale.md  the incidents the enforcement layer was built from, the
                                  exemptions that were tried and rejected, and what is provable
                                  about delegation versus what merely reads as proof
      stand-test-ui-guardrails-rationale.md  what actually enforces the discovery-account rule (at
                                  stage 3: nothing), why rung 4 is fragile, what `asSensitive()`
                                  does NOT reach, and why the registry's version-4 credential pair
                                  is a knowing relaxation of ADR-UI-006 §5
    workflows/       ← 2 multi-command pipeline docs (not auto-loaded; referenced by the KB commands)
      ingest-unstructured-spec-to-kb.md    document → staged candidates
      review-and-apply-kb-candidates.md    candidates → human review → curated write
    agents/          ← 3 subagents: the stages a SEPARATE context must run. Shipped to BOTH copies;
                       the permission grammar differs by host, the instruction body does not
      stand-test-safety-reviewer.md    stage 8 — no Write, so it reports what it finds instead of fixing it
      stand-test-quality-reviewer.md   stage 11 — reads the ORIGINAL case, not the design
      stand-test-kb-resolver.md        stages 2+4 — Read/Grep/Glob only, keeps the KB out of the authoring context
    hooks/           ← the enforcement layer the HOST runs, model or no model (Claude Code only)
      stand-guard.mjs  pre-write / post-write / pre-bash / post-run / stop / subagent-stop / record-gate / scan
                       pre-bash also refuses a file WRITTEN BY THE SHELL — `cat > Test.java`, `tee`,
                       `sed -i`, `cp` into the tree — because pre-write is wired to Write/Edit only,
                       and that route delivers the same content with nothing scanned and no artifact
                       recorded, so the Stop gate would end the session with nothing to review
                       scan --format sarif --exit-code [--against <base version>] — the SAME gate in
                       CI, with no session and no model: findings become pull-request annotations,
                       and a rule is declared enabled only if it actually ran in that invocation
                       kb-status / kb-validate / alias-check — the KB checked at the site that USES it,
                       because the schema tests live in the SDK repo and do not travel with the bundle
                       kb-write-permit — curated KB writes are declared by path before the content
                       exists; the human confirms each write, kb-write re-reads what landed
      detectors.json   all 26 safety findings, as data — 18 protocol plus 8 UI-specific. Finding 18
                       (failure concealment) needs the artifact's previous version (the disk, or
                       `--against` in CI) and says so when it lacks one
      lib/, corpus/    the engine and the golden fixtures it is proven against
      stand-batch.mjs  NOT a hook — nothing invokes it, and it enforces nothing of its own. A
                       directory of text cases, one headless `claude -p` session each, run by a
                       person from a terminal. Every verdict comes from what the hooks recorded,
                       never from what the model said, and NEEDS-HUMAN is a correct outcome: in a
                       headless run nobody can answer the blocking questions of stage 3
  .opencode/         ← THE SAME BUNDLE for opencode — identical skills/commands/rules/workflows, plus
      AGENTS.md      ← opencode-specific manual (load model, command/skill index); the pipeline
                       itself lives in rules/stand-test-pipeline.md, shared by both bundles
      opencode.json  ← model, permissions, MCP servers; must sit NEXT TO `.opencode/`, not inside it
                       Ships machine-agnostic MCP servers ONLY — no database and no IDE server.
                       A wired-up SQL channel would reach every consumer that copies this bundle;
                       add one in a local override outside the repo (shape: `env.template`).
```

Each skill directory is self-contained: its SKILL.md references the colocated template,
checklists and worked example by relative path, so the bundle works wherever it lives.

## Installation into a consumer project (e.g. QA_TEST)

1. Install by manifest, not by directory copy:
   `node docs/ai-agent/install.mjs <consumer-repo>` — dry-run, prints what it would write —
   then the same with `--apply`. It copies exactly what `MANIFEST.json` lists, leaves a file you
   have edited locally alone unless you add `--force`, and drops the manifest beside the bundle so
   the installation can later be checked. A `cp -R` carries whatever happens to be in the
   directory, which is how machine-local residue reached consumers before.
   For opencode add `--host opencode`, then move `opencode.json` up to `<consumer-repo>/` — its
   `instructions` paths (`.opencode/AGENTS.md`, `.opencode/rules/**/*.md`) resolve from the
   directory that contains `.opencode/`.
   Then: `node .claude/hooks/stand-guard.mjs doctor` — version, files that did not arrive, files
   edited since, hooks that are not wired. Every other check that guards this kit lives in the SDK
   repository and does not travel; this one does.
2. Copy the KB **contract** across by hand — the installer ships the bundle and nothing else:
   `mkdir -p <consumer-repo>/knowledge-base && cp -R docs/ai-agent/knowledge-base/schema <consumer-repo>/knowledge-base/schema`.
   `knowledge-base/` lives in the consumer's repository ROOT and holds that project's own curated
   entries; this repository's copy of it holds THIS repository's (`services/`, `environments/`,
   `candidates/`), so nothing under it except `schema/` is generic enough to install — which is why
   the manifest lists neither. That also makes this `cp -R` a different act from the one step 1
   warns against: the warning is about copying a BUNDLE directory, where machine-local residue
   accumulates, and twenty schema files accumulate nothing. The remaining collections
   (`services/`, `endpoints/`, `kafka/`, `db/`, `grpc/`, `environments/`, `mappings/`) are created
   by the first `/stand-test-kb-update` or `/stand-test-bootstrap-kb`. `doctor` reports whether the
   directory is there, never what is in it; the schemas are the contract the kit's own
   `kb-validate` deliberately does not re-implement, so an absent `schema/` is a gap nothing will
   report. Worked walkthrough: [`usage-guide.md`](usage-guide.md) §1.
3. Verify the consumer project has: the SDK modules on the test classpath (BOM + junit or
   starter + adapters + config/allure) and its environment registry
   (`stand-test-environments.yml` or `application.yml` `stand.test.environments.*`).
4. For the JSON track additionally approve/add `com.networknt:json-schema-validator:1.5.6`
   + `jackson-databind` as test dependencies (the SDK ships only the schema resource).
5. Start with `/stand-test-design` on a real text case.
6. For a folder of cases at once:
   `node .claude/hooks/stand-batch.mjs cases/ --dry-run`, then without `--dry-run`. One headless
   session per case, sequentially — the hooks keep one state file per PROJECT, so parallel sessions
   would overwrite each other's gate bookkeeping and every verdict would be a guess. The report says
   which cases produced a test, which came back as questions, and which expectation families it could
   not check at all.

The bundle under `docs/ai-agent/` is the single source of truth. The repo-root `.claude/` is one
developer's local tooling and is deliberately untracked — do not treat anything there as part of
this contract.

## Versioning the kit

`MANIFEST.json` carries a `version`, and `doctor` prints it as the answer to "which set of prompts is
installed here". It is **hand-set**, because no machine can tell a reworded sentence from a changed
rule — and being hand-set is exactly how it sat at `1` while four rounds of edits changed what the
kit tells an agent to do. A number that never moves is not an answer; it reads like one, which is
worse.

Bump it when the kit's **meaning** changes:

| Bump | Do not bump |
|---|---|
| a rule, a stage, a gate, or the order of them | a rewording that leaves the instruction the same |
| a claim about what the SDK can or cannot do | a typo, a broken link, a formatting fix |
| a new skill, command, subagent or detector | a clarifying sentence added to an existing rule |

```bash
node docs/ai-agent/install.mjs --manifest --version 3   # regenerate AND bump
node docs/ai-agent/install.mjs --manifest               # regenerate, carrying the version
```

The command refuses a downgrade and refuses a non-number. The content hashes answer "what has been
edited" on their own and need no help from this field — the version answers the other question, which
is what a consumer asks when a generated test starts behaving differently than it did last month.

## How a text case becomes an autotest

The quality of the input decides how far the run gets before it has to stop and ask. Two worked
inputs, deliberately different:

| Input | What it shows |
|---|---|
| [`example-test-case-specification.md`](example-test-case-specification.md) | a **well-formed** case (SM-001) — exact expected values taken from the live stand, an explicit data-and-cleanup verdict, and correlation addressed rather than assumed. Every alias in it resolves against this repo's KB, so the claim "no blocking questions" is checkable, not asserted. It also states what it deliberately leaves out and why. Give this to whoever writes the cases. |
| [`.claude/skills/stand-test-case-analysis/example-text-case.md`](.claude/skills/stand-test-case-analysis/example-text-case.md) | a **raw** case (OT-101) as a QA engineer actually writes it, ambiguities included — the input the analysis skill is built to interrogate. The rest of the bundle's worked examples derive from it. |

`/stand-test-generate-java-test` runs the whole chain below as one umbrella workflow; the phase
commands remain individually invocable:

```
Text case
  → /stand-test-design      (skills: case-analysis → kb-lookup → environment-mapping → scenario-design)
  → /stand-test-java  OR  /stand-test-yaml   (+ fixture-authoring, safety-review)
  → /stand-test-validate    (schema/compile/run-skip-gate/safety/quality → readiness report)
  → human approval → commit
  → on failure: /stand-test-debug
```

## UI cases: the second branch

A case that lives on a **screen** goes down the UI branch instead. It exists because of one asymmetry:
a REST contract can be read from a specification, and a `data-testid` cannot — it exists only in the
DOM of a running application. So the branch has a stage the protocol chain has no equivalent of,
**discovery**, and a source-of-truth order with three rungs rather than two:

> **knowledge base → the live DEV/IFT UI → a question to the human.**
> "Not in the KB" is a reason to go and look, never a reason to invent, and by itself never a reason
> to ask.

```
UI business case (form: .claude/skills/stand-test-ui-case-intake/ui-case-template.md)
  → /stand-test-ui-design    (intake → completeness GATE → discovery → scenario design → Page Objects)
  → /stand-test-ui-java      (the test + compile/checkstyle over test AND Page Objects)
  → /stand-test-ui-validate  (UI safety gate → quality gate → generation report + original snapshot)
  → human approval → commit
/stand-test-generate-ui-test runs all of it; /stand-test-ui-discover re-runs discovery alone, which
is the right command when a merged test starts failing on locators.
```

| Rule | Why |
|---|---|
| The live UI is the source of truth for the DOM | an invented locator compiles, survives review by eye, and fails at run time exactly like application drift |
| Application by registry alias only; **PROD forbidden** | a browser opens whatever it is given — the environment key, the `ui-applications` whitelist and the account pool are three closed doors |
| Discovery runs under the **restricted discovery account** | `auth.discovery-account-ref` (SEC-10); the registry refuses a config where that account also sits in the working pool |
| **No irreversible action** during discovery | walk up to the last control before the effect and stop; a screen reachable only through it stays unexplored, and that is recorded |
| Locator priority `data-testid → role/name → label → stable attribute → text → CSS` | only `TEST_ID` is non-fragile (`UiLocator.fragile()`); rung 4 is a CSS attribute selector, so it is fragile too, and the report says so |
| No XPath; long/brittle CSS is flagged | the SDK has no XPath factory at all — the cheapest ban is having nowhere to put one |
| No `Thread.sleep`, no driver wait | every UI wait is `ui.expectEventually` with a bounded `within(...)` |
| Locators live in **Page Objects**, never in a test body | one screen changes, one file changes — and the static KPI-9 count reads merged Page Objects |
| The test depends on **no LLM at run time** | CI executes plain Java; nothing is resolved "by description" while the test runs |
| The result carries a **generation report** | eight sections (BR-07): covered · not covered and why · assumptions · fragile locators · UI↔backend binding · gate results · files created · the original generation |
| The original generation is **preserved** | copy + `sha256` under `ui-generation/<scenario-id>/`; without that diff base KPI-4 is unobservable |

**The UI track is Java-only** — `ui.*` steps are not in the AI JSON/YAML format, and no declarative UI
document can be executed. What the adapter does and does not offer is pinned in
[`ui-sdk-surface-checklist.md`](.claude/skills/stand-test-ui-java-authoring/ui-sdk-surface-checklist.md);
nothing outside it may appear in a generated artifact. The write hook carries **8 UI-specific
detectors** beside the protocol ones (addresses, secrets, sleeps, PII, which run over UI Java files
too): an invented locator (`UI_DISCOVERY_PARITY`, against the discovery report), a locator outside a
Page Object, XPath, `ui.login` without a role, `${…}` in an `ui.open` path or an expected value,
`expectEventually` without a bound, a stand address in a discovery report, and an incomplete
generation report. A ninth UI gate rides an existing detector rather than a new one: `THREAD_SLEEP` is
extended with the driver-level waits (`page.waitForSelector/Timeout/LoadState`, `.waitFor`).
Known gap, stated rather than implied: **that is still not a review** —
nine gates (U4's semantic half, U8, U10, U11a/b, U12, U14, U15, U18, U19) have no regular expression
and are closed by the stage-7 subagent and by a human, which is why a clean hook run is never reported
as a clean UI review.

## Knowledge base: the anti-invention layer

The agent never invents endpoints, topics, DB queries, gRPC methods or environment config.
Contract details resolve through the **schema-validated knowledge base**
([`knowledge-base/README.md`](knowledge-base/README.md)): a consumer project keeps YAML entries
at `knowledge-base/` (same layout as the shipped examples), validated by
[`knowledge-base/schema/stand-test-knowledge-base.schema.json`](knowledge-base/schema/stand-test-knowledge-base.schema.json)
and pinned by the KB validation tests in `stand-test-ai-schema`. `stand-test-kb-lookup` resolves a
case against it (unknowns become `missing`, never guesses); `/stand-test-kb-update` feeds it from
OpenAPI/AsyncAPI/proto/SQL specs (dry-run first, human-approved); `/stand-test-generate-env`
renders its environment entries into the registry formats below. The KB stores aliases, contracts
and env-var reference NAMES only — secrets, URLs and production environments are schema-rejected.

## Preferred authoring track

| Track | When | Why |
|---|---|---|
| **Java DSL** (default) | Any real business case; anything needing `db.seed`/`db.cleanup`, `rest.put`/`rest.delete`, non-equals matchers outside REST rewording, gRPC custom metadata, negative paths | Full feature surface; validator still runs inside `stand.run(...)` |
| **AI JSON/YAML** (`steps/type`) | Simple read-only flows fully inside the executable subset below | Machine-checkable before any code exists; smallest review surface |
| **Hybrid** | Case partially fits the AI format | AI document for the declarative part, thin Java test around it |
| **UI (Java only)** | Any case that lives on a screen — see the UI branch above | `ui.*` has no declarative surface; a UI case with backend effects stays ONE scenario, bound by a value captured off the screen |

### AI-format executable subset

The JSON Schema accepts slightly more than the runtime executes ("schema ⊇ executable"):

| Step type | Executable notes |
|---|---|
| `rest.get`, `rest.post` | all five matchers; body only `{"fixture": "path"}` |
| `rest.expectEventually` | GET-only; `timeout` required; needs `expect.status` and/or `assert` |
| `kafka.send` | `payload.fixture` only; `correlation: {inject: true}` |
| `kafka.expect` | `timeout` + `assert` required; **equals-only**; `correlation: {fromContext: true}` |
| `db.expectEventually` | SELECT-only; `expect.singleValue` only (`rowExists` rejected) |
| `grpc.unary` | `timeout` required; `request.fixture` only; all five matchers; `expect.status` rejected (a non-OK status is an infra failure) |

Not in the AI format at all: `db.query`/`db.seed`/`db.cleanup`, `rest.put`/`rest.delete`,
gRPC custom metadata. Source of truth: `ai/stand-test-ai-generation-rules.md` +
`/schema/stand-test-scenario.schema.json` inside the published `stand-test-ai-schema` jar
(`ru.alfa.stand.test.ai.AiSchemaResources`). **On any conflict, the jar resources win.**

## SDK modules an agent touches

| Module | Role |
|---|---|
| `stand-test-core` | `Scenario.builder(...)`, failure semantics, `ForbiddenOperation`, `${var}` resolver |
| `stand-test-rest` / `-kafka` / `-db` / `-grpc` | Typed lazy step builders |
| `stand-test-ui` | `UiStep` (`ui.open`/`click`/`fill`/`expect`/`expectEventually`/`login`), `UiLocator`, Playwright confined to its driver package. Discovered through `ServiceLoader` on BOTH surfaces: by `@StandTest` on plain JUnit, and by the starter's `StepExecutorDiscovery` since ADR-UI-008 — a starter consumer declares no bean |
| `stand-test-await` | The only sanctioned wait engine (via `*.expectEventually`) |
| `stand-test-junit` | `@StandTest`/`@StandEnv`/`@StandScenarioId`, `StandClient` injection |
| `stand-test-spring-boot-starter` | `@SpringBootTest` + `@Autowired StandClient`, `stand.test.*` config |
| `stand-test-config` | `stand-test-environments.yml` file registry |
| `stand-test-scenario-yaml` | `AiScenarioParser` (AI format, JSON or YAML), `YamlScenarioParser` (given/then) |
| `stand-test-ai-schema` | JSON Schema + generation-rules resources |
| `stand-test-allure` | Automatic Allure reporting via SPI (consumer adds `io.qameta.allure:allure-junit5:2.29.1`) |

## Safety constraints and prohibitions

The hard rules live in the bundle: [`.claude/rules/stand-test-guardrails.md`](.claude/rules/stand-test-guardrails.md)
(mirrors the 16-code `ForbiddenOperation` enum + review-only rules), with detection patterns
in [`.claude/skills/stand-test-safety-review/safety-checklist.md`](.claude/skills/stand-test-safety-review/safety-checklist.md).
Summary: aliases only, no secrets, no sleeps, bounded timeouts, no destructive SQL,
SDK-owned `testRunId`/`correlationId`, no pipeline/validator bypass, no production envs,
no SDK modifications, human approves every merge. Parallel-safe by construction (the SDK runs
tests in-JVM concurrently — classes concurrent, methods same_thread): all test data scoped by
`${testRunId}`; every `db.seed` declares `taggedByTestRunId("<col>")` = its cleanup's
`whereTestRunId("<col>")` column; every `kafka.expect` has a per-run discriminator
(`correlationIdFromContext` or a `${testRunId}`-derived key); no shared static state;
`@StandIsolated`/`@ResourceLock` only for a resource that cannot be `testRunId`-isolated.

## How to run validation

**AI JSON/YAML document** (before it is ever executed):

```java
// Schema gate — requires com.networknt:json-schema-validator (2020-12) + jackson (test classpath)
JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
JsonSchema schema = factory.getSchema(AiSchemaResources.scenarioSchemaJson());
Set<ValidationMessage> messages = schema.validate(new ObjectMapper().readTree(documentJson));
// must be EMPTY

// Parse gate (parser fails closed on non-executable constructs)
Scenario scenario = new AiScenarioParser().parse(documentJson);

// Guardrail self-check — REQUIRES the registry overload; the one-arg validate(Scenario)
// checks structure only and runs zero guardrails
EnvironmentRegistry registry = new FileEnvironmentRegistry();   // stand-test-config
new DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid();
```

**Java test**: `./gradlew compileTestJava checkstyleTest` in the consumer project.
`DefaultScenarioValidator` runs automatically inside `stand.run(...)`.

## How to run generated tests

Generated tests are gated to **skip** (not fail) without stand configuration:

```java
@EnabledIfEnvironmentVariable(named = "ORDER_SERVICE_URL", matches = ".+")
```

Wire the registry's env vars into the **test JVM**, not just the shell. A bare
`export VAR=... && ./gradlew test` is not a reliable channel to the forked test worker: the test then
skips silently and the build still reports `BUILD SUCCESSFUL`, so a test that never issued a request
looks like a passing one. In the consumer's `build.gradle.kts`:

```kotlin
tasks.withType<Test>().configureEach {
  listOf("ORDER_SERVICE_URL", "ORDER_DB_PASSWORD").forEach { name ->   // the registry's *-ref names
    providers.environmentVariable(name).orNull?.let { environment(name, it) }
  }
}
```

The names to forward are the `*-ref` fields' values plus, on the Spring-starter surface, the
variables inside the endpoint value twins' `${ENV_VAR:...}` placeholders.

**Always check the result, not the exit code**: `build/test-results/test/TEST-<class>.xml` must show
`tests="1" skipped="0"`. `skipped="1"` means the gate fired and the stand was never touched.
Assertion failures surface as `StandTestAssertionError` (JUnit red), infra/config problems
as `StandTestException` (JUnit error).

## How to review the result

Run `/stand-test-validate`; it applies the safety checklist (blocking), the review and
flakiness checklists, and produces a READY/NOT-READY report. A generated test is never
merged without an explicit human decision on that report.
