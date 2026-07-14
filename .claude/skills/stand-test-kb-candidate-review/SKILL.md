---
name: stand-test-kb-candidate-review
description: Review the staged KB candidates for one ingested document - load candidates/<document-id>/, re-validate every file against the candidate schemas, group by category, surface low-confidence items, unresolved items and conflicts, run the secret/URL safety scan, classify each candidate as apply-eligible/needs-tick/blocked, and produce a review report for a human decision. Writes no curated KB. Use via /stand-test-review-kb-candidates.
---

# stand-test-kb-candidate-review (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-kb-candidate-review/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-kb-candidate-review/SKILL.md).

1. Read that file completely and follow it exactly, plus its colocated
   `kb-candidate-review-checklist.md` and `conflict-item-template.yml`.
2. This is the human gate: confidence gating (high/medium/low) and conflict blocking are decided here.
3. Writes NO curated KB — only per-candidate `review`/`status` marks in the staging files.
4. Workflow: `docs/ai-agent/.claude/commands/stand-test-review-kb-candidates.md`.
