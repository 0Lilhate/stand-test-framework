---
name: stand-test-test-review
description: Quality review of a generated stand-test autotest — coverage vs the original case, assertion correctness (matcher/type/equals-only asymmetry), non-flaky awaits, correlation, captures, cleanup, reporting metadata, negative cases. Use after safety review passes, before human approval.
---

# stand-test-test-review (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-test-review/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-test-review/SKILL.md).

1. Read that file completely and follow it exactly.
2. Report via `docs/ai-agent/.claude/skills/stand-test-test-review/generated-test-review-template.md`; apply
   `docs/ai-agent/.claude/skills/stand-test-test-review/review-checklist.md` and
   `docs/ai-agent/.claude/skills/stand-test-test-review/flakiness-checklist.md`.
3. CRITICAL/HIGH ⇒ CHANGES-REQUIRED, loop back to authoring. The merge decision is human.
