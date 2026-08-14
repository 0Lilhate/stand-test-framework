---
name: stand-test-kb-lookup
description: Resolve a text test case against the stand-test knowledge base (knowledge-base or docs/ai-agent/knowledge-base layout) into a deterministic KnowledgeBaseLookupResult - matched services/endpoints/topics/datasources/dbProbes/grpcTargets/grpcMethods, missing entries, recorded assumptions. Never invents contract details; no KB entry means a missing item, not a guess. Use right after stand-test-case-analysis, before stand-test-environment-mapping.
---

# stand-test-kb-lookup (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-kb-lookup/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-kb-lookup/SKILL.md).

1. Read that file completely and follow it exactly.
2. Fill the template `docs/ai-agent/.claude/skills/stand-test-kb-lookup/kb-lookup-result-template.yml`.
3. Never invent endpoints/topics/queries/methods — unknowns go to `missing`, blocking ones stop the workflow.
4. Next step: `stand-test-environment-mapping`
   (workflow: `docs/ai-agent/.claude/commands/stand-test-generate-java-test.md`).
