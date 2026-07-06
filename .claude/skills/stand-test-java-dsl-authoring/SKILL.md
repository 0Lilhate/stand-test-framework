---
name: stand-test-java-dsl-authoring
description: Generate a JUnit 5 test on the stand-test-sdk lazy Java DSL (Scenario.builder + RestStep/KafkaStep/DbStep/GrpcStep, injected StandClient, expectEventually awaits, captures/${var}, testRunId-scoped data, no eager IO, no validator bypass). The DEFAULT authoring track for stand autotests. Use when converting a scenario design into a Java test.
---

# stand-test-java-dsl-authoring (wrapper)

This is a thin wrapper. The single source of truth is
[docs/ai-agent/.claude/skills/stand-test-java-dsl-authoring/SKILL.md](../../../docs/ai-agent/.claude/skills/stand-test-java-dsl-authoring/SKILL.md).

1. Read that file completely and follow it exactly; start from
   `docs/ai-agent/.claude/skills/stand-test-java-dsl-authoring/java-test-template.java`.
2. Hard rules that override anything else you know: no `Thread.sleep`/Awaitility, no raw
   HTTP/Kafka/JDBC/gRPC clients, no `new DefaultScenarioRunner(...)` in consumer code, no
   inline `Authorization` headers, aliases only, AssertJ only.
3. NOTE: the generic `springboot-tdd` skill's Testcontainers guidance does NOT apply to
   stand tests — this SDK targets real DEV/IFT stands by design.
4. Gates: compile + checkstyle, grep sweep, then `stand-test-safety-review`
   (workflow: `docs/ai-agent/.claude/commands/stand-test-java.md`).
