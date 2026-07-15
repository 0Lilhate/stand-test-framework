---
description: Review the staged KB candidates of one ingested document. Loads candidates/<document-id>/, re-validates every file against the candidate schemas, groups candidates, surfaces low-confidence items, unresolved items and conflicts, runs the secret/URL safety scan, classifies each candidate as apply-eligible / needs-tick / blocked, and produces a review report. This is the human gate; it writes no curated KB.
---

# /stand-test-review-kb-candidates — review staged candidates

Runs [`stand-test-kb-candidate-review`](../skills/stand-test-kb-candidate-review/SKILL.md). This is the
**human decision point** between ingestion and apply. No curated KB is written.

## Input

- `document-id`: the `candidates/<document-id>/` directory to review.

## Steps

1. **Load candidates** for the `document-id` (`source-document.yml` first).
2. **Re-validate schemas** — every `*.candidates.yml` against its candidate schema; a file that no
   longer validates blocks the review.
3. **Show grouped candidates** by `candidateType` with `confidence`, `Derived from` and `naturalKey`.
4. **Show low-confidence candidates** (never apply-eligible).
5. **Show unresolved items** grouped by `type` with `blockingFor`.
6. **Show conflicts** — both sides with provenance and the non-binding `suggestedResolution`.
7. **Safety scan** the candidates (secret/URL/production); findings block the involved ids.
8. **Classify** each candidate: `high` → eligible after approval; `medium` → needs a per-item tick;
   `low` / open conflict / partial / promotionBlocked → blocked. Record each decision in the
   candidate's `review` block and `status`.
9. **Produce the review report** per
   [`kb-candidate-review-checklist.md`](../skills/stand-test-kb-candidate-review/kb-candidate-review-checklist.md);
   end with the eligible-vs-blocked split.

## Mandatory checks

- [ ] Every candidate re-validated; provenance + confidence present.
- [ ] Low-confidence, conflicted, partial and promotionBlocked candidates marked NOT eligible.
- [ ] Safety scan clean (or findings block the ids).
- [ ] No conflict auto-resolved; no unresolved item silently dropped.
- [ ] No curated KB written.

## Human handoff

Next: `/stand-test-apply-kb-candidates <document-id> --apply` on the approved, eligible subset.
