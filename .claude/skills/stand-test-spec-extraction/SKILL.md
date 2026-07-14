---
name: stand-test-spec-extraction
description: The interpretive extraction rules for turning normalized spec text/tables/figures into schema-valid stand-test KB candidates - how to find REST endpoints, Kafka topics, DB tables/probes, gRPC methods, business flows and business rules, how to assign provenance and a high/medium/low confidence tier, and how to emit an UNRESOLVED item instead of hallucinating a missing method/path/topic/table/column. Used by stand-test-spec-ingestion; produces candidate files only.
---

# stand-test-spec-extraction (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-spec-extraction/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-spec-extraction/SKILL.md).

1. Read that file completely and follow it exactly, plus its colocated candidate templates
   (`business-flow-candidate-template.yml`, `business-rule-candidate-template.yml`,
   `test-scenario-candidate-template.yml`, `unresolved-item-template.yml`).
2. Extract ONLY what the document states; every missing contract detail is an unresolved item, never a guess.
3. Every candidate carries provenance (redacted quote) + a confidence tier + a naturalKey; candidate
   files are validated by `knowledge-base/schema/*.schema.json`, never the strict umbrella schema.
