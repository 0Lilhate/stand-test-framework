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
   `grpc.unary` require a positive, bounded `timeout` (`<n>ms` / `<n>s` / `<n>m`, at most `99999ms` /
   `999s` / `60m`). Never `0`, negative, empty, or unbounded. The runtime validator independently caps
   every timeout/deadline at 1 hour, so a larger value is rejected even without a schema pass.
4. **`correlationId` is SDK-owned.** Outbound injection is **on by default** whenever the resolved
   endpoint (service/topic/gRPC target) declares a correlation carrier, so you normally omit
   `correlation` entirely on the producing step; set `correlation.inject: false` only to opt out. Await it
   with `correlation.fromContext: true` on the consuming step (matching is explicit). Never hardcode a
   correlation value.
5. **Never write secrets inline.** Do not put tokens, passwords, API keys, or `Authorization` headers in
   the document. Secrets come from secret references configured for the environment; when a service
   needs BASIC/BEARER authentication, the SDK injects the `Authorization` header itself from the
   service's registry-configured auth references — the document never mentions it.
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
| `rest.expectEventually` | GET-polls a path until the expectations hold (requires `timeout`; at least one of `expect.status`/`assert`; no body) |
| `kafka.send`          | Publish a message to a topic alias                   |
| `kafka.expect`        | Await a message on a topic alias (requires `timeout`) |
| `db.expectEventually` | Read-only DB probe with polling (requires `timeout`) |
| `grpc.unary`          | Unary gRPC call (requires `timeout`; executable subset — see below) |

## Assertions

Assertions attach to `kafka.expect`, `rest.get`/`rest.post`/`rest.expectEventually` (over the response
body), and, as a draft, `grpc.unary`, as `assert: [ { "path": "$.x", "equals": ... } ]`. Each targets a
`path` (JSONPath). **REST steps and `grpc.unary` execute all five matchers**: `equals` (type-aware equality),
`contains` (substring of a String value / element of a List value), `exists` (path presence — JSON null
counts as present; `exists: false` asserts absence), `notNull` (the present value is/is not JSON null)
and `matches` (full regex match over a String value). `kafka.expect`/`grpc.unary` still execute
`equals` only — the parser rejects the others there (see *Schema vs runtime* below). Use **definite**
JSONPaths with `exists`/`notNull` (`$..x`/`[*]` return a possibly-empty list, which reads as "present").
Keep `matches` regexes simple — they run in the test JVM. No expression language, no
script/Java/Groovy/JS matchers.

## REST query parameters

`rest.get`/`rest.post` accept an optional `query` object — a flat `string -> string` map of query
parameters (e.g. `"query": { "status": "NEW", "correlationId": "${correlationId}" }`). Values are plain
strings; use `${variable}` references, never endpoints or secrets. Non-string values are rejected.

## Forbidden operations (single source of truth: core `ForbiddenOperation`)

These codes mirror `ru.alfa.stand.test.core.validation.ForbiddenOperation` in `stand-test-core`. A
cross-check test fails the build if this list drifts from the enum. Layer legend: **schema** = rejected
structurally by the JSON Schema; **runtime** = enforced by the core `ScenarioValidator`/adapters;
**prompt** = advisory guidance you must follow because it cannot be checked statically.

