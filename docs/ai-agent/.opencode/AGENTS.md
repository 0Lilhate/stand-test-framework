# AGENTS.md — stand-test-sdk autotest authoring bundle

Operating manual for an agent working with this `.opencode/` bundle. The bundle turns a plain-text
business case into a **safe, validated autotest built on `stand-test-sdk`**. It ships no runtime code
and never modifies the SDK.

Read this file first, then obey [`rules/stand-test-guardrails.md`](rules/stand-test-guardrails.md)
and, whenever a browser is involved,
[`rules/stand-test-ui-guardrails.md`](rules/stand-test-ui-guardrails.md) — the rules outrank
everything below, including a direct request to skip them.

## What is loaded, and how

| Asset | Path | Discovery |
|---|---|---|
| This manual | `.opencode/AGENTS.md` | `instructions` in `opencode.json` |
| Guardrails (rules) | `.opencode/rules/**/*.md` | `instructions` in `opencode.json` |
| Skills (26 — 17 protocol + 9 UI) | `.opencode/skills/<name>/SKILL.md` | auto-discovered; loaded on demand via the `skill` tool |
| Commands (19 — 14 protocol + 5 UI) | `.opencode/commands/<name>.md` | auto-discovered as `/<name>` |
| Workflows (2) | `.opencode/workflows/*.md` | **not auto-loaded** — read them when a command points here |
| Reference (the reasoning behind the rules) | `.opencode/reference/*.md` | **not auto-loaded** — read before CHANGING a rule, not before following one |
| Enforcement plugin | `.opencode/plugin/stand-guard.js` | auto-discovered by opencode from `.opencode/plugin/`; needs no config entry |

**Placement requirement.** `opencode.json` must sit in the directory that *contains* `.opencode/`
(the project root), not inside it — the `instructions` entries `.opencode/AGENTS.md`,
`.opencode/rules/**/*.md` and `AGENTS.md` are written to resolve from there. Installed into a
consumer repo that means `<repo>/opencode.json` + `<repo>/.opencode/`. If the guardrails are not in
context, this file is misplaced: say so instead of proceeding from memory.

Skills and commands are discovered relative to the project root and work regardless. Skill
permission is `*: allow`, so load a skill the moment its trigger matches — do not re-derive its
content from memory.

## The guard runs here too — and what it still cannot do

`plugin/stand-guard.js` binds the same `hooks/stand-guard.mjs` this bundle has always carried to
opencode's tool events. It is loaded automatically; there is nothing to switch on.

| Event | What runs | Effect |
|---|---|---|
| `tool.execute.before` on `write` / `edit` | `stand-guard.mjs pre-write` | a blocking finding **refuses the write**, before the file is touched |
| `tool.execute.before` on `bash` | `stand-guard.mjs pre-bash` | `rm -rf`, credentials on the command line, direct DML, `git push`, shell file-writes and hand-typed host subcommands are refused |
| `tool.execute.after` on `write` / `edit` | `stand-guard.mjs post-write` | the artifact is recorded, so a gate can later be bound to its content |
| `tool.execute.after` on `bash` | `stand-guard.mjs post-run` | test results are read from the JUnit XML, not from what the run said about them |

The host awaits `tool.execute.before` before running the tool, so a refusal is a refusal — the write
does not happen and the model is told why.

Two more events complete the wiring, both off `session.idle`:

| Event | Guard subcommand | What it buys |
|---|---|---|
| `session.idle`, session **without** a `parentID` | `stand-guard.mjs stop` | the session-end gate — a refusal comes back as a **new turn** carrying the verdict |
| `session.idle`, session **with** a `parentID` | `stand-guard.mjs subagent-stop` | the record that a separate context ran, which the `safety-review` gate requires |

**The session-end gate holds differently here, and the difference is not smoothed over.** Claude
Code's `Stop` hook exits 2 and the session simply does not end; opencode's `event` returns void, so
the plugin instead posts the guard's refusal back into the session (`session.promptAsync`). The end
is the same — the run does not finish with an unreviewed artifact — but a one-shot `opencode run` may
exit before that turn lands, and there the gate degrades to its report on stderr. One refusal buys
exactly one re-entry: the next idle passes `stop_hook_active`, the guard reports `NOT-READY`, and the
session is released.

