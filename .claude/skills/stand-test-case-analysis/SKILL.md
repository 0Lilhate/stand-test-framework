---
name: stand-test-case-analysis
description: Extract the structure of a future stand-test-sdk autotest from a plain-text business case (goal, preconditions, trigger, expected REST/Kafka/DB/gRPC effects, timeouts, cleanup, missing info). Use FIRST whenever asked to create an autotest from a text case, ticket, or manual regression steps. Produces analysis only — no code.
---

# stand-test-case-analysis (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-case-analysis/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-case-analysis/SKILL.md).

1. Read that file completely and follow it exactly.
2. Fill the template `docs/ai-agent/.claude/skills/stand-test-case-analysis/test-case-analysis-template.md`.
3. Do NOT generate any scenario/test/fixture in this skill — analysis only.
4. Next step: `stand-test-environment-mapping`, then `stand-test-scenario-design`
   (workflow: `docs/ai-agent/.claude/commands/stand-test-design.md`).
