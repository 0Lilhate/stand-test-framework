# stand-test knowledge base (KB)

A **schema-validated YAML knowledge base** of everything an AI agent may reference when turning a
text test case into a stand-test autotest: services, endpoint contracts, Kafka topics, datasources,
DB probes, gRPC targets/methods and environment bindings. It exists for one reason:

> **The agent never invents endpoints, topics, DB queries, gRPC methods or environment config.**
> Every contract detail in a generated test traces to a KB entry, to the case text, or to an
> explicitly recorded, human-visible assumption. No KB entry → `missing` → a blocking question —
> never a guess. A plausible-but-wrong path that compiles and reaches review is the exact failure
> mode this KB closes.

The KB stores **aliases, contracts and env-var references** — never secret values, never URLs,
hosts, ports, JDBC strings, never production configuration. The JSON Schemas make those
unrepresentable in every contract and identity field (`additionalProperties: false`, ref-shape
patterns, anti-URL guards on paths/names/labels/descriptions, `select`-only SQL, bounded
timeouts, production-looking environment ids rejected wherever an environment is referenced);
assertion VALUES are data and are covered by the validation tests' string scan and by review
rather than by pattern.

## Where it lives

- **This directory** ships the contract: schemas, worked examples, per-entity guides.
- **A consumer project** keeps its real KB at `knowledge-base/` with the same
  layout (`services/`, `endpoints/`, `kafka/`, `db/`, `grpc/`, `environments/`, `mappings/`).
  Copy this directory's layout, replace the examples with real entries, keep the schemas.

## Layout and file contract

Every KB file is a YAML document with **exactly one collection key** validated by
[`schema/stand-test-knowledge-base.schema.json`](schema/stand-test-knowledge-base.schema.json)
(draft 2020-12; the thin per-entity `*.schema.json` files are `$ref` pointers into it):

| Directory | Collection key | Entity | Schema |
|---|---|---|---|
| `services/` | `services` | owning service: correlation header, auth refs, rollup lists | `schema/service.schema.json` |
| `endpoints/` | `endpoints` | one HTTP operation: method, path, fixture, captures, assertions | `schema/endpoint.schema.json` |
| `kafka/` | `kafkaTopics` | topic contract: direction, HEADER correlation, equals-only assertions, timeout | `schema/kafka-topic.schema.json` |
| `db/` | `datasources` | datasource whitelist entry: access mode, schema whitelist | `schema/datasource.schema.json` |
| `db/` | `dbProbes` | sanctioned single-value `select` probes with named `:params` | `schema/db-probe.schema.json` |
| `db/` | `dbTables` | table contract: schema-qualified name, columns, primary key, testRunId tag column | `schema/db-table.schema.json` |
| `grpc/` | `grpcTargets` | target + unary methods, METADATA correlation, mandatory deadline | `schema/grpc-target.schema.json` |
| `environments/` | `environments` | env-var **references** + real per-env topic names | `schema/environment.schema.json` |
| `mappings/` | `testCaseMappings` | traceability: case → matched entries → generated test | `schema/test-case-mapping.schema.json` |

Conventions (schema-enforced where possible): camelCase field names; kebab-case ids/aliases;
lowercase canonical SQL; `*Ref` fields carry env-var **NAMES** (`^[A-Z][A-Z0-9_]{2,63}$` — a URL,
a token or a `Bearer` value cannot even be spelled); bounded timeouts (`ms`/`s`/`m`, runtime cap
1 h); fixture paths are relative, `..`-free.

**Conventions for large KBs (mandatory beyond ~10 services):** one file per owning service,
named `<collection>/<service-id>.yml` (e.g. `endpoints/tks-client-pckg.yml`) — this lets
`stand-test-kb-lookup` read the KB addressed (services first, then only the matched services'
files) instead of loading everything; every service entry carries `domain` and at least one
`tags` value so name-ambiguous cases ("client service" among three client-* services) resolve
by tag/domain instead of a question to the human; `mappings/` history doubles as a
disambiguation corpus — keep it in the repo.

## How the KB maps onto the SDK

The KB mirrors the `EnvironmentRegistry` model — on any conflict **the SDK wins**:

