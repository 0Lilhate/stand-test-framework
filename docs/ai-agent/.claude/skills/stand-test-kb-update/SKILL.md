---
name: stand-test-kb-update
description: Update the stand-test knowledge base from a source of truth (OpenAPI, AsyncAPI, proto, SQL schema/migration, markdown spec, application.yml, or pasted text) - parse, form schema-valid candidate entries, diff deterministically against the existing KB (added/updated/unchanged/conflicts), never delete or silently overwrite, never add secrets or production config. Use via /stand-test-kb-update; dry-run by default.
version: 1
---

# Skill: stand-test-kb-update

Turn an external specification into **schema-valid KB entries** through a deterministic
parse → candidates → validate → diff → report pipeline. The KB is stand configuration:
`apply` changes files only after a human saw the diff, and existing knowledge is never destroyed.

## Inputs

| sourceType | What to extract |
|---|---|
| `openapi` | endpoints (method, path, request/response fields, success status, response id-capture candidates), owning service |
| `asyncapi` | Kafka topics (direction from publish/subscribe, payload fields, headers) |
| `proto` | gRPC targets (service FQN) and unary methods (fullMethodName, message types) |
| `sql` | datasource schema whitelist candidates; probe candidates from table/column DDL |
| `application-yml` | environment entries from an existing `stand.test.environments.*` / registry file (refs only) |
| `markdown` / `text` | any of the above named explicitly in prose - extract ONLY what is stated |
| `auto` | detect by content; on ambiguity, ask instead of guessing |

## Procedure

1. **Parse the source** with the appropriate reader. Unparseable input is a report-level error,
   not an excuse to hand-write entries from memory.
2. **Form candidate entries** in the KB contract — `stand-test-knowledge-base.schema.json`
   (consumer repo: `knowledge-base/schema/`; SDK repo:
   `docs/ai-agent/knowledge-base/schema/`):
   - ids/aliases: stable kebab-case derived from the source name (`POST /api/requests` +
     `operationId createRequest` → `create-request`); derivation is mechanical and documented in
     the report, so re-running produces the same ids;
   - correlation/auth land on the SERVICE entry; real topic names land on ENVIRONMENT entries;
   - fields the source does not state stay ABSENT (no filler defaults) except schema-required
     ones, which become explicit `conflicts`/questions;
   - `valueHints`: derivable only partially — an OpenAPI `format: date`/`date-time` field is a
     date-class candidate (WHICH class — current/future/past — the source cannot tell: report a
     question); `run-unique` is never derivable from a spec — the human enriches hints, absent
     hints leave the field to scenario-design's semantic classification. An id-shaped REQUEST field
     that names a business entity the operation resolves (a client/account/deal id, pin) is an
     `entity-handle` CANDIDATE — propose the hint as a question (the human confirms); it tells
     scenario-design the field must be provisioned+captured, never copied verbatim.
   - `response.captures`: when a create/POST endpoint's RESPONSE schema declares fields that
     identify the created resource (id/uuid/pin/accountId/number/dealId or nested equivalents),
     emit `response.captures` CANDIDATES (JSONPath → suggested variable name) as human-enrichment
     QUESTIONS in the report — the spec cannot state WHICH field is the resource id, so kb-update
     PROPOSES and the human confirms (exactly as `valueHints`/date-class are handled; the JSONPath
     is transferred from the real response schema, never invented). A create-style endpoint whose
     spec response is status-only (no body schema — e.g. a data-mart/vitrina loader) yields NO
     capture candidate and is explicitly reported as "non-provisioning: exposes no capturable
     entity id" so scenario-design (rule 11) / case-analysis (item 7) never treat it as a create
     channel.
3. **Validate every candidate** against the JSON Schema. Invalid candidates go to the report's
   `skipped` section with the validation message - they never reach the KB files.
4. **Diff against the existing KB** per entry id:
   - `added` - id not present;
   - `updated` - id present, only source-derived fields differ AND the existing value was itself
     source-derived (matching an earlier kb-update report) or empty;
   - `conflict` - id present with a manually-authored or diverging value (path changed, field
     renamed, direction flipped) - NEVER auto-resolved;
   - `unchanged` - byte-identical after normalization;
   - `removed-candidate` - present in KB but absent from the source - REPORT ONLY, the entry
     stays (removal is a human act).

   **Source priority (when different source kinds describe the same entry):** machine contracts
   outrank prose - `openapi`/`asyncapi`/`proto`/`sql` (rank 1) > `application-yml` (rank 2) >
   `markdown` (rank 3) > pasted `text` (rank 4). A field written from rank N may be UPDATED only
   by a source of rank ≤ N (same or stronger); a weaker source disagreeing with a stronger one is
   a `conflict`, never an update. Record each entry's source rank in the report's "Derived from"
   column so the next run can apply this rule mechanically; manually-authored values sit above
   rank 1 (only a human changes them).
5. **Write (apply mode only)** deterministically: collections sorted by `id`; entry keys in
   schema property order; 2-space indent; one collection key per file; file naming follows the
   convention `<collection>/<service-id>.yml` (children grouped by owning service — this is what
   lets kb-lookup read large KBs addressed instead of wholesale); an existing non-conventional
   split is preserved, not reshuffled.
6. **Cross-check**: after apply, referential integrity must hold (service rollups list new child
   ids; environments bind new entries or the report lists the binding as a follow-up).
7. **Validate the result**: run the KB validation tests
   (`./gradlew :stand-test-ai-schema:test` in this repo; the consumer's KB check where one
   exists) and re-run the schema over every touched file.
8. **Report** per [`kb-update-report-template.md`](kb-update-report-template.md), then apply
   [`kb-entry-review-checklist.md`](kb-entry-review-checklist.md) to every added/updated entry.

## Hard rules

- **dry-run is the default**; `apply` only on explicit request, and the diff is shown either way.
- Never delete entries; never rewrite an id/alias (tests and mappings reference them).
- Never overwrite a manually-authored field without a `conflict` entry a human resolved.
- No secrets, tokens, URLs, hosts, JDBC strings — even if the source contains them: server
  URLs from an OpenAPI `servers:` block become env-var ref NAMES (proposed, human-approved),
  never values.
- No production anything: a source describing a production environment yields NO environment
  entry, only a report warning.
- No entries invented beyond the source: kb-update transfers knowledge, it does not author it.

## Checklist before handing off

- [ ] Every candidate passed the JSON Schema (failures listed under `skipped`).
- [ ] Create-style endpoints with an id-bearing response schema have proposed `response.captures`
      (or are flagged "non-provisioning: no capturable entity id"); id-shaped request handles have a
      proposed `valueHints: entity-handle`.
- [ ] Diff lists every touched entry under exactly one of added/updated/unchanged/conflicts.
- [ ] No deletions; no id/alias renames; conflicts left unresolved in the KB files.
- [ ] Secret/URL scan of the diff is clean; no production environments introduced.
- [ ] KB validation tests green after apply.
