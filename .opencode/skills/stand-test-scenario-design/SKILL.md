---
name: stand-test-scenario-design
description: Turn a stand-test case analysis into a technical scenario design (scenario id, step order and types, captures, assertions, awaits, correlation, test-data and cleanup strategy, Java-DSL vs AI-format track choice). Use after stand-test-case-analysis and before any authoring.
---

# stand-test-scenario-design (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-scenario-design/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-scenario-design/SKILL.md).

1. Read that file completely and follow it exactly.
2. Fill the template `docs/ai-agent/.claude/skills/stand-test-scenario-design/scenario-design-template.md`.
3. Track rule: Java DSL is the default; AI format ONLY if every step fits the executable
   subset listed in `docs/ai-agent/README.md`.
4. Still no code generation in this skill.
