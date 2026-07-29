---
name: stand-test-kb-candidate-review
description: Review the staged KB candidates for one ingested document - load candidates/<document-id>/, re-validate every file against the candidate schemas, group by category, surface low-confidence items, unresolved items and conflicts, run the secret/URL safety scan, classify each candidate as apply-eligible/needs-tick/blocked, and produce a review report for a human decision. Writes no curated KB. Use via /stand-test-review-kb-candidates.
---

# Skill: stand-test-kb-candidate-review

The **human gate** of ingestion. It presents the staged candidates for one document so a human can
decide what may be applied. It writes nothing to the curated KB; its outputs are a review report and
per-candidate `review`/`status` marks in the staging files.

## When to use

After `/stand-test-ingest-spec` has produced `candidates/<document-id>/`, and before
`/stand-test-apply-kb-candidates`. This is where confidence gating and conflict blocking are decided.

## Procedure

1. **Load candidates** for the `document-id` from `knowledge-base/candidates/<document-id>/`
   (consumer repo: `knowledge-base/candidates/<document-id>/`). Read `source-document.yml` first.
2. **Re-validate schemas** — every `*.candidates.yml` against its candidate schema
   (`extraction-candidate`, `business-flow`, `business-rule`, `test-scenario-candidate`,
   `unresolved-item`, `conflict-item`). A file that no longer validates (e.g. a bad hand-edit) blocks
   the whole review — fix or quarantine it first.
3. **Group candidates** by `candidateType` and show each with its `confidence`, its `Derived from`
   (documentId §section p.page) and its `naturalKey`.
4. **Show low-confidence candidates** — everything at `confidence: low` (and anything in
   `unresolved.candidates.yml`). These are NEVER apply-eligible; they need a better source or a human
   answer.
5. **Show unresolved items** — grouped by `type`, with `blockingFor` so the human sees what each gap holds up.
6. **Show conflicts** — from `conflicts.candidates.yml`, both sides with their own provenance and the
   non-binding `suggestedResolution`. Every conflict is `autoResolvable: false` — a human decides.
7. **Safety scan** — run [`stand-test-safety-review`](../stand-test-safety-review/SKILL.md)'s secret/URL/
   production scan over every candidate file (drafts bypass the strict schema's guards only up to
   promotion, so scan them now). Any finding blocks the involved candidate ids.
8. **Classify each candidate** for the apply gate:
   - `high` + no open conflict + not partial → **eligible after approval**;
   - `medium` → **needs an explicit per-item human tick**;
   - `low` / open conflict / partial / `promotionBlocked` → **blocked**.
   Record the human's decision in each candidate's `review` block (`reviewer`, `decision`, `comment`)
   and set `status` (`approved` / `rejected` / `needs-review`). **No curated KB is written here.**
9. **Produce the review report** using the checklist below; end with the eligible-vs-blocked split and
   the next command (`/stand-test-apply-kb-candidates <document-id>`).

## Confidence & conflict gating (the rules apply enforces)

- Every apply is human-gated — confidence never removes the human, it only sets scrutiny.
- `high` = eligible after review; `medium` = needs an explicit tick; `low` = never applied.
- Any candidate id appearing in an unresolved `conflict` is blocked on BOTH sides until a human writes
  the conflict's `resolution` block.
- `partial: true` or `promotionBlocked` (e.g. db-table with no curated home) is blocked regardless of confidence.

## Forbidden

- Writing to the curated 8 collections (that is `/stand-test-apply-kb-candidates` only).
- Auto-resolving a conflict, or upgrading a `low` item's confidence without a human and a better source.
- Silently dropping an unresolved item — it must be shown and tracked.

## Artifacts

Conflict record shape: [`conflict-item-template.yml`](conflict-item-template.yml).
Review checklist: [`kb-candidate-review-checklist.md`](kb-candidate-review-checklist.md).
