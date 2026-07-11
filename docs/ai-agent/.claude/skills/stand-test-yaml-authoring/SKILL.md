---
name: stand-test-yaml-authoring
description: Generate an AI-format (steps/type) JSON/YAML scenario for stand-test-sdk from a scenario design, conforming to the stand-test-ai-schema JSON Schema and its executable subset (7 step types, equals-only outside REST, fixture-only bodies, bounded timeouts, logical aliases). Use when the design's track is the declarative AI format.
---

# Skill: stand-test-yaml-authoring

Generate an AI-format (`steps/type`) JSON or YAML scenario document from a `ScenarioDesign.md`,
conforming to the `stand-test-ai-schema` JSON Schema and — more importantly — to the
**executable subset**.

## Source of truth

- Schema: `/schema/stand-test-scenario.schema.json` in the `stand-test-ai-schema` jar
  (`AiSchemaResources.SCHEMA_RESOURCE`).
- Rules: `/ai/stand-test-ai-generation-rules.md` (`AiSchemaResources.GENERATION_RULES_RESOURCE`).
- Golden examples: `stand-test-ai-schema/src/test/resources/examples/valid/*.json`
  (canonical flow: `rest-kafka-db-flow.json`) and the runnable parity fixtures in
  `stand-test-example/src/test/resources/ai/canonical-flow.json`.

**On any conflict between this file and those resources, the resources win.** Do not restate
the rules elsewhere — link here.

## When to use

The design says track = AI format (every step inside the executable subset).

## Document shape

```json
{
  "id": "kebab-case-id",
  "environment": "ift",            // top-level key is "environment" — NOT "env"
  "title": "optional human title",
  "tags": ["integration"],
  "steps": [ { "id": "...", "type": "...", ... } ]
}
```

JSON and YAML are both accepted (`AiScenarioParser` loads JSON as a YAML subset). Prefer JSON —
it is what the schema examples use. Every object is `additionalProperties: false`: **any field
not in the schema fails validation.**

## Per-type field crib (executable subset only)

```jsonc
// rest.post / rest.get           required: id, type, service, path
{ "id": "create-order", "type": "rest.post", "service": "order-service",
  "path": "/api/orders", "headers": {"Content-Type": "application/json"},
  "correlation": {"inject": true},
  "body": {"fixture": "fixtures/create-order.json"},     // NEVER {"json": {...}} — rejected by parser
  "expect": {"status": 200},
  "assert": [{"path": "$.status", "equals": "ACCEPTED"}],  // all 5 matchers OK on rest.*
  "capture": {"orderId": "$.orderId"} }

// rest.expectEventually          required: id, type, service, path, timeout (+ expect and/or assert)
{ "id": "await-processed", "type": "rest.expectEventually", "service": "order-service",
  "path": "/api/orders/${orderId}", "timeout": "30s",
  "expect": {"status": 200}, "assert": [{"path": "$.status", "equals": "DONE"}] }
  // GET-only: no body field exists; no pollInterval on this surface

// kafka.send                     required: id, type, topic, payload
{ "id": "send-command", "type": "kafka.send", "topic": "order-commands",
  "key": "${correlationId}", "correlation": {"inject": true},
  "payload": {"fixture": "fixtures/command.json"} }

// kafka.expect                   required: id, type, topic, timeout, assert
{ "id": "await-order-event", "type": "kafka.expect", "topic": "order-events",
  "correlation": {"fromContext": true}, "timeout": "30s",   // per-run discriminator REQUIRED: fromContext,
  "assert": [{"path": "$.status", "equals": "CREATED"}],     //  or a "key": "${testRunId}..." — a constant
  "capture": {"eventId": "$.eventId"} }                       //  key is refused at run time (parallel-unsafe)

// db.expectEventually            required: id, type, datasource, timeout, query, expect
{ "id": "verify-projection", "type": "db.expectEventually", "datasource": "orders-db",
  "timeout": "10s",
  "query": "SELECT status FROM orders WHERE order_id = :orderId",   // SELECT-only
  "params": {"orderId": "${orderId}"},
  "expect": {"singleValue": "DONE"} }                      // NEVER rowExists — rejected

// grpc.unary                     required: id, type, target, method, timeout
{ "id": "charge", "type": "grpc.unary", "target": "billing-grpc",
  "method": "billing.BillingService/Charge",               // exactly package.Service/Method
  "correlation": {"inject": true},
  "request": {"fixture": "fixtures/charge-request.json"},  // NEVER {"json": ...}
  "timeout": "5s",
  "expect": {"assert": [{"path": "$.status", "equals": "OK"}]},  // assert NESTED under expect; NEVER expect.status
  "capture": {"chargeId": "$.chargeId"} }
```

