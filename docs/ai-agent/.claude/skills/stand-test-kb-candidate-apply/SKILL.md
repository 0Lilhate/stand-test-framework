---
name: stand-test-kb-candidate-apply
description: Promote the human-approved, confidence-eligible, conflict-free candidates of one document into the curated knowledge base - project each candidate to its curated entry (strip provenance/confidence), re-validate against the STRICT stand-test-knowledge-base.schema.json, hand off to stand-test-kb-update as the sole deterministic writer, co-update owning-service rollups, append the promotion ledger, and re-run KB validation. Dry-run by default. Use via /stand-test-apply-kb-candidates.
version: 1
---

# Skill: stand-test-kb-candidate-apply

The only step that changes the curated KB — and it does so mechanically from an already-approved set.
It does NOT re-open judgement; it replays the human's review decisions through the existing
deterministic writer. The interpretive, non-deterministic work is already frozen in the candidate
layer; promotion is a lift-and-validate.

## When to use

After `/stand-test-review-kb-candidates` has marked candidates approved. This is the irreversible step,
so it is small, auditable, and `dry-run` by default.

## Hard preconditions (checked before anything is written)

A candidate is promotable ONLY if ALL hold:
- `review.decision: approved` AND `status: approved`;
- `confidence: high`, OR `confidence: medium` with an explicit per-item human tick;
- it is not involved in any `conflict` whose `resolution` is still unresolved;
- it is not `partial` and not `promotionBlocked` (e.g. db-table with no curated home is not promotable
  until a human extends the schema — a separate SDK change, not this pipeline).
Anything failing a precondition is reported and skipped; it is never written.

## Procedure

1. **Load** the approved subset of `candidates/<document-id>/` and re-validate each against its
   candidate schema.
2. **Project** each candidate to a curated entry: lift the projectable fields (`normalized` +
   `extracted` → the curated shape), **strip** `source`/`confidence`/`review`/`naturalKey`/`raw`
   (provenance NEVER enters the curated KB), and re-derive the id by the same mechanical kebab rule.
   A still-missing strict-required field is filled only if `stand-test-kb-lookup` supplies it, else the
   candidate is downgraded to `unresolved` — never invented.
3. **Strict-validate** each projected entry against `stand-test-knowledge-base.schema.json` (the curated
   umbrella). A failure means the entry is not promotable — report it, do not write.
4. **Hand off to `stand-test-kb-update`** as the SOLE deterministic writer. Feed the projected entries
   as pre-formed candidates; kb-update runs its own `parse → validate → diff (added/updated/unchanged/
   conflict) → write` pipeline, dry-run first. Do NOT write curated files directly — reuse kb-update's
   never-delete / never-silently-overwrite / sorted-by-id / property-order guarantees and the pinned
   `KnowledgeBaseSchemaValidationTest` guard.
5. **Referential integrity** — in the SAME kb-update run, co-update the owning service's rollup
   (`endpoints[]`/`datasources[]`/`grpcTargets[]`/`kafkaTopics`) for each promoted child. If the owner
   is neither curated nor in the approved set, BLOCK the child's promotion (never orphan-promote).
6. **Update provenance links** — append `promotion-log.yml`: `curatedId → { documentId,
   documentVersion, documentDate, promotedAt, promotedBy }`. This is the ONLY join surface for
   stale-vs-curated and version-conflict detection (curated entries stay provenance-free). A skipped
   log append **fails the apply closed** — it is part of the write, not an afterthought. Stamp the
   promoted candidate `status: applied`.
7. **Produce the diff** (kb-update's report) and, for any new env-var refs the promoted entries
   introduce, run `/stand-test-generate-env` (refs only, diff before apply).
8. **Run KB validation** — `./gradlew :stand-test-ai-schema:test` (this repo) or the consumer's KB
   check; re-run the schema over every touched curated file.

## Forbidden

- Writing curated files by any path other than `stand-test-kb-update`.
- Promoting a `low`/unticked-`medium`/conflicted/`partial`/`promotionBlocked` candidate.
- Letting provenance/confidence leak into a curated entry (the curated KB is sterile — refs/contracts only).
- Deleting or renaming an existing curated id/alias, or overwriting a manually-authored field without a
  human-resolved conflict.

## Determinism guarantees

Same approved set → same curated diff: mechanical ids, natural-key join (no duplicates), sorted-by-id
property-ordered writes via kb-update, conflicts never auto-resolved, `dry-run` by default, apply only
on explicit `--apply`.
