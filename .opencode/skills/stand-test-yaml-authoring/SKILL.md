---
name: stand-test-yaml-authoring
description: Generate an AI-format (steps/type) JSON/YAML scenario for stand-test-sdk from a scenario design, conforming to the stand-test-ai-schema JSON Schema and its executable subset (7 step types, full matcher set on REST and grpc.unary but equals-only on kafka.expect, fixture-only bodies, bounded timeouts, logical aliases). Use when the design's track is the declarative AI format.
---

# stand-test-yaml-authoring (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-yaml-authoring/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-yaml-authoring/SKILL.md),
which itself defers to the resources inside the `stand-test-ai-schema` jar
(`AiSchemaResources.GENERATION_RULES_RESOURCE` and `SCHEMA_RESOURCE`) — on conflict the jar
resources win.

1. Read the skill file and follow it exactly; start from
   `docs/ai-agent/.claude/skills/stand-test-yaml-authoring/yaml-scenario-template.yaml`.
2. Mandatory gates, in order: JSON Schema validation (empty message set) →
   `AiScenarioParser().parse(...)` →
   `DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid()`
   (registry overload only — the one-arg form is structural and skips guardrails)
   → `stand-test-safety-review`.
3. Emit every referenced fixture via `stand-test-fixture-authoring` in the same change.
4. If any step falls outside the executable subset — switch to `stand-test-java-dsl-authoring`.
