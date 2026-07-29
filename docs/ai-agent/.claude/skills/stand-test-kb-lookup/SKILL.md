---
name: stand-test-kb-lookup
description: Resolve a text test case against the stand-test knowledge base (knowledge-base or docs/ai-agent/knowledge-base layout) into a deterministic KnowledgeBaseLookupResult - matched services/endpoints/topics/datasources/dbProbes/grpcTargets/grpcMethods, missing entries, recorded assumptions. Never invents contract details; no KB entry means a missing item, not a guess. Use right after stand-test-case-analysis, before stand-test-environment-mapping.
version: 1
---

# Skill: stand-test-kb-lookup

Turn the entities named in a case analysis into **knowledge-base entry ids** — or into explicit
`missing` items. This skill is the anti-invention gate of the pipeline: after it runs, every
contract detail the scenario design uses (method, path, response fields, message schema, table,
gRPC method) traces to a KB entry, to the case text, or to a recorded assumption.

## When to use

Right after `stand-test-case-analysis`, before `stand-test-environment-mapping`. The mapping skill
verifies *environment* properties (aliases, correlation, auth, write permissions); this skill
resolves *contracts*.

## Where the KB lives

| Context | Location |
|---|---|
| Consumer project | `knowledge-base/{services,endpoints,kafka,db,grpc,environments,mappings}/*.yml` |
| SDK repo (contract + worked examples) | `docs/ai-agent/knowledge-base/` |

Every file is schema-validated (`knowledge-base/schema/stand-test-knowledge-base.schema.json`).
**Read strategy (scales to large KBs):** read `services/` in full FIRST (it is the smallest
collection and the entry point of the match procedure). Then read child collections
ADDRESSED, not wholesale: by the naming convention `<collection>/<service-id>.yml`
(e.g. `endpoints/tks-client-pckg.yml`) read only the files of matched services, plus any file
whose name does not follow the convention (those must be scanned — do not skip them silently).
Environments and mappings are small — read them in full. On a small KB (≲10 services) reading
everything is fine; the addressed strategy is mandatory beyond that.
**No KB directory at all** is itself a blocking finding: report it and stop; do not fall back to
guessing or to scraping URLs from the case text.

## Procedure

1. **Extract intent** from `TestCaseAnalysis.md`: the trigger operation, the expected effects per
   transport, the entities named (system names, operations, topics, tables, gRPC calls).
2. **Match services** — case-insensitive match of system names against `services/*.yml`
   (`id`, `name`, `tags`, `domain`). Ambiguity (two candidate services) is a `missing` item with
   reason `ambiguous`, never a silent pick.
3. **Match endpoints** — within matched services first (`service.endpoints` rollup), by operation
   semantics: method + path if the case names them, else by `description`/`id` wording. The case
   naming a path that differs from the KB path is a **conflict** → record it under the result's
   `conflicts` section (never under `missing`, never silently resolved) — a human decides.
4. **Match Kafka topics** — via `service.kafkaTopics.produces/consumes` and `kafka/*.yml`
   (`direction: produced` = the case expects an event; `consumed` = the case sends a command).
5. **Match DB probes** — `db/*.yml` `dbProbes` whose `datasourceId` belongs to a matched service
   and whose description/sql covers the expected DB state. A DB check with no matching probe is
   `missing` (a new probe is a KB change a human approves) — do NOT compose ad-hoc SQL. When the
   case implies DB WRITES (seed/cleanup preconditions), also verify the matched datasource has
   `access.mode: write-allowed` and the target schema in `allowedSchemas` — a mismatch is a
   blocking `missing` row (guardrails §"DB write logic"), not a silent design change.
6. **Match gRPC targets and methods** — `grpc/*.yml` by service name / method wording; record
   both the target id and the method id.
7. **Resolve the environment** — the case's environment if named; else the `allowedEnvironments`
   intersection of the matched entries: a single element wins; several elements — take the one
   the project's existing generated tests use, else the integration environment over dev
   (`ift` > `dev`); record any defaulting as an assumption. An empty intersection is a `missing`
   item and the environment stays `null` (blocking).
8. **Emit the result** — fill [`kb-lookup-result-template.yml`](kb-lookup-result-template.yml)
   exactly; write it next to the other pipeline artifacts. Then PROJECT it into `mappings/` as a
   `testCaseMapping` entry (status `draft`) — the mapping schema is narrower than the lookup
   result, so the projection is fixed: carry over `caseId`/`environment`/`matched`/`missing`/
   `assumptions` only; each `conflicts` row becomes a `missing` row with reason
   `case/KB conflict: <detail>`; `confidence` is NOT carried. While `environment` is `null` the
   projection is deferred — resolve the blocking item first.

## Determinism rules

- Same case + same KB ⇒ same result: match by the ordered procedure above, iterate files and
  entries in lexicographic order, never sample.
- Every matched id MUST exist in the KB (copy ids verbatim; never derive or pluralize).
- Anything not found goes to `missing` with a machine-readable reason; anything defaulted goes to
  `assumptions`. Empty `missing` + empty `assumptions` on a sparse case is a red flag, not a win.
- `confidence.overall`: `high` = every transport matched uniquely; `medium` = matches unique but
  some optional detail assumed; `low` = any ambiguity present. `low` confidence or any `missing`
  item that the blocking list of `stand-test-case-analysis` covers ⇒ stop and ask the human.

## Forbidden

- Inventing endpoints, paths, JSON fields, topics, table/column names, SQL, gRPC methods,
  environment ids — the exact failure this skill exists to prevent.
- Treating README/prose/module docs as a contract source when a KB entry exists (the KB wins;
  on KB-vs-runtime conflict, the SDK wins and the KB entry must be fixed via kb-update).
- Editing KB CONTRACT files during lookup (services/endpoints/kafka/db/grpc/environments are
  read-only here; additions go through `stand-test-kb-update`). The single exception is
  `mappings/` — a traceability collection, not a contract: lookup appends/updates the case's
  `testCaseMapping` entry per step 8 (in the umbrella command's `draft` mode the projection is
  shown in the reply instead of written).
- Proceeding to scenario design with unresolved `missing` items of blocking severity.

## Checklist before handing off

- [ ] Every transport effect from the analysis has a matched entry id or a `missing` row.
- [ ] Every matched id verified to exist in the KB files (grep, not memory).
- [ ] Environment resolved or listed as missing; defaulting recorded as an assumption.
- [ ] Result written per template AND projected into a `testCaseMapping` (status `draft`,
      fixed projection from step 8) — unless deferred by a `null` environment or `draft` mode.
- [ ] No new KB CONTRACT entries were created in the process (the mapping projection is the
      only permitted write).
