---
name: stand-test-environment-mapping
description: Map systems named in a text case onto logical aliases of the stand-test environment registry (stand-test-environments.yml or stand.test.environments.*), verify correlation/auth/write-allowed properties, and report missing aliases and required env vars. Use right after stand-test-case-analysis.
---

# stand-test-environment-mapping (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-environment-mapping/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-environment-mapping/SKILL.md).

1. Read that file completely and follow it exactly.
2. Output the four-table mapping report (resolved aliases, missing aliases with proposed
   registry blocks, forbidden directs, required env vars).
3. Registry additions are stand configuration — a HUMAN approves and applies them; never
   invent endpoints, never put URLs/secrets anywhere (env-var NAMES only in `*-ref` fields).