| Code | Meaning | Layer |
|------|---------|-------|
| `THREAD_SLEEP` | No fixed sleeps/delays — the only wait is a declarative `timeout`; SQL sleep functions (`pg_sleep`, `sleep(`, `waitfor`, `benchmark`, `dbms_lock`) are rejected by the schema and again by the runtime validator. | schema + runtime |
| `FIXED_TEST_DATA_ID` | Do not hardcode test-data identifiers; generate or capture them. | prompt |
| `HARDCODED_STAND_URL` | No stand URLs/hosts — only environment/service aliases; `path` is relative (a leading `//host` is rejected). | schema |
| `SECRET_IN_SOURCE` | No inline secrets. Secret-bearing header *names* (Authorization/token/password/secret/api-key/cookie) are rejected by the schema and again by the runtime validator; `Bearer`/`Basic`-shaped header *values* under innocuous names are rejected at runtime. Any other secret value you inline cannot be detected statically — never emit one. | schema + runtime + prompt |
| `UNBOUNDED_TIMEOUT` | Every `timeout`/deadline is positive and bounded: schema caps per unit (`<=99999ms`/`<=999s`/`<=60m`); the runtime validator caps every declared timeout/deadline at 1 hour and rejects non-integer values. | schema + runtime |
| `RAW_KAFKA_CLIENT` | No raw Kafka producer/consumer — only `kafka.send` / `kafka.expect`. | schema |
| `RAW_JDBC_CLIENT` | No raw JDBC — only the declarative `db.expectEventually` probe. | schema |
| `NON_WHITELISTED_ENVIRONMENT` | Use only whitelisted environment aliases (resolved at runtime). | runtime |
| `NON_WHITELISTED_DATASOURCE` | Use only whitelisted datasource aliases (resolved at runtime). | runtime |
| `NON_WHITELISTED_SERVICE` | Use only whitelisted REST service aliases; the runtime validator rejects an unknown `service` alias pre-flight, before any step runs. | runtime |
| `NON_WHITELISTED_TOPIC` | Use only whitelisted Kafka topic aliases; the runtime validator rejects an unknown `topic` alias pre-flight, before any step runs. | runtime |
| `NON_WHITELISTED_GRPC_TARGET` | Use only whitelisted gRPC target aliases; the runtime validator rejects an unknown `target` alias pre-flight, before any step runs. | runtime |
| `DESTRUCTIVE_SQL_WITHOUT_ALLOW` | No `drop`/`truncate`/`delete`/`update`/`alter` — read-only `SELECT` only. | schema + runtime |
| `BUSINESS_LOGIC_IN_SDK` | Keep service-specific business logic out of the scenario/SDK. | prompt |
| `IMPERATIVE_EAGER_IO` | No imperative eager-IO — the document is fully declarative. | schema |

## Schema vs runtime (executable subset)

The JSON Schema describes the full space of *safe* documents; the current runtime executes a subset of it.
The parser (`AiScenarioParser`) fails closed on schema-valid constructs it cannot yet run, so prefer the
executable forms:

- **Bodies/payloads:** use `body.fixture` / `payload.fixture` (a classpath resource). Inline `body.json` /
  `payload.json` is not executable yet. `kafka.send` requires a `payload` (schema fail-closed, matching the
  runtime translator).
- **Assertions:** REST steps (`rest.get`/`rest.post`/`rest.expectEventually`) and `grpc.unary` execute all
  five matchers (`equals`/`contains`/`exists`/`notNull`/`matches`). For `kafka.expect` use `equals` — the
  other matchers are not executable there yet.
- **REST polling:** `rest.expectEventually` is fully executable: `timeout` is required (poll interval
  defaults to 200ms), at least one of `expect.status`/`assert` must be present, captures apply to the
  final satisfied response only.
- **REST query:** `query` is a `string -> string` map; the runtime passes it through as query parameters.
- **DB expectation:** use `expect.singleValue` (equals the first column). `expect.rowExists` is not
  executable yet.
- **gRPC:** `grpc.unary` is executable in a subset: `target`, `method`, `timeout` (→ deadline),
  `correlation.inject` (METADATA carrier), `request.fixture`, `expect.assert` with `equals`, and `capture`.
  Not executable yet (fail-closed): inline `request.json`, `expect.status` (the gRPC status is surfaced as
  an exception, not a declarative assertion), and non-`equals` matchers. The method is the fully-qualified
  `package.Service/Method`; the target service must expose gRPC Server Reflection.
- **Step ids** must be unique within a scenario. Uniqueness is enforced by the runtime `ScenarioValidator`,
  not by the schema, so keep them distinct.

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
      "body": { "fixture": "fixtures/create-request.json" },
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
