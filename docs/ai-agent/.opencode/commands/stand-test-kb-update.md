---
description: Update the stand-test knowledge base from a source spec (OpenAPI/AsyncAPI/proto/SQL/markdown/application.yml/pasted text) - parse to schema-valid candidates, deterministic diff against the existing KB, dry-run by default, deterministic apply, validation tests, update report. Never deletes entries, never adds secrets or production config.
---

# /stand-test-kb-update — source spec → knowledge-base entries

Runs [`stand-test-kb-update`](../skills/stand-test-kb-update/SKILL.md) as a gated workflow.
The KB is stand configuration: a human approves the diff before `apply` lands.

## Input

- Source: file path OR pasted specification text.
- `sourceType`: `openapi | asyncapi | proto | sql | markdown | application-yml | auto`.
- Target domain/service alias (optional — scopes candidate ownership).
- Mode: `dry-run` (default) | `apply`.

## Steps

1. **Read the source**; unreadable/unparseable input ends the workflow with a report error.
2. **Parse into candidate entries** per the skill's per-type extraction rules; id/alias
   derivation is mechanical and recorded in the report.
3. **Validate candidates** against `stand-test-knowledge-base.schema.json` (consumer repo:
   `knowledge-base/schema/`; SDK repo: `docs/ai-agent/knowledge-base/schema/`);
   failures go to `skipped`, never into files.
4. **Diff against the existing KB**: `added` / `updated` / `unchanged` / `conflicts` /
   `removed-candidates` (report-only). Conflicts are never auto-resolved.
5. **dry-run**: stop — no file changes; print the report with the would-be diff.
6. **apply**: write deterministically (collections sorted by id, schema key order, one collection
   key per file), never deleting, never renaming ids/aliases.
7. **Run KB validation** — `./gradlew :stand-test-ai-schema:test` in this repo / the consumer's
   KB check; plus [`kb-entry-review-checklist.md`](../skills/stand-test-kb-update/kb-entry-review-checklist.md)
   over every added/updated entry.
8. **Print the update report** per
   [`kb-update-report-template.md`](../skills/stand-test-kb-update/kb-update-report-template.md).

## Mandatory checks

- [ ] Every candidate schema-validated; every touched entry in exactly one diff bucket.
- [ ] No deletions, no id/alias renames, no overwritten manually-authored fields.
- [ ] Secret/URL scan of the diff clean; no production environments.
- [ ] Validation tests green after apply.

## Human approval points (blocking)

- The `apply` itself (the KB is configuration).
- Every `conflict` row.
- Any proposed new env-var ref names (they imply stand configuration work).
