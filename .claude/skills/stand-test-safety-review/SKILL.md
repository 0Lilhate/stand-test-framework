---
name: stand-test-safety-review
description: Adversarial guardrail review of generated stand-test artifacts — detects arbitrary URLs, inline secrets, destructive SQL, production envs, missing/unbounded timeouts, Thread.sleep, raw clients, validator bypasses, fixed ids without testRunId. Mandatory gate after every stand-test generation; any BLOCK finding stops the workflow.
---

# stand-test-safety-review (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-safety-review/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-safety-review/SKILL.md).

1. Read that file completely and follow it exactly (grep sweeps are mandatory — do not
   trust reading alone).
2. Report via `docs/ai-agent/.claude/skills/stand-test-safety-review/safety-review-template.md`; apply
   `docs/ai-agent/.claude/skills/stand-test-safety-review/safety-checklist.md`.
3. BLOCK findings ⇒ regenerate through the authoring skill; never hand-patch around a
   guardrail, never weaken the violated check.
