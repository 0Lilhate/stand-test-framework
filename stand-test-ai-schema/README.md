# stand-test-ai-schema

**Group:** AI / DSL · **Gradle plugin:** `java-library`

AI guardrails for `stand-test-sdk`: a machine-readable **JSON Schema** of the allowed declarative
scenario document plus an **AI generation-rules** catalogue, so an AI agent generates safe declarative
tests instead of arbitrary Java. This is a **static guardrail layer** — it constrains *what may be
generated*; it does not execute anything.

## What it ships

- `src/main/resources/schema/stand-test-scenario.schema.json` — the scenario JSON Schema
  (Draft 2020-12).
- `src/main/resources/ai/stand-test-ai-generation-rules.md` — human/AI-readable generation rules,
  including the forbidden-operations table keyed off core `ForbiddenOperation`.
- `ru.alfa.stand.test.ai.AiSchemaResources` — a JDK-only loader for both resources
  (`SCHEMA_RESOURCE`, `GENERATION_RULES_RESOURCE`).

## What it is NOT

No scenario execution, no `ScenarioRunner`/`StepExecutor`, no YAML runner, no REST/Kafka/DB/gRPC
clients, no JUnit extension, no Allure adapter, no Spring Boot starter, no Testcontainers, no business
scenarios, no real stand configs, and no real secrets.

## Dependency boundary

Target graph: **core model only** (plan §4/§5/§8.6). The main source is JDK-only. `stand-test-core` is
used **only** by the cross-check test (verifying the rules stay in sync with `ForbiddenOperation`), and
the JSON Schema validator (`networknt`) + Jackson exist **only** in tests to validate the example
scenarios — so **Jackson never reaches the main graph** (the repo otherwise avoids Jackson). The module
does **not** depend on the adapter modules or on `stand-test-scenario-yaml`: the schema mirrors the
declarative surface as a *specification*, not a compile-time edge.

## Using the schema

Add the module (typically `testImplementation`) and validate an AI-generated scenario against the
shipped resource with any JSON Schema 2020-12 validator:

```java
String schema = AiSchemaResources.scenarioSchemaJson(); // or read SCHEMA_RESOURCE from the classpath
// feed `schema` + the candidate document to your validator of choice
```

## Supported step types (MVP)

`rest.get`, `rest.post`, `kafka.send`, `kafka.expect`, `db.expectEventually` (read-only),
and `grpc.unary` (**draft**: shape only — execution ships with the `stand-test-grpc` adapter later).

Each step is an object with a `type` discriminator (`{ "type": "rest.post", ... }`), never a single-key
map. Fail-closed: every step object sets `additionalProperties: false`.

## Forbidden by the schema

Arbitrary URLs / hosts / connection strings (only logical aliases: `service`/`topic`/`datasource`/
`target`); inline secrets and secret-bearing headers (`Authorization`/token/password/…); destructive
SQL (`db.expectEventually` is a read-only `SELECT`); missing or unbounded `timeout` on async waits;
sleeps; scripting/expression matchers; and any unknown field. Alias whitelisting and SQL semantics are
enforced separately at **runtime** by the core `ScenarioValidator` and the adapters (defence in depth).

## Why declarative, not Java

The SDK deliberately runs every scenario through the `ScenarioValidator` and its guardrails. A
declarative document that an AI produces can be validated *before* execution and cannot smuggle in
imperative eager-IO or raw clients, so the guardrails always apply. That is the whole point of the
schema.

## Note on document shape (follow-up)

This MVP schema uses a top-level `steps: [...]` array with a `type:` discriminator per step, which is the
most JSON-Schema-friendly shape for AI generation. The current `stand-test-scenario-yaml` parser uses a
different surface (`given`/`then` with single-key step maps). Reconciling the two surfaces (or adding a
translation/parity layer) is a tracked follow-up; until then this schema is the contract for
AI-generated documents, not a 1:1 mirror of the YAML parser input.
