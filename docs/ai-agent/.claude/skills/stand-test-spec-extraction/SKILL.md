---
name: stand-test-spec-extraction
description: The interpretive extraction rules for turning normalized spec text/tables/figures into schema-valid stand-test KB candidates - how to find REST endpoints, Kafka topics, DB tables/probes, gRPC methods, business flows and business rules, how to assign provenance and a high/medium/low confidence tier, and how to emit an UNRESOLVED item instead of hallucinating a missing method/path/topic/table/column. Used by stand-test-spec-ingestion; produces candidate files only.
version: 1
---

# Skill: stand-test-spec-extraction

The interpretive workhorse of ingestion. Given the normalized document (from
[`stand-test-spec-ingestion`](../stand-test-spec-ingestion/SKILL.md) Stage 0) and its anchor index,
emit **schema-valid candidates** for every contract, flow, rule and scenario the document STATES, and
an **unresolved item** for everything it merely implies. Governing rule: **extract only what is
stated; a missing contract detail is an unresolved item, never a guess.**

## Output contract (per candidate)

Every emitted item carries:

- `source` — provenance: `documentId`, `documentName`, `documentVersion`, `page`, `section`,
  `heading`, and a **redacted verbatim `quote`** (host placeholders, secrets stripped; the true
  verbatim stays in gitignored `_source/`). `documentId` + `documentName` + `quote` are mandatory.
- `confidence` — see the rubric below.
- a `naturalKey` — the category dedup key, so a re-worded extraction of the same real thing does not
  duplicate on promotion.

Validate against the candidate schemas (`knowledge-base/schema/*.schema.json`). Never route a candidate
through the strict `stand-test-knowledge-base.schema.json`.

## Confidence rubric

| Tier | Meaning |
|---|---|
| `high` | Exact name/path/table/topic/status stated **verbatim** in a machine-readable span (fenced block, config/table cell, explicit inline literal). |
| `medium` | Strongly implied — named nearby with a single obvious reading, or assembled from two adjacent statements needing light normalization. |
| `low` | Inferred from narrative prose, a diagram/figure, or OCR. **Never promotion-eligible.** |

Routing: `high`/`medium` → the category candidate file; `low` → `unresolved.candidates.yml` only.
Confidence ≠ completeness: a `high` candidate that is still missing a schema-required field carries a
non-empty `gaps`/`unresolved` reference and is not promotable until the gap is resolved.

## Extraction rules by category

### REST endpoints (`candidateType: endpoint`)
- Look in: endpoint/API tables, request/response sections, "REST"/"HTTP" headings, fenced request lines.
- Capture into `extracted`: `method`, `path` (relative, leading slash — reject any `://`), `successStatus`,
  `requiresAuth`, `requiredFields`, `serviceAlias`. Purpose/description → `normalized.title/description`.
- `naturalKey`: `{ method, path, serviceAlias }`.
- If the method or path is not stated → **unresolved** `missing-http-method` / `missing-endpoint-path`.
  Never fabricate a path from the operation name.
- **Provisioning signals** (candidate hints, human-confirmed on promotion, NEVER auto-authored): a
  create/POST whose RESPONSE names id-bearing fields (id/uuid/pin/accountId/number/dealId) → propose
  `response.captures` CANDIDATES (JSONPath → variable) so the entity it mints is capturable; a create
  whose response is status-only (no body) → flag "non-provisioning: no capturable entity id" so
  mapping never treats it as a create channel. An id-shaped REQUEST field that names a business entity
  the operation resolves (client/account/deal id, pin) → propose `valueHints: entity-handle` for that
  field. All three are QUESTIONS (the spec cannot say which field is THE id — the JSONPath/name is
  transferred from the real schema, never guessed).

### Kafka topics/events (`candidateType: kafka-topic`)
- Look in: "events"/"messaging"/"Kafka" sections, "publishes"/"consumes"/"subscribes" verbs.
- `direction`: `produced` (system under test emits — the test does `kafka.expect`) from "publishes/emits";
  `consumed` (test sends) from "consumes/subscribes". `correlationHeader` from a named header. Payload
  field names → `extracted`/`normalized`.
