# Workflow: ingest an unstructured spec into KB candidates

End-to-end from a dropped document to a reviewed set of KB candidates. Staging only — the curated KB is
not touched. Continues in [`review-and-apply-kb-candidates.md`](review-and-apply-kb-candidates.md).

## Preconditions

- An external document converter is available (pandoc / a page-preserving PDF tool / tesseract).
- The project has a `knowledge-base/` (curated collections + `schema/`). If `knowledge-base/candidates/`
  is absent, the ingest command creates it.

## Flow

```
drop <spec>.docx|pdf|md|txt|html
  └─ /stand-test-ingest-spec <spec> --domain <domain> [--mode create-candidates]      [STAGING, dry-run by default]
       1. reader tool -> _source/normalized.md + manifest.json (page/section anchors, sha256)
          (missing tool / unreadable -> report error; NEVER author from memory)
       2. source-document.yml (mechanical documentId; version/date if stated; sourceHash)
       3. section/anchor index
       4. stand-test-spec-extraction: contracts + flows + rules + scenarios + unresolved
          (each item: provenance + redacted quote + confidence + naturalKey)
       5. candidate-schema validation (failures -> skipped)
       6. conflict detection: candidate vs curated KB (kb-lookup) AND candidate vs candidate (cross-doc)
       7. safety-review scan (secret/URL/production) over candidates
       8. write candidates/<document-id>/* (only in create-candidates mode)
       9. extraction-report.md
  == HUMAN REVIEW starts (see the next workflow) ==
```

## Guarantees

- **No curated write.** Only `candidates/<document-id>/` (and `_source/`, gitignored raw) is produced.
- **No hallucination.** Every unstated method/path/topic/table/column is an unresolved item.
- **Sterile.** Candidate files carry no URLs/secrets; the binary stays gitignored.
- **Deterministic.** Mechanical ids, natural keys, sorted files; a re-ingest of the same bytes is
  detected via `sourceHash`.

## Stop conditions

- Reader tool missing or a region unreadable → `needs-tool` / `unparseable-source` report error.
- A document with the same `sourceHash` already ingested → reported as a re-ingest, no duplicate.
- Any candidate failing its schema → `skipped` bucket, never written.

## Next

`/stand-test-review-kb-candidates <document-id>` — the human gate.
