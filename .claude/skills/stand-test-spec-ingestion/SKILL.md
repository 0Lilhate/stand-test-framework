---
name: stand-test-spec-ingestion
description: Ingest an UNSTRUCTURED specification (PDF/DOCX/ФС/BRD/ТЗ/markdown/txt/html) into schema-valid KB CANDIDATES under knowledge-base/candidates/<document-id>/ - read via an external converter, split into sections, extract contracts/flows/rules/scenarios with mandatory provenance and confidence, validate against the candidate schemas, detect conflicts against the existing KB, and produce an extraction report. NEVER writes the curated KB and never invents missing contract details. Use via /stand-test-ingest-spec; dry-run by default.
---

# stand-test-spec-ingestion (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-spec-ingestion/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-spec-ingestion/SKILL.md).

1. Read that file completely and follow it exactly, plus its colocated
   `extraction-report-template.md`, `source-document-template.yml` and
   `unstructured-spec-ingestion-checklist.md`.
2. Stage 0 uses an EXTERNAL converter (pandoc / PDF tool / OCR); the SDK runtime never parses documents.
3. Writes ONLY under `knowledge-base/candidates/` — never the curated KB. dry-run is the default.
4. Workflow: `docs/ai-agent/.claude/commands/stand-test-ingest-spec.md`.
