# knowledge-base/candidates/ — spec-ingestion staging layer

This tree is the **candidate-first staging layer** for populating the knowledge base from
**unstructured** documents (PDF/DOCX/ФС/BRD/ТЗ/Confluence export). Nothing here is the curated KB.
Extracted knowledge lands here first, is reviewed by a human, and only an approved subset is promoted
into the sterile curated collections through `stand-test-kb-update`.

## Why a separate layer

The curated KB (`../schema/stand-test-knowledge-base.schema.json`) is a **closed, sterile contract**:
`additionalProperties: false`, aliases/refs/contracts only, no secrets, no URLs, no production config.
It cannot represent what ingestion produces — provenance, a confidence tier, partial/annotated
entries, business flows/rules, conflicts and open questions. So candidates get their **own permissive
schemas** (`../schema/*.schema.json`, listed below) and are **NEVER validated against the strict
umbrella schema**. Promotion is the single point where the projectable subset is lifted, provenance is
stripped, and the result is re-validated against the strict schema before `kb-update` writes it.

> Build-safety: the pinned `KnowledgeBaseSchemaValidationTest` validates a **fixed allowlist** of
> curated example files and never globs this tree, so a candidate can never enter the strict curated
> validation. `KbCandidateSchemaValidationTest` validates the candidate schemas against classpath
> fixtures AND globs every committed `candidates/**/*.yml` file, mapping each to its schema by top-level
> shape and running the sterility (URL/JDBC/secret) scan — so a malformed, provenance-missing, or
> secret-carrying committed candidate fails the build. Every committed file now has a schema, including
> the semantic `glossary.candidates.yml`, the human `review-decisions.yml`, and the `promotion-log.yml`
> ledger (their own permissive schemas below).

## Candidate schemas (in `../schema/`)

| Schema | File shape | Holds |
|---|---|---|
| `source-document.schema.json` | `{ document: {...} }` | one ingested document (id, name, type, version, hash, status) — the provenance root |
| `extraction-candidate.schema.json` | `{ candidates: [...] }` | the general per-candidate contract (service/endpoint/kafka/datasource/db-table/db-probe/grpc + unresolved) |
| `business-flow.schema.json` | `{ businessFlows: [...] }` | end-to-end flows (trigger → steps → result) — semantic, no curated home |
| `business-rule.schema.json` | `{ businessRules: [...] }` | conditional rules / acceptance criteria — semantic, no curated home |
| `test-scenario-candidate.schema.json` | `{ testScenarios: [...] }` | candidate autotest intents — feed case-analysis, never auto-promoted |
| `unresolved-item.schema.json` | `{ unresolved: [...] }` | gaps / open questions — the anti-hallucination sink |
| `conflict-item.schema.json` | `{ conflicts: [...] }` | value disagreements (candidate vs curated / candidate vs candidate) — never auto-resolved |
| `glossary.schema.json` | `{ documentId, source, abbreviations?, terms?, aliases? }` | domain vocabulary digest (file-level provenance) — feeds scenario-design, never promoted |
| `review-decisions.schema.json` | `{ documentId, reviewedOn, reviewedBy, <family>: {...} }` | the human per-family review dispositions (approved/held/promoted) — a review artifact, not promotable |
| `promotion-log.schema.json` | `{ promotions: [...] }` | the append-only curated-promotion ledger (root `candidates/promotion-log.yml`) — an ops record |

## Per-document layout

```
candidates/
  <document-id>/                         # == source-document.documentId (kebab)
    _source/
      raw/                               # GITIGNORED: original .docx/.pdf/exports (never committed)
      normalized.md                      # converter output (structure-preserving) — local only
      manifest.json                      # page/section/table/figure anchors, sha256
    source-document.yml                  # { document: {...} } — the provenance root
    endpoints.candidates.yml             # { candidates: [...] } (candidateType: endpoint)
    kafka.candidates.yml
    datasources.candidates.yml
    db-probes.candidates.yml
    db-tables.candidates.yml             # curated home now exists (dbTables collection); still blocked per row when schema/table unresolved
    services.candidates.yml
    grpc.candidates.yml
    business-flows.candidates.yml
    business-rules.candidates.yml
    test-scenarios.candidates.yml
    unresolved.candidates.yml
    conflicts.candidates.yml
    extraction-report.md                 # human review surface
promotion-log.yml                        # curatedId -> last {documentId, documentVersion, documentDate, promotedAt}
```

Consumer projects receive only `knowledge-base/schema/` and the `.claude/` bundle — the ingestion
skill creates `knowledge-base/candidates/` on first run, exactly as `stand-test-kb-update` creates
collection directories.

## Invariants (enforced by schema + skills)

- **Provenance required** — every candidate/flow/rule/scenario/unresolved/conflict item carries a
  `source` block (documentId + documentName + quote mandatory). No item without provenance.
- **Confidence required** — `high | medium | low`. `low` is never applied automatically; `medium`
  needs an explicit human tick; `high` is eligible only after human review. **Every apply is human-gated.**
- **Sterile** — every free-text field rejects URLs/JDBC (`noUrl`) and inline secrets (`secretShape`).
  Quotes are redacted (host placeholders, secrets stripped); the verbatim source stays in gitignored `_source/`.
- **Unresolved, never invented** — a missing method/path/topic/table/column becomes an `unresolved`
  item, never a guessed value.
- **Conflicts never auto-resolved** — `autoResolvable: false`, `whoDecides: human`.
- **Deterministic** — mechanical kebab ids, natural-key dedup, sorted keys, dry-run by default; the
  actual curated write is done by `stand-test-kb-update` only.

## Workflow

`/stand-test-ingest-spec` → `/stand-test-review-kb-candidates` → `/stand-test-apply-kb-candidates`.
See `../../.claude/workflows/ingest-unstructured-spec-to-kb.md` and
`review-and-apply-kb-candidates.md`.
