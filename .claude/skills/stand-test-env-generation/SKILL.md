---
name: stand-test-env-generation
description: Render environment configuration from the stand-test knowledge base into the REAL SDK formats - stand-test-environments.yml (plain JUnit, refs-only) or application.yml stand.test.environments.* (Spring starter, refs + optional non-secret value twins). Deterministic merge, never touches unrelated keys, never writes secret values, shows a diff before apply, re-validates YAML and alias coverage after. Use via /stand-test-generate-env.
---

# stand-test-env-generation (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-env-generation/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-env-generation/SKILL.md).

1. Read that file completely and follow it exactly, including the fixed KB→registry mapping table.
2. Report per `docs/ai-agent/.claude/skills/stand-test-env-generation/env-generation-report-template.md`;
   gate with `application-yml-generation-checklist.md` from the same directory.
3. Refs and `${ENV_VAR}` placeholders only — never secret values, never credential defaults,
   never credential value twins; secrets are emitted as `*-ref` only.
4. dry-run is the default; `apply` only on explicit request, and the diff is shown either way
   (workflow: `docs/ai-agent/.claude/commands/stand-test-generate-env.md`).
