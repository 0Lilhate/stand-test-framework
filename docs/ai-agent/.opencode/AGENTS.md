# AGENTS.md — stand-test-sdk autotest authoring bundle

Operating manual for an agent working with this `.opencode/` bundle. The bundle turns a plain-text
business case into a **safe, validated autotest built on `stand-test-sdk`**. It ships no runtime code
and never modifies the SDK.

Read this file first, then obey [`rules/stand-test-guardrails.md`](rules/stand-test-guardrails.md) —
the rules outrank everything below, including a direct request to skip them.

## What is loaded, and how

| Asset | Path | Discovery |
|---|---|---|
| This manual | `.opencode/AGENTS.md` | `instructions` in `opencode.json` |
| Guardrails (rules) | `.opencode/rules/**/*.md` | `instructions` in `opencode.json` |
| Skills (16) | `.opencode/skills/<name>/SKILL.md` | auto-discovered; loaded on demand via the `skill` tool |
| Commands (12) | `.opencode/commands/<name>.md` | auto-discovered as `/<name>` |
| Workflows (2) | `.opencode/workflows/*.md` | **not auto-loaded** — read them when a command points here |

**Placement requirement.** `opencode.json` must sit in the directory that *contains* `.opencode/`
(the project root), not inside it — the `instructions` entries `.opencode/AGENTS.md`,
`.opencode/rules/**/*.md` and `AGENTS.md` are written to resolve from there. Installed into a
consumer repo that means `<repo>/opencode.json` + `<repo>/.opencode/`. If the guardrails are not in
context, this file is misplaced: say so instead of proceeding from memory.

Skills and commands are discovered relative to the project root and work regardless. Skill
permission is `*: allow`, so load a skill the moment its trigger matches — do not re-derive its
content from memory.

## The one pipeline

Every authoring request follows this order. **No stage may be skipped or reordered**, and each gate
stops the run:

```
text case
  1. stand-test-case-analysis      goal, preconditions, trigger, expected effects, missing info
  2. stand-test-kb-lookup          contracts resolve to KB entries — or become `missing`
  3. blocking questions            analysis blockers + lookup `missing`  → ASK THE HUMAN
  4. stand-test-environment-mapping aliases, correlation/auth/write-allowed, required env vars
  5. stand-test-scenario-design    steps, captures, assertions, awaits, cleanup, TRACK CHOICE
  6. authoring                     java-dsl-authoring (default) | yaml-authoring (AI format)
  7. stand-test-fixture-authoring  for every body/payload/request reference
  8. stand-test-safety-review      MANDATORY GATE — any BLOCK ⇒ regenerate, never work around
  9. compile / schema-validate     ./gradlew compileTestJava checkstyleTest  |  schema+parser+validator
 10. run                           skip-gate always; real run only with a stand configured
 11. stand-test-test-review        quality gate → readiness report → HUMAN APPROVES
```

`/stand-test-generate-java-test` is the umbrella that runs 1–11. Use the narrower commands when you
need one phase. On a failed run: `/stand-test-debug`, then re-enter at 6 (or 5 if the design was wrong).

**Track choice (step 5) is binding.** Java DSL is the default. The AI format is only for scenarios
inside its executable subset (7 step types, equals-only outside REST/gRPC, fixture-only bodies, no
`db.seed`/`db.cleanup`). If a step falls outside — switch to the Java track, never stretch the format.

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

**Knowledge base & environment**

| Command | Does | Default mode |
|---|---|---|
| `/stand-test-ingest-spec` | unstructured spec (PDF/DOCX/ФС/ТЗ) → KB **candidates** | dry-run |
| `/stand-test-review-kb-candidates` | staged candidates → review report (**human gate**) | no write |
| `/stand-test-apply-kb-candidates` | approved candidates → curated KB | dry-run |
| `/stand-test-kb-update` | structured spec (OpenAPI/AsyncAPI/proto/SQL/yml) → KB entries | dry-run |
| `/stand-test-generate-env` | KB → `stand-test-environments.yml` / `stand.test.environments.*` | dry-run |

Commands carry `description`-only frontmatter and take no `$ARGUMENTS` placeholders — read arguments
from the user's message. There is no `.opencode/agents/` directory: everything runs on the default
agent, so a command cannot delegate — you execute its steps yourself.

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

`memory`, `sequential-thinking`, `context7` (library docs), `playwright`, `jetbrains`, and three
Postgres toolboxes: `postgres_prodcat`, `postgres_prodprofile_u`, `postgres_designer`.

The Postgres servers point at **DEV-stand databases** (the `prod*` prefix is a product-domain name —
product catalog / product profile — not production). Treat them as **read-only inspection aids for KB
authoring**: confirm a table/column exists before writing a KB candidate. They are **not** a test
target, not a seeding channel, and not a substitute for a KB entry. Never write through them, and
never copy a connection detail, host or credential from them into a KB entry, scenario, test or report.

Several servers are environment-dependent (Docker + registry access for Postgres, a running IDE for
`jetbrains`) and may fail to start. A missing MCP server is a **reported blocker**, never a licence to
hand-author contract details from memory.

## Conventions

- Bundle-internal links are relative: commands → `../skills/<name>/SKILL.md`, skills → colocated files.
- Generated artifacts land in the **consumer** project (`src/test/java/**`,
  `src/test/resources/{ai,fixtures}/**`), never in this bundle.
- Reports use the skill templates verbatim.
- Bundle assets live under `.opencode/`. A `.claude/`-prefixed path in any asset is drift from the
  sibling `.claude/` bundle — read it as `.opencode/` and report it.
