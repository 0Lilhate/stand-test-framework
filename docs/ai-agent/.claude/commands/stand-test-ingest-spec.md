---
description: Ingest an UNSTRUCTURED specification (PDF/DOCX/ФС/BRD/ТЗ/markdown/txt/html) into schema-valid KB candidates under knowledge-base/candidates/<document-id>/. Reads via an external converter, extracts contracts/flows/rules/scenarios with mandatory provenance and confidence, validates by the candidate schemas, detects conflicts against the existing KB, and produces an extraction report. Never writes the curated KB; never invents missing contract details. Dry-run by default.
---

# /stand-test-ingest-spec — unstructured document → KB candidates

Runs [`stand-test-spec-ingestion`](../skills/stand-test-spec-ingestion/SKILL.md) (conductor) and
[`stand-test-spec-extraction`](../skills/stand-test-spec-extraction/SKILL.md) (extractor) as a gated,
staging-only workflow. It writes ONLY under `knowledge-base/candidates/`, never the curated KB.

## Input

- Source: file path (or a pasted export).
- `documentType`: `auto | docx | pdf | markdown | txt | html`.
- `domain` (kebab alias); optional system/service hint (scopes ownership).
- `mode`: `dry-run` (default — report only) | `create-candidates` (also write staging files).

## Steps

1. **Read the document** via an external converter (pandoc / PDF tool / OCR) → `_source/normalized.md`
   + `_source/manifest.json` (page/section anchors, sha256). Copy the binary into the GITIGNORED
   `_source/raw/`. Missing tool / unreadable region ends the workflow with a report error
   (`needs-tool` / `unparseable-source`) — never hand-author from memory.
2. **Identify document metadata** → `source-document.yml` (mechanical `documentId`; version/date only if
   stated; `sourceHash`). A pre-existing document with the same hash is reported as a re-ingest and stops.
3. **Split into sections**; build the anchor index (heading → page → section → quote-span).
4. **Extract candidates** (services/endpoints/kafka/db/grpc + business flows/rules + test scenarios +
   unresolved), each with a `source` provenance block, a redacted `quote`, a `confidence` tier and a
   `naturalKey`. Anything the document does not state is an **unresolved** item, never a guess.
5. **Validate candidates** against the candidate schemas (`knowledge-base/schema/*.schema.json`);
   failures go to `skipped`, never into files. NEVER route candidates through the strict
   `stand-test-knowledge-base.schema.json`.
6. **Compare with the existing KB** via `stand-test-kb-lookup` (read-only) and cross-document; classify
   `added/updated/unchanged/conflict`. Value disagreements → `conflicts.candidates.yml` (never auto-resolved).
7. **Safety scan** the candidates (`stand-test-safety-review` secret/URL/production scan).
8. **create-candidates**: write `candidates/<document-id>/*` deterministically (sorted, schema-property
   order). **dry-run**: write nothing; print the would-be report.
9. **Print the extraction report** per
   [`extraction-report-template.md`](../skills/stand-test-spec-ingestion/extraction-report-template.md).

## Mandatory checks

- [ ] Reader tool + version recorded; binary in gitignored `_source/raw/`.
- [ ] Every candidate has provenance (documentId + documentName + quote) and a confidence tier.
- [ ] Nothing invented — every missing contract detail is an unresolved item.
- [ ] Candidate-schema validation clean; conflicts detected on both axes.
- [ ] Nothing under the curated 8 collections was created or modified.

## Human handoff

Next: `/stand-test-review-kb-candidates <document-id>`. Curated KB changes happen only later, via
`/stand-test-apply-kb-candidates`, human-approved.