- `naturalKey`: `{ alias }`. The **real topic name is per-environment** (`actualName`) — do NOT put a
  concrete broker topic name in the candidate; use a logical alias.
- If the topic is only described by role and no alias can be formed → **unresolved** `missing-topic-name`.
- The bounded await timeout is almost never in the doc → an `unresolved` `no-curated-collection`
  (REST endpoint has no KB timeout home) or an assumption at scenario-design, never invented here.

### DB (`candidateType: datasource | db-probe | db-table`)
- Datasource: the logical store alias + access intent (readonly / write-allowed). Never a JDBC URL.
- db-probe: a read `SELECT` the doc describes (status/row checks). Single-statement, lowercase.
- db-table (**no curated home yet**): schema-qualified `table`, `columns`, the `tagColumn`
  (`test_run_id`-style) db.seed needs, `datasourceAlias`. Set `promotionBlocked: curated-collection-missing`.
- `naturalKey`: probe = `{ alias }`; table = `{ table }`.
- A table/column/probe not named in the doc → **unresolved** `missing-table-name` / `missing-column`.
  Never compose ad-hoc SQL or invent a column.

### gRPC (`candidateType: grpc-target`)
- Capture the service (proto FQN), the fully-qualified method (`pkg.Service/Method`), correlation
  metadata key, request/response message hints. `naturalKey`: `{ fullMethodName }`.
- Method not stated → **unresolved**; never invent a method name.

### Business flows (`business-flow.schema.json`)
- Trigger → ordered `steps` (order/actor/action/target/expectedEffect) → `expectedResult` +
  `failureBehavior` + `parallelism`. Link `relatedArtifacts` to endpoint/topic/dbTable/grpc candidate ids.
- These are SEMANTIC — they feed `stand-test-scenario-design`, they never enter the curated contract.

### Business rules (`business-rule.schema.json`)
- `condition` → `action`, plus `exceptions`, `priority`, `appliesTo`, and `testImplications`
  (positive/negative cases). These become the expected values and negative paths of a test.

### Test scenarios (`test-scenario-candidate.schema.json`)
- Pre-fill `goal`, `preconditions`, `testData`, `action`, `expected` (rest/kafka/db/grpc), `assertions`,
  `cleanup`, `risks`, and `kbDependencies` (KB ids the scenario needs). These feed
  `stand-test-case-analysis`; they are NEVER auto-promoted to a `testCaseMapping` — a human authors the test.

## Unresolved instead of hallucination

Emit an `unresolved` item (`unresolved-item.schema.json`) whenever the document does not state a needed
fact. Pick the precise `type` (`missing-endpoint-path`, `missing-http-method`, `missing-topic-name`,
`missing-table-name`, `missing-column`, `missing-env`, `unclear-business-rule`, `no-curated-collection`,
`low-confidence-inference`, `unparseable-source`, `conflict`, `other`), record the `question`,
`context`, `blockingFor`, a `suggestedResolution`, and the provenance quote. This is the single most
important rule of the skill: **an unstated contract detail is always an unresolved item.**

## Sterility & redaction

Before writing any candidate: replace URLs/hosts in quotes with placeholders (`<host>/v1/...`), strip
any credential (`Authorization: <redacted>`), and keep the true verbatim only in gitignored `_source/`.
The candidate schemas reject `://`/JDBC and inline secret shapes on every free-text field, so an
un-redacted candidate fails validation and lands in `skipped`.

## Templates

[`business-flow-candidate-template.yml`](business-flow-candidate-template.yml),
[`business-rule-candidate-template.yml`](business-rule-candidate-template.yml),
[`test-scenario-candidate-template.yml`](test-scenario-candidate-template.yml),
[`unresolved-item-template.yml`](unresolved-item-template.yml). The contract candidate shape
(`extraction-candidate`) is documented in `knowledge-base/candidates/README.md`.
