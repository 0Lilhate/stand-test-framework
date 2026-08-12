---
version: 1
---

# Workflow: review and apply KB candidates

Continues [`ingest-unstructured-spec-to-kb.md`](ingest-unstructured-spec-to-kb.md). Turns a staged set
of candidates into a human-approved, deterministically-applied curated KB update. Every apply is
human-gated; the only writer of the curated KB is `stand-test-kb-update`.

## Flow

```
/stand-test-review-kb-candidates <document-id>                                   [HUMAN GATE, no curated write]
  1. load + re-validate candidates/<document-id>/*
  2. group by type; show confidence, Derived from, naturalKey
  3. show low-confidence, unresolved, conflicts (both sides + provenance)
  4. safety-review scan (secret/URL/production)
  5. classify: high = eligible-on-approval | medium = needs a tick | low/conflict/partial = BLOCKED
  6. human records review.decision + status per candidate; resolves conflicts

/stand-test-apply-kb-candidates <document-id> --apply                            [CURATED WRITE]
  precondition (hard): listed under `approved` in review-decisions.yml AND (high OR medium with a basis)
                       AND no open conflict AND not partial/promotionBlocked
  0. kb-write-permit --reason promote --document <id> <files>   (curated files are closed without it)
  1. project candidate -> curated entry (STRIP provenance/confidence; re-derive id)
  2. strict-validate vs stand-test-knowledge-base.schema.json
  3. stand-test-kb-update: diff (added/updated/unchanged/conflict) -> deterministic write
     (the host asks a human at each write — that prompt, not the permit, is the approval)
  4. co-update owning-service rollups (BLOCK orphan promotion)
  5. append promotion-log.yml (fail-closed); stamp candidate status: applied
  6. /stand-test-generate-env for new env-var refs
  7. kb-validate --exit-code                (curated KB validation)
  8. alias-check, then record-gate --gate kb-write --verdict PASS <files>
     (until this verdict is recorded the session will not end)
```

## Gates

- **Confidence gating** sits at the review→apply boundary: `high` eligible after approval, `medium`
  needs a stated `basis` on its `approved` item, `low` never applies.
- **The curated write is permitted, prompted and checked.** Paths are declared before the content
  exists (permit), the human confirms each write (host prompt), and what landed is re-read by
  `kb-write` before the session may end. None of the three claims to be the others.
- **Conflict blocking** is enforced as an apply precondition — any id in an unresolved conflict is
  blocked on both sides until a human writes the `resolution`.
- **Partial / promotionBlocked** (e.g. db-table with no curated home) is blocked until a human decides
  to extend the schema — a separate, human-owned SDK change, NOT part of this pipeline.

## Guarantees

- **Sterile curated KB.** Provenance/confidence are stripped on promotion; only refs/contracts land.
- **Deterministic write.** kb-update is the sole writer (sorted-by-id, property order, never-delete);
  natural-key join prevents duplicate curated entries.
- **Auditable.** `promotion-log.yml` links every curated id back to its source document/version — the
  only join surface for stale/version-conflict detection later.

## Next

Author tests against the enriched KB with the existing pipeline: `/stand-test-design` →
`/stand-test-generate-java-test`. Test-scenario candidates from ingestion pre-fill
`stand-test-case-analysis`.
