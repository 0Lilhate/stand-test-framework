---
description: Promote the human-approved, confidence-eligible, conflict-free candidates of one document into the curated knowledge base. Projects each candidate to its curated entry (provenance/confidence stripped), re-validates against the STRICT stand-test-knowledge-base.schema.json, hands off to stand-test-kb-update as the sole deterministic writer, co-updates owning-service rollups, appends the promotion ledger, and re-runs KB validation. Dry-run by default; --apply writes.
version: 1
---

# /stand-test-apply-kb-candidates — promote approved candidates → curated KB

Runs [`stand-test-kb-candidate-apply`](../skills/stand-test-kb-candidate-apply/SKILL.md). The only
command that changes the curated KB — and only for an already-approved set, mechanically, via
`stand-test-kb-update`.

## Input

- `document-id`: the `candidates/<document-id>/` directory to promote from.
- Mode: `dry-run` (default) | `--apply`.

## Hard preconditions (checked before any write)

Promotable ONLY if: `review.decision: approved` AND `status: approved`; `confidence: high` or
ticked `medium`; not in an unresolved `conflict`; not `partial`; not `promotionBlocked`. Anything else
is reported and skipped.

## Steps

1. **Load** the approved subset; re-validate against the candidate schemas.
2. **Project** each candidate to a curated entry — lift the projectable fields, STRIP
   provenance/confidence/review/naturalKey/raw, re-derive the id mechanically. A missing strict-required
   field is filled only from `stand-test-kb-lookup`, else downgraded to unresolved — never invented.
3. **Strict-validate** each projected entry against `stand-test-knowledge-base.schema.json`; a failure
   blocks that entry (report, no write).
4. **Hand off to `stand-test-kb-update`** as the sole writer: its `diff (added/updated/unchanged/
   conflict) → write` runs dry-run first; never delete, never rename ids, never overwrite a
   manually-authored field without a human-resolved conflict.
5. **Referential integrity** — co-update each owning service's rollup in the same run; BLOCK any child
   whose owner is neither curated nor approved (no orphan promotion).
6. **Update provenance links** — append `promotion-log.yml` (curatedId → documentId/version/date/
   promotedAt). A skipped append fails the apply closed. Stamp promoted candidates `status: applied`.
7. **Produce the diff**; run `/stand-test-generate-env` for any new env-var refs.
8. **Run KB validation** — `./gradlew :stand-test-ai-schema:test` (this repo) / the consumer's KB check.

## Mandatory checks

- [ ] Only approved + eligible + conflict-free + non-partial candidates promoted.
- [ ] Every projected entry passed the STRICT umbrella schema before writing.
- [ ] Curated files written only via `stand-test-kb-update`; no deletions, no id/alias renames.
- [ ] Provenance/confidence stripped — the curated KB stays sterile (refs/contracts only).
- [ ] `promotion-log.yml` appended for every promoted id; KB validation green.

## Human approval points (blocking)

- The `--apply` itself (the curated KB is configuration).
- Every conflict resolution.
- Any new env-var ref names (they imply stand configuration work).
