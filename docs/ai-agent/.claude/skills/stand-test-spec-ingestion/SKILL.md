---
name: stand-test-spec-ingestion
description: Ingest an UNSTRUCTURED specification (PDF/DOCX/ФС/BRD/ТЗ/markdown/txt/html) into schema-valid KB CANDIDATES under knowledge-base/candidates/<document-id>/ - read via an external converter, split into sections, extract contracts/flows/rules/scenarios with mandatory provenance and confidence, validate against the candidate schemas, detect conflicts against the existing KB, and produce an extraction report. NEVER writes the curated KB and never invents missing contract details. Use via /stand-test-ingest-spec; dry-run by default.
version: 1
---

# Skill: stand-test-spec-ingestion

Turn an unstructured specification into **schema-valid staging candidates** through a deterministic
`read → section → extract → validate → conflict-detect → report` pipeline. This skill is the
**conductor**: it owns the document-reader boundary and orchestrates
[`stand-test-spec-extraction`](../stand-test-spec-extraction/SKILL.md) (the interpretive workhorse).
It never touches the curated KB — promotion is a separate, human-gated step
([`stand-test-kb-candidate-apply`](../stand-test-kb-candidate-apply/SKILL.md)).

## When to use

When a human drops a spec document and wants its contents captured in the knowledge base. Run it
BEFORE `stand-test-case-analysis`/`stand-test-kb-lookup` when the KB does not yet cover the systems the
document describes. For a STRUCTURED source of truth (OpenAPI/AsyncAPI/proto/SQL/application.yml) use
`stand-test-kb-update` instead — this skill is for prose/binary documents that name contracts in text.

## Inputs

- Source file path (or a pasted export). `documentType`: `auto | docx | pdf | markdown | txt | html`.
- `domain` (kebab alias) and an optional system/service hint (scopes ownership).
- `mode`: `dry-run` (default — report only) | `create-candidates` (also write the staging files).

## Pipeline

1. **Read the document (Stage 0 — external tool, not from memory).** The agent cannot reliably parse
   binary `.docx`/`.pdf` bytes, and the SDK runtime must never gain a parsing dependency (core is
   JDK-only/no-IO). Use an external converter that PRESERVES structure and page/section anchors:
   `pandoc` (docx/html/md), a page-preserving PDF tool (`pdftotext`/`pdfplumber`/`docling`),
   `tesseract` (scans/image-only pages). Produce `_source/normalized.md` + `_source/manifest.json`
   (page map, section/table/figure anchors, `sha256`). Copy the original into the **gitignored**
   `_source/raw/`. If the tool is missing or a region is unreadable, STOP with a report error
   (`needs-tool` / `unparseable-source`) — **never hand-author entries from memory.**
2. **Identify document metadata** → a `source-document.yml` record (`documentId` = mechanical kebab of
   the file name/title; `documentType`; `documentVersion`/`documentDate` only if the document states
   them, else `null`; `sourceHash` = `sha256:` of the raw bytes for repeated-ingest detection). If a
   `source-document.yml` with the SAME `sourceHash` already exists, report it and stop (re-ingest).
3. **Split into sections.** Build the anchor index from the manifest: heading → page → section-id →
   quote-span. This is the provenance backbone every candidate cites.
4. **Extract candidates** by delegating to `stand-test-spec-extraction`. Categories: services,
   endpoints, kafka topics, datasources, db-probes, db-tables, grpc targets, business flows, business
   rules, test scenarios, and **unresolved items** for everything the document does not state. Every
   item gets a `source` provenance block (with a redacted verbatim `quote`) and a `confidence` tier.
5. **Validate candidates by schema** against the candidate schemas in `../../../knowledge-base/schema/`
   (consumer repo: `knowledge-base/schema/`): `extraction-candidate`, `business-flow`, `business-rule`,
   `test-scenario-candidate`, `unresolved-item`, `conflict-item`, `source-document`. A candidate that
   fails validation goes to the report's `skipped` bucket — it never reaches a candidate file.
6. **Compare with the existing KB.** Resolve each candidate against the curated KB with
   `stand-test-kb-lookup` (read-only) and classify with the `kb-update` diff vocabulary:
   `added / updated / unchanged / conflict`. Also diff candidate-vs-candidate across every document
   under `candidates/` (cross-document). Genuine value disagreements go to `conflicts.candidates.yml`
   (never auto-resolved).
7. **Create candidate files** under `candidates/<document-id>/` — ONLY when `mode=create-candidates`.
   One file per category (created on demand), sorted by id, schema-property order, 2-space indent.
8. **Produce the extraction report** per
   [`extraction-report-template.md`](extraction-report-template.md): counts per bucket, a confidence
   split, per-item `Derived from` (documentId §section p.page), conflicts, skipped, unresolved, a
   `KB schema gaps surfaced` section (facts with no curated home), a `Non-KB semantic content` section,
   the validation result, and Next actions pointing at `/stand-test-review-kb-candidates`.

## Forbidden (BLOCK)

- Writing anything under the curated 8 collections (`services/endpoints/kafka/db/grpc/environments/
  mappings` and any new curated collection). Ingestion writes ONLY under `candidates/`.
- Inventing an endpoint path, HTTP method, topic name, table/column, or gRPC method the document does
  not state — that is an `unresolved` item, never a guessed value.
- Committing a live URL/host or a secret. Payload/identity fields reject URLs by schema; quotes are
  redacted (host placeholders, secrets stripped) before writing; the raw binary stays in gitignored
  `_source/raw/`.
- Adding a production environment or URL to any environment candidate (refs/aliases only).
- Deleting or overwriting any existing KB entry (that only happens on promotion, human-approved).

## Determinism rules

- `documentId` and every candidate id are mechanical kebab derivations of a source token — re-running
  on the same document yields the same ids and files.
- Candidates carry a `naturalKey` (method+path / alias / table / fullMethodName); conflict detection
  and later promotion join on the natural key, not the derived id, so a re-worded extraction of the
  same real thing does not duplicate.
- `dry-run` is the default; `create-candidates` writes staging files only; the curated KB is never
  touched here.

## Checklist before handing off

See [`unstructured-spec-ingestion-checklist.md`](unstructured-spec-ingestion-checklist.md). In short:
reader tool + version recorded; every candidate has provenance + confidence; nothing invented (gaps →
unresolved); candidate-schema validation clean; conflicts detected on both axes; report written;
`mode=dry-run` touched no files.
