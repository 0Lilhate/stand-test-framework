---
description: Render environment config from the knowledge base into stand-test-environments.yml (plain JUnit, refs-only) or application.yml stand.test.environments.* (Spring starter) - deterministic merge that never touches unrelated keys, never writes secret values, shows a diff before apply, validates the result and reports missing refs.
version: 1
---

# /stand-test-generate-env — knowledge base → environment config

Runs [`stand-test-env-generation`](../skills/stand-test-env-generation/SKILL.md) as a gated
workflow. Output lands in the SDK's REAL registry formats — no third format exists.

## Input

- Environment id (`dev` / `ift` / other KB-declared TEST environment).
- Target file: `src/test/resources/stand-test-environments.yml` OR the project's
  `application.yml`/`application-test.yml` (starter surface).
- Mode: `dry-run` (default) | `apply`.
- Include modules (optional): `rest,kafka,db,grpc` — limits emitted blocks.

## Steps

1. **Load KB environment entry** + every bound service/topic/datasource/grpc entity; unresolved
   bindings go to `Missing KB references`.
2. **Build the desired registry subtree** per the skill's fixed KB→registry mapping table
   (kebab-case keys, deterministic ordering).
3. **Read the existing target file**; parse safely.
4. **Merge deterministically**: only `environments.<envId>` (or `stand.test.environments.<envId>`)
   is managed; KB-derived keys are set, unmanaged keys inside preserved and reported, everything
   outside untouched.
5. **Show the diff**. `dry-run` stops here.
6. **apply**: minimal textual edits (comments survive); full-file generation only for wholly
   SDK-managed files (with a generated-from-KB header comment).
7. **Validate**: YAML re-parse; alias coverage vs KB bindings; ref shapes; secret scan; starter
   twin rules. Apply
   [`application-yml-generation-checklist.md`](../skills/stand-test-env-generation/application-yml-generation-checklist.md).
8. **Report** per
   [`env-generation-report-template.md`](../skills/stand-test-env-generation/env-generation-report-template.md),
   including the full env-var list a runner must export.

## Mandatory checks

- [ ] Only `${ENV_VAR}` placeholders / `*-ref` names — zero secret values, zero credential defaults.
- [ ] Unrelated properties byte-identical after merge.
- [ ] Every KB binding for the environment present in the result (or reported missing).
- [ ] Keys sorted deterministically; file re-parses; loader/binding accepts it where runnable.

## Human approval points (blocking)

- The `apply` to a consumer config file.
- Any preserved unmanaged key that CONFLICTS with a KB-derived value (report lists both).
