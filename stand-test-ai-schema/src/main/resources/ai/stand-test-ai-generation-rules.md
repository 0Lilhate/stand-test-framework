# Stand-test AI generation rules

Rules for an AI agent generating **stand-test** integration/e2e scenarios. Follow them exactly.
The authoritative machine-readable contract is the JSON Schema
`schema/stand-test-scenario.schema.json` (classpath resource). Every scenario you emit MUST validate
against that schema. These rules explain the *intent* behind the schema and cover the checks that are
enforced later at runtime (and therefore cannot be expressed in a static schema).

## Golden rules

1. **Generate a declarative scenario document only** — JSON or YAML that matches the schema. Never
   generate Java, Kotlin, Groovy, JavaScript, Python, shell, or any other code, and never embed code
   snippets, expressions, or scripts inside fields.
2. **Use logical aliases, never endpoints.** `service`, `topic`, `datasource`, and `target` are logical
   aliases resolved by the SDK. Never emit a URL, host, port, bootstrap-servers list, JDBC URL, or
   connection string.
3. **Every asynchronous wait has a bounded `timeout`.** `kafka.expect`, `db.expectEventually`, and
   `grpc.unary` require a positive, bounded `timeout` (`<n>ms` / `<n>s` / `<n>m`). Never `0`, negative,
   empty, or unbounded.
4. **`correlationId` is SDK-owned.** Request it with `correlation.inject: true` on the producing step
   and await it with `correlation.fromContext: true` on the consuming step. Never hardcode a correlation
   value.
5. **Never write secrets inline.** Do not put tokens, passwords, API keys, or `Authorization` headers in
   the document. Secrets come from secret references configured for the environment.
6. **Data flows through captured variables.** Capture values with `capture` (`variableName -> JSONPath`)
   and reference them as `${variableName}`. Only plain `${identifier}` references are allowed — no
   function calls, arithmetic, nested/script expressions, or shell-style `$(...)` substitutions.
7. **DB is a read-only probe layer.** In the AI schema, `db.expectEventually` runs a read-only `SELECT`.
   Never generate destructive SQL. DB seed/cleanup/writes are out of scope until a dedicated safety
   contract exists.
8. **Do not add unknown fields.** The schema is fail-closed (`additionalProperties: false`). Emit only
   the documented fields for each step type.
9. **Never bypass the ScenarioValidator.** Do not attempt to describe imperative, eager-IO behaviour or
   any escape hatch into raw clients — the format is intentionally declarative so the validator and
   guardrails always run.

## Built-in variables

These are provided by the SDK and may be referenced with `${...}`:

- `${scenarioId}`
- `${testRunId}`
- `${correlationId}`
- `${environment}`

## Supported step types (MVP)

| `type`                | Purpose                                              |
|-----------------------|------------------------------------------------------|
| `rest.get`            | Read-only REST call                                  |
| `rest.post`           | REST call that creates/triggers                      |
| `kafka.send`          | Publish a message to a topic alias                   |
| `kafka.expect`        | Await a message on a topic alias (requires `timeout`) |
| `db.expectEventually` | Read-only DB probe with polling (requires `timeout`) |
| `grpc.unary`          | DRAFT — shape only; execution ships later            |

## Assertions

Use simple declarative matchers only: `equals`, `exists`, `notNull`, `contains`, `matches`. Each
assertion targets a `path` (JSONPath). No expression language, no script/Java/Groovy/JS matchers.

## Forbidden operations (single source of truth: core `ForbiddenOperation`)

These codes mirror `ru.alfa.stand.test.core.validation.ForbiddenOperation` in `stand-test-core`. A
cross-check test fails the build if this list drifts from the enum. Layer legend: **schema** = rejected
structurally by the JSON Schema; **runtime** = enforced by the core `ScenarioValidator`/adapters;
**prompt** = advisory guidance you must follow because it cannot be checked statically.

| Code | Meaning | Layer |
|------|---------|-------|
| `THREAD_SLEEP` | No fixed sleeps/delays — the only wait is a declarative `timeout` on async steps. | schema |
| `FIXED_TEST_DATA_ID` | Do not hardcode test-data identifiers; generate or capture them. | prompt |
| `HARDCODED_STAND_URL` | No stand URLs/hosts — use environment and service aliases. | schema |
| `SECRET_IN_SOURCE` | No inline secrets (tokens/passwords/Authorization) — use secret references. | schema + runtime |
| `RAW_KAFKA_CLIENT` | No raw Kafka producer/consumer — only `kafka.send` / `kafka.expect`. | schema |
| `RAW_JDBC_CLIENT` | No raw JDBC — only the declarative `db.expectEventually` probe. | schema |
| `NON_WHITELISTED_ENVIRONMENT` | Use only whitelisted environment aliases (resolved at runtime). | runtime |
| `NON_WHITELISTED_DATASOURCE` | Use only whitelisted datasource aliases (resolved at runtime). | runtime |
| `DESTRUCTIVE_SQL_WITHOUT_ALLOW` | No `drop`/`truncate`/`delete`/`update`/`alter` — read-only `SELECT` only. | schema + runtime |
| `BUSINESS_LOGIC_IN_SDK` | Keep service-specific business logic out of the scenario/SDK. | prompt |
| `IMPERATIVE_EAGER_IO` | No imperative eager-IO — the document is fully declarative. | schema |

## Minimal valid example

```json
{
  "id": "example-flow",
  "title": "Example flow",
  "environment": "ift",
  "tags": ["integration"],
  "steps": [
    {
      "id": "create-request",
      "type": "rest.post",
      "service": "client-service",
      "path": "/api/requests",
      "correlation": { "inject": true },
      "body": { "json": { "amount": 100 } },
      "expect": { "status": 200 },
      "capture": { "requestId": "$.requestId" }
    },
    {
      "id": "await-response-event",
      "type": "kafka.expect",
      "topic": "response-topic",
      "correlation": { "fromContext": true },
      "timeout": "30s",
      "assert": [ { "path": "$.status", "equals": "SUCCESS" } ]
    }
  ]
}
```