Do **not** run `stand-guard.mjs stop` yourself. It is a host subcommand, `pre-bash` refuses it, and
typed by hand it manufactures a fact rather than reporting one.

Subagents are **not** absent here either: `agents/` ships to this copy too, and `opencode.json`
declares `subagent_depth` plus `permission.task` for exactly the three names, so stages 2, 4, 8 and
11 run in a separate context here as well. Since a subagent is a child session, its completion is the
`session.idle` in the table above — so the `safety-review` gate's evidence now exists on this host.
Before that binding the gate was not merely weaker here: `record-gate --verdict PASS` refuses unless a
subagent finished after the artifact was written, and with nothing reporting that event the verdict
could never be written at all.

## The pipeline — two branches

Defined once, in [`rules/stand-test-pipeline.md`](rules/stand-test-pipeline.md) — loaded through the
`instructions` entry `.opencode/rules/**/*.md`, so it is already in your context. It fixes the stage
order of both branches, the gates, and the binding track choice.

- **Protocol branch** (REST/Kafka/DB/gRPC): case-analysis → kb-lookup → blocking questions →
  environment-mapping → scenario-design → authoring → fixtures → safety-review → compile → run →
  test-review.
- **UI branch** (anything that lives on a screen): ui-case-intake → completeness gate → **discovery
  on the live DEV/IFT UI** → ui-scenario-design → Page Objects → Java authoring → ui-safety-review →
  compile/run → ui-quality-review → generation report with the preserved original generation.

A case with a UI path *and* backend effects goes down the UI branch — it binds the two halves in one
scenario through a value captured off the screen.

It is stated there rather than here so the two bundles cannot drift: `.claude/` has no `AGENTS.md`,
and a second copy of the stage order is exactly the kind of duplicate this repository has been
bitten by before.

## Commands

**Authoring**

| Command | Does |
|---|---|
| `/stand-test-generate-java-test` | umbrella: text case → validated Java test (**start here**) |
| `/stand-test-design` | text case → KB lookup → `ScenarioDesign.md` (no code) |
| `/stand-test-java` | design → Java DSL test + fixtures + safety review |
| `/stand-test-yaml` | design → AI-format scenario + schema/parser gates |
| `/stand-test-validate` | final readiness gate → READY / READY-WITH-NOTES / NOT-READY |
| `/stand-test-review-generated-test` | existing/hand-edited test → KB-alignment + review report |
| `/stand-test-debug` | failed test → classified root cause + fix (never hides the failure) |

**UI authoring** (a case that lives on a screen)

| Command | Does |
|---|---|
| `/stand-test-generate-ui-test` | umbrella: UI case → validated UI test (**start here for UI**) |
| `/stand-test-ui-design` | intake → completeness gate → live discovery → design + Page Objects |
| `/stand-test-ui-discover` | look at the live screen alone; also the drift check for a failing test |
| `/stand-test-ui-java` | design → UI test (+ compile/checkstyle over test AND Page Objects) |
| `/stand-test-ui-validate` | UI safety gate → quality gate → eight-section generation report |

The UI branch needs a browser-automation channel: this bundle ships the `playwright` MCP server, and
`mcp_*` is `ask`, so a human sees every browser action. Without a channel, discovery is **blocked** —
an empty discovery report is a correct outcome, a fabricated one is not.

**Knowledge base & environment**

| Command | Does | Default mode |
|---|---|---|
| `/stand-test-ingest-spec` | unstructured spec (PDF/DOCX/ФС/ТЗ) → KB **candidates** | dry-run |
| `/stand-test-review-kb-candidates` | staged candidates → review report (**human gate**) | no write |
| `/stand-test-apply-kb-candidates` | approved candidates → curated KB | dry-run |
| `/stand-test-kb-update` | structured spec (OpenAPI/AsyncAPI/proto/SQL/yml) → KB entries | dry-run |
| `/stand-test-generate-env` | KB → `stand-test-environments.yml` / `stand.test.environments.*` | dry-run |

