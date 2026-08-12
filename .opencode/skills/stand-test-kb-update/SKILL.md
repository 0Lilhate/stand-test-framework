---
name: stand-test-kb-update
description: Update the stand-test knowledge base from a source of truth (OpenAPI, AsyncAPI, proto, SQL schema/migration, markdown spec, application.yml, or pasted text) - parse, form schema-valid candidate entries, diff deterministically against the existing KB (added/updated/unchanged/conflicts), never delete or silently overwrite, never add secrets or production config. Use via /stand-test-kb-update; dry-run by default.
---

# stand-test-kb-update (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-kb-update/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-kb-update/SKILL.md).

1. Read that file completely and follow it exactly.
2. Report per `docs/ai-agent/.claude/skills/stand-test-kb-update/kb-update-report-template.md`;
   review every entry with `kb-entry-review-checklist.md` from the same directory.
3. dry-run is the default; `apply` and every conflict need explicit human approval.
4. Validate with `./gradlew :stand-test-ai-schema:test` after apply
   (workflow: `docs/ai-agent/.claude/commands/stand-test-kb-update.md`).