- correlation header / auth identity are **per service** (registry `correlation:`/`auth:`), not per
  endpoint; two identities against one service = two service entries;
- Kafka correlation is HEADER-only; Kafka/gRPC assertions are **equals-only** (the schema cannot
  express what the adapters cannot check);
- real topic names are per-environment `actualName` values → the registry's literal `name:` field;
- environment entries hold `*Ref` names that `/stand-test-generate-env` renders into
  `stand-test-environments.yml` (refs-only) or `application.yml` `stand.test.environments.*`;
- destructive SQL is not re-declared here: `ForbiddenOperation` in `stand-test-core` stays the
  single source of truth; the probe schema's `select`-only pattern is defense-in-depth.

## Workflows that use the KB

| Task | Entry point |
|---|---|
| Find entries for a text case | skill [`stand-test-kb-lookup`](../.claude/skills/stand-test-kb-lookup/SKILL.md) — deterministic `KnowledgeBaseLookupResult`, unknowns become `missing`, never guesses |
| Add/refresh entries from a spec (OpenAPI/AsyncAPI/proto/SQL/markdown/application.yml) | command [`/stand-test-kb-update`](../.claude/commands/stand-test-kb-update.md) + skill [`stand-test-kb-update`](../.claude/skills/stand-test-kb-update/SKILL.md) |
| Render environment config from the KB | command [`/stand-test-generate-env`](../.claude/commands/stand-test-generate-env.md) + skill [`stand-test-env-generation`](../.claude/skills/stand-test-env-generation/SKILL.md) |
| Text case → Java autotest (end to end) | command [`/stand-test-generate-java-test`](../.claude/commands/stand-test-generate-java-test.md) |
| Review an existing generated test against the KB | command [`/stand-test-review-generated-test`](../.claude/commands/stand-test-review-generated-test.md) |

## Adding a new entry (endpoint / topic / probe / gRPC method)

1. Prefer `/stand-test-kb-update` with the source spec — hand-written entries drift.
2. One entity, one entry; ids and aliases stable kebab-case, never renamed casually (mappings and
   generated tests reference them).
3. Keep both directions consistent: the owning `service` entry lists the child id, the child names
   its `serviceId`/`datasourceId` (validation cross-checks this).
4. Add the environment binding (`environments/*.yml`) for every `allowedEnvironments` entry —
   an entry without a binding cannot run anywhere.
5. Validate before committing. In THIS repo: the KB validation tests in `stand-test-ai-schema`
   (`./gradlew :stand-test-ai-schema:test`) check schema conformance, referential integrity and
   scan every string for secret/URL shapes. In a CONSUMER repo: copy
   `stand-test-ai-schema/src/test/java/ru/alfa/stand/test/ai/KnowledgeBaseSchemaValidationTest.java`
   as a starting point (test deps: `com.networknt:json-schema-validator` + `jackson-databind` +
   `org.yaml:snakeyaml`), point it at `knowledge-base/`, and keep it in the
   regular test run; until that test exists, the schema check is manual and
   [`kb-entry-review-checklist.md`](../.claude/skills/stand-test-kb-update/kb-entry-review-checklist.md)
   is the gate.
6. A KB change is stand configuration — a HUMAN approves it, like a registry addition.

## Secrets: what may never appear here

Only env-var **names** (`passwordRef: CLIENT_DB_PASSWORD`). Never values, never `${VAR:default}`
defaults for credentials, never tokens, never `Basic`/`Bearer` strings, never JDBC URLs, never
`host:port`. The schemas reject value-shaped strings; the validation tests scan every string field
as a second net; the safety review greps generated artifacts as a third.

## Deterministic merge (kb-update / env-generation)

Same inputs → byte-identical outputs: entries and keys are emitted in a fixed order (collections
sorted by `id`; within an entry, schema property order); existing entries are never deleted and
manually-authored fields are never overwritten without an explicit `conflict` in the report;
`dry-run` is the default mode and `apply` writes only after the diff is shown. See the
[`stand-test-kb-update` skill](../.claude/skills/stand-test-kb-update/SKILL.md) for the exact
algorithm and report format.
