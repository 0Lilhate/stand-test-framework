---
name: stand-test-debugging
description: Diagnose a failed stand-test autotest — extract scenarioId/testRunId/correlationId, split StandTestAssertionError vs StandTestException, match known failure signatures (registry/alias, unresolved env var, kafka messagesSeen, await diagnostics, gRPC status), classify the root cause and propose a fix without hiding the failure. Use when a generated test fails.
---

# stand-test-debugging (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-debugging/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-debugging/SKILL.md).

1. Read that file completely and follow it exactly
   (workflow: `docs/ai-agent/.claude/commands/stand-test-debug.md`).
2. Report via `docs/ai-agent/.claude/skills/stand-test-debugging/debugging-report-template.md` — exactly one primary
   root-cause class; `system-defect` keeps the test red and becomes a defect summary.
3. Forbidden fixes: try/catch around `stand.run`, deleting/weakening assertions,
   `@Disabled` without a ticket, blind timeout inflation. Weakening fixes need human approval.
