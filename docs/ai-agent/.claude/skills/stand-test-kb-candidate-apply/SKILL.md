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

The RECORD OF REVIEW is `candidates/<document-id>/review-decisions.yml` — the only place carrying a
schema-required `reviewedBy` and `reviewedOn`, i.e. who decided and when. Per-candidate `status` is
LIFECYCLE (`new` → `applied`), not authority, and `review.decision` is an optional detail beside it.

A candidate is promotable ONLY if ALL hold:
- the family's `disposition` in `review-decisions.yml` lists its id under `approved`;
- `confidence: high`, OR `confidence: medium` whose `approved[].basis` says why the human took it —
  that string IS the tick; no schema anywhere has another field for it;
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
4. **Take a write permit** naming every curated file this promote will touch, the owning service's
   rollup included (it is written a second time by the referential-integrity step):
   `node <bundle>/hooks/stand-guard.mjs kb-write-permit --reason promote --document <document-id> <files>`.
   It refuses a document with no review record — that catches a promote aimed at the wrong id and is
   NOT the approval; the approval is the host prompt on each write.
5. **Hand off to `stand-test-kb-update`** as the SOLE deterministic writer. Feed the projected entries
   as pre-formed candidates; kb-update runs its own `parse → validate → diff (added/updated/unchanged/
   conflict) → write` pipeline, dry-run first. Do NOT write curated files directly — reuse kb-update's
   never-delete / never-silently-overwrite / sorted-by-id / property-order guarantees and the pinned
   `KnowledgeBaseSchemaValidationTest` guard.
6. **Referential integrity** — in the SAME kb-update run, co-update the owning service's rollup
   (`endpoints[]`/`datasources[]`/`grpcTargets[]`/`kafkaTopics`) for each promoted child. If the owner
   is neither curated nor in the approved set, BLOCK the child's promotion (never orphan-promote).
7. **Update provenance links** — append `promotion-log.yml`: `curatedId → { documentId,
   documentVersion, documentDate, promotedAt, promotedBy }`. This is the ONLY join surface for
   stale-vs-curated and version-conflict detection (curated entries stay provenance-free). A skipped
   log append **fails the apply closed** — it is part of the write, not an afterthought. Stamp the
   promoted candidate `status: applied`.
8. **Produce the diff** (kb-update's report) and, for any new env-var refs the promoted entries
   introduce, run `/stand-test-generate-env` (refs only, diff before apply).
9. **Run KB validation** — `node <bundle>/hooks/stand-guard.mjs kb-validate --exit-code` (both
   repositories; the Gradle schema tests went with `stand-test-ai-schema`); re-run the schema over
   every touched curated file.
10. **Close the write.**
   `node <bundle>/hooks/stand-guard.mjs alias-check`, then
   `node <bundle>/hooks/stand-guard.mjs record-gate --gate kb-write --verdict PASS <files>` — until
   that verdict is recorded the session will not end, because a curated write nobody re-read breaks
   not this session but the next generated test.

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