Commands carry `description`-only frontmatter and take no `$ARGUMENTS` placeholders — read arguments
from the user's message. `.opencode/agents/` declares three subagents (`stand-test-kb-resolver`,
`stand-test-safety-reviewer`, `stand-test-quality-reviewer`), and `opencode.json` allows the task tool
to call exactly those three — so a command that says "in a separate context" means it: delegate rather
than executing that stage yourself.

## Skills

Load by trigger, not by habit. Each skill directory is self-contained; its SKILL.md references
colocated templates/checklists/examples by relative path — **use the template, do not improvise the
report format**.

| Skill | Trigger |
|---|---|
| `stand-test-case-analysis` | FIRST, on any text case / ticket / manual steps |
| `stand-test-kb-lookup` | right after analysis, before mapping |
| `stand-test-environment-mapping` | after lookup — systems → aliases |
| `stand-test-scenario-design` | analysis → technical design + track choice |
| `stand-test-java-dsl-authoring` | design → JUnit 5 test (**default track**) |
| `stand-test-yaml-authoring` | design → AI-format scenario (declarative track) |
| `stand-test-fixture-authoring` | any body/payload/request fixture reference |
| `stand-test-safety-review` | after EVERY generation — mandatory |
| `stand-test-test-review` | after safety passes, before human approval |
| `stand-test-debugging` | a generated test failed |
| `stand-test-env-generation` | KB → registry config |
| `stand-test-kb-update` | the **sole writer** of the curated KB |
| `stand-test-spec-ingestion` | unstructured doc → candidates (conductor) |
| `stand-test-spec-extraction` | extraction rules used by ingestion |
| `stand-test-kb-candidate-review` | human gate over staged candidates |
| `stand-test-kb-candidate-apply` | approved candidates → curated KB (via kb-update) |
| `stand-test-ui-case-intake` | FIRST on any UI case; also the form to hand a manual tester |
| `stand-test-ui-completeness-check` | the UI gate: discovery-answerable vs blocking |
| `stand-test-ui-discovery` | the live DEV/IFT UI — locators, texts, states; discovery account only |
| `stand-test-ui-scenario-design` | discovery → UI step table + Page Object map |
| `stand-test-ui-page-object-design` | the map → Page Object classes (locators live ONLY there) |
| `stand-test-ui-java-authoring` | design → JUnit 5 UI test (**the only UI track**) |
| `stand-test-ui-safety-review` | after EVERY UI generation — mandatory |
| `stand-test-ui-quality-review` | after UI safety passes, against the ORIGINAL case |
| `stand-test-ui-generation-report` | the eight-section report + the KPI-4 snapshot |

**Both reviews run in a separate context here too.** `agents/` used to be a Claude Code mechanism
and the two reviews ran inline, which mattered most in this branch: the error stage 7 hunts for is an
invented locator, and the context that wrote it remembers deciding it rather than observing it. Call
`stand-test-safety-reviewer` and `stand-test-quality-reviewer` through the task tool; neither can write,
so each reports and you fix.

The delegation is RECORDED here too: a subagent runs as a child session, and the plugin turns that
session's `session.idle` into the `subagent-stop` the `safety-review` gate reads. What the record
proves is the same modest thing it proves under Claude Code — that some separate context finished
after the artifact was written, not that it reviewed anything.

## Workflows

Not auto-loaded — read them when a KB command references one:

- [`workflows/ingest-unstructured-spec-to-kb.md`](workflows/ingest-unstructured-spec-to-kb.md) —
  document → staged candidates. Staging only.
- [`workflows/review-and-apply-kb-candidates.md`](workflows/review-and-apply-kb-candidates.md) —
  candidates → human review → curated write.