## Hard rules

1. Only the seven schema step types; `db.seed`/`db.cleanup`/`db.query`/`rest.put`/`rest.delete`
   do not exist here — if the design needs them, STOP and switch to the Java track.
2. `timeout` on every async step; grammar `<n>ms` (1–99999) / `<n>s` (1–999) / `<n>m` (1–60).
   No `0s`, no `24h`, no fractions, no combined units.
3. `service`/`topic`/`datasource`/`target` are logical aliases matching
   `^[A-Za-z][A-Za-z0-9._-]*$` — a URL/JDBC string/host:port fails the pattern by design.
4. `path` starts with a single `/` (absolute and protocol-relative URLs are rejected).
5. No secret-bearing header names (`authorization|token|password|secret|api[-_]?key|cookie`) —
   the schema rejects them via `propertyNames`; auth lives in the registry.
6. SQL: starts with `SELECT`, single statement, no
   `DROP|TRUNCATE|DELETE|UPDATE|ALTER|INSERT|MERGE|GRANT|REVOKE|PG_SLEEP|SLEEP|WAITFOR|BENCHMARK|DBMS_LOCK`.
   Values only via `:name` binds + `params` map.
7. Assertions: exactly one matcher key per item (`equals|exists|notNull|contains|matches`);
   `equals: null` is forbidden (use `exists`/`notNull`); non-equals only on `rest.*` steps.
8. Data flow only via `capture` (keys `^[A-Za-z_][A-Za-z0-9_]*$`, values start with `$`) and
   `${identifier}` references. No expressions, functions, `$()`, nesting.
9. Query param values are strings (`{"page": "1"}`, not `{"page": 1}`); `rest.get` has no body.
10. Step ids unique across the document (schema does NOT check this — runtime does).
11. Fixture paths: relative, no leading `/`, no `..`. Emit every referenced fixture file
    (delegate content to `stand-test-fixture-authoring`).
12. `kafka.expect` MUST carry a per-run discriminator — `"correlation": {"fromContext": true}` or a
    `"key"` derived from `${testRunId}`/`${correlationId}`. A constant key with no `fromContext` is
    refused at run time (two concurrent runs would match each other's messages on a shared topic).
    This keeps the document parallel-safe; AI documents have no `db.seed`, so seed tagging is a
    Java-track concern (rule 1).

## Mandatory validation gate (in this order)

1. **JSON Schema** — networknt `V202012` against `AiSchemaResources.scenarioSchemaJson()`:
   message set must be EMPTY. (Consumer test classpath needs
   `com.networknt:json-schema-validator:1.5.6` + `jackson-databind` — the SDK does not export
   a validator.)
2. **Parse gate** — `new AiScenarioParser().parse(document)` must not throw (this is what
   catches schema-valid-but-not-executable constructs).
3. **Guardrail self-check** — `new DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid()`
   with the project's `EnvironmentRegistry` (e.g. `new FileEnvironmentRegistry()` from
   `stand-test-config`). The single-argument `validate(scenario)` overload is
   structural-only and skips ALL guardrails — never use it as a guardrail gate.
4. `stand-test-safety-review`.

If gate 2 throws `StandTestException` naming a construct ("not executable yet", "only
executable for REST assertions"), fix the document or fall back to the Java track — never
work around the parser.

## How the document is executed

```java
@StandTest
class OrderFlowFromDocumentTest {
    @Test
    void runsDeclarativeFlow(StandClient stand) {
        Scenario scenario = new AiScenarioParser().parseResource("ai/order-flow.json");
        ScenarioResult result = stand.run(scenario);   // validator runs inside
        assertThat(result.isSuccessful()).isTrue();
    }
}
```

Classpath must contain `stand-test-junit`, `stand-test-scenario-yaml`, one adapter module per
step type used, and `stand-test-config` (or the Spring starter equivalent).

## Output

- `src/test/resources/ai/<scenario-id>.json` (consumer project)
- `src/test/resources/fixtures/*.json` for every `fixture:` reference
- the 3-line runner test above (unless one already exists for the directory)

## Example

[`example-generated.yaml`](../stand-test-yaml-authoring/example-generated.yaml).
Negative corpus to test your own generator against:
`stand-test-ai-schema/src/test/resources/examples/invalid/*.json`.
