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

`rest.get`, `rest.post`, `rest.expectEventually` (GET-polling, requires `timeout` and at least one of
`expect`/`assert`), `kafka.send`, `kafka.expect`, `db.expectEventually` (read-only), and `grpc.unary`
(executable: the `stand-test-grpc` adapter runs it via server reflection + `DynamicMessage`).

Each step is an object with a `type` discriminator (`{ "type": "rest.post", ... }`), never a single-key
map. Fail-closed: every step object sets `additionalProperties: false`.

REST steps accept `service` (alias), a relative `path`, an optional `query` (`string -> string` map),
`headers`, `body.fixture`/`body.json`, `expect.status`, an optional `assert` list over the response body
(same matcher shape as `kafka.expect`), `correlation.inject`, and `capture`. `kafka.send` requires a
`payload` (schema is fail-closed to match the runtime). REST and `grpc.unary` assertions execute the full
matcher set (`equals`/`exists`/`notNull`/`contains`/`matches`); `kafka.expect` assertions execute `equals`
only — see the *Schema vs runtime* section of the generation rules.

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

## Note on document shape (resolved)

This schema uses a top-level `steps: [...]` array with a `type:` discriminator per step — the most
JSON-Schema-friendly shape for AI generation. The `stand-test-scenario-yaml` module parses this exact
format via `AiScenarioParser` (alongside its human-oriented `given`/`then` surface handled by
`YamlScenarioParser`), so a schema-valid document is directly executable; the parity is pinned by
`AiScenarioParserTest` and the example module's `AiSchemaParityTest`. The parser additionally fails
closed on schema-valid constructs the runtime cannot execute yet (see the generation rules' "Schema vs
runtime" section), and the core `DefaultScenarioValidator` re-enforces the schema's value-level
guardrails at run time.
