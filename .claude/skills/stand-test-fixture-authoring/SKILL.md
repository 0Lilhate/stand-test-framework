---
name: stand-test-fixture-authoring
description: Create safe classpath fixture files for stand-test scenarios (REST/Kafka bodies, gRPC protobuf-JSON requests) with ${testRunId}/${correlationId} placeholders, generic test data, no secrets/PII/production values. Use whenever a scenario references body/payload/request fixtures.
---

# stand-test-fixture-authoring (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-fixture-authoring/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-fixture-authoring/SKILL.md).

1. Read that file completely and follow it exactly; start from
   `docs/ai-agent/.claude/skills/stand-test-fixture-authoring/fixture-template.json`.
2. Every fixture referenced by a scenario ships in the SAME change set; paths relative,
   no `..`, under `src/test/resources/fixtures/`.
3. Unique keys derive from `${testRunId}`; zero secrets/PII — do not rely on Allure masking.