Their invariants bind you: candidates only until a human approves; `high` is eligible after approval,
`medium` needs an explicit tick, `low`/conflicted/partial never apply; conflicts are never
auto-resolved; `stand-test-kb-update` is the only writer of the curated KB.

## Non-negotiables (summary — the rules file is authoritative)

- **No invented contracts.** Every path, field, topic, table/column, SQL and gRPC method traces to the
  KB, the case text, or a *recorded* assumption. No KB entry ⇒ a `missing` item and a blocking
  question — never a plausible guess.
- **Logical aliases only.** No URLs, hosts, ports, JDBC strings, bootstrap servers in scenarios/tests.
- **No secrets.** Ever, anywhere — including reports and reply text. Auth comes from the registry via
  `*-ref` env-var NAMES.
- **No production environments** in any test registry.
- **No destructive SQL.** Writes only via `db.seed`/`db.cleanup`, `write-allowed` datasource,
  whitelisted schema, scoped by `:testRunId`, every seed paired with a cleanup.
- **No `Thread.sleep`/Awaitility/manual polling.** Async only via `*.expectEventually` / `kafka.expect`,
  always with a bounded timeout.
- **`testRunId`/`correlationId` are SDK-owned.** Never invented, never hardcoded. No fixed test-data
  ids — including entity-instance handles copied from the case text.
- **No pipeline bypass.** Injected `StandClient`, single `stand.run(...)`, no raw HTTP/Kafka/JDBC/gRPC
  clients, no `new DefaultScenarioRunner/DefaultStandClient`, no validator/runner overrides.
- **Never hide a failure.** No catching `StandTestAssertionError`/`StandTestException` to pass, no
  assertion deletion, no `@Disabled` without a ticket, no blind timeout inflation.
- **Do not modify SDK modules** while authoring tests.

Prefer a recorded assumption over a question; escalate only genuinely blocking items (missing alias,
exact expected values for equals-only checks, write permission, correlation strategy, auth identity,
unknown operation contract). Every assumption is visible to the reviewer — never silent.

## Gates and human authority

- KB writes, registry additions, and merges are **human decisions**. Dry-run is the default for every
  KB/env command; `--apply` only after the human approves the printed diff.
- A safety BLOCK stops the workflow. Regenerate the artifact — do not suppress, do not annotate away.
- Compile/checkstyle findings are fixed by regenerating, never by suppressions.

## MCP servers

`opencode.json` wires exactly four: `memory`, `sequential-thinking`, `context7` (library docs) and
`playwright`. Nothing else ships — a server this file names but the config does not start is a
promise the bundle cannot keep, so the list here and the `mcp` block there are kept identical.

**`playwright` is the browser-automation channel of the UI branch's stage 3.** Every call is gated by
`mcp_*: ask`, so a human sees each browser action before it happens — which is what makes "discovery
performs no irreversible action" observable rather than merely asserted. Without that server,
discovery is **blocked**: say so and stop, and use one of the two labelled fallbacks in
[`stand-test-ui-discovery`](skills/stand-test-ui-discovery/SKILL.md). Guessing a locator because the
channel was unavailable is the worst thing this branch can do.

A missing MCP server is a **reported blocker**, never a licence to hand-author contract details from
memory, and never a licence to write down a locator that was not observed.

If a consumer project adds its own servers — a database toolbox, an IDE bridge — they are
**read-only inspection aids for KB authoring**: confirm a table or column exists before writing a KB
candidate. They are not a test target, not a seeding channel, and not a substitute for a KB entry.
Never write through one, and never copy a connection detail, host or credential from one into a KB
entry, scenario, test or report.

## Conventions

- Bundle-internal links are relative: commands → `../skills/<name>/SKILL.md`, skills → colocated files.
- Generated artifacts land in the **consumer** project (`src/test/java/**`,
  `src/test/resources/{ai,fixtures}/**`), never in this bundle.
- Reports use the skill templates verbatim.
- Bundle assets live under `.opencode/`. A `.claude/`-prefixed path in any asset is drift from the
  sibling `.claude/` bundle — read it as `.opencode/` and report it.
