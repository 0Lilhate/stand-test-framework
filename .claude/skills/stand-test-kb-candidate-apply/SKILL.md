---
name: stand-test-kb-candidate-apply
description: Promote the human-approved, confidence-eligible, conflict-free candidates of one document into the curated knowledge base - project each candidate to its curated entry (strip provenance/confidence), re-validate against the STRICT stand-test-knowledge-base.schema.json, hand off to stand-test-kb-update as the sole deterministic writer, co-update owning-service rollups, append the promotion ledger, and re-run KB validation. Dry-run by default. Use via /stand-test-apply-kb-candidates.
---

# stand-test-kb-candidate-apply (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-kb-candidate-apply/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-kb-candidate-apply/SKILL.md).

1. Read that file completely and follow it exactly.
2. The ONLY curated writer is `stand-test-kb-update`; this skill projects + strips provenance and hands off.
3. Hard preconditions: approved + (high or ticked medium) + no open conflict + not partial/promotionBlocked.
4. dry-run is the default; `--apply` writes. Append `promotion-log.yml` fail-closed; re-run KB validation.
5. Workflow: `docs/ai-agent/.claude/commands/stand-test-apply-kb-candidates.md`.
