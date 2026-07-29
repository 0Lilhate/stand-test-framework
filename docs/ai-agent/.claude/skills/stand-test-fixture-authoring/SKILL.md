---
name: stand-test-fixture-authoring
description: Create safe classpath fixture files for stand-test scenarios (REST/Kafka bodies, gRPC protobuf-JSON requests) with ${testRunId}/${correlationId} placeholders, generic test data, no secrets/PII/production values. Use whenever a scenario references body/payload/request fixtures.
version: 1
---

# Skill: stand-test-fixture-authoring

Create the classpath fixture files referenced by scenarios (`body`/`payload`/`request`
`fixture:` in the AI format; `bodyFromResource`/`requestFromResource` in the Java DSL).

## When to use

Whenever an authoring skill references a fixture path. **Every referenced fixture must be
emitted in the same change** — a missing fixture is a runtime `StandTestException`.

## Where fixtures live

Consumer project test resources, referenced by a **relative, dot-dot-free** path:

```
src/test/resources/fixtures/<scenario-id or domain>/<name>.json
```

The AI schema rejects `..` and leading `/` in fixture paths; keep the same discipline in Java.

## Content rules

1. **JSON for REST/Kafka bodies; protobuf-JSON for gRPC requests** (field names as in the
   proto; enums as protobuf JSON names; the request must match the method's input type —
   `JsonFormat` parsing fails otherwise).
2. **Placeholders resolve inside fixtures at execution time** — use them:
   - `${testRunId}` for uniqueness of created entities;
   - `${correlationId}` where the payload mirrors the correlation value;
   - `${capturedVar}` for values produced by earlier steps.
   Remember substitution is textual: a placeholder inside `"..."` produces a JSON string.
   Keep numeric fields literal numbers (no placeholder) unless the consumer tolerates strings.
3. **Generic test data only**:
   - no real personal data (names, phone numbers, account numbers, PINs) — use obvious
     synthetics (`"customerName": "Test Customer ${testRunId}"`);
   - no production values, no real business identifiers copied from tickets;
   - no secrets, tokens, passwords, cookies — in any field, ever. The Allure sink masks
     best-effort only (documented holes for XML/nested objects) — **pre-redaction is your job**;
   - amounts/quantities: small round numbers (`1`, `100`).
4. **No fixed unique ids**: any field the system treats as a unique key derives from
   `${testRunId}` (`FIXED_TEST_DATA_ID` guardrail — enforced by review, not runtime).
5. One fixture per request shape; do not share one fixture across steps with different
   placeholder needs.

## Template

```json
{
  "externalId": "order-${testRunId}",
  "amount": 100,
  "currency": "EUR",
  "customerName": "Test Customer",
  "comment": "created by stand-test scenario ${scenarioId}"
}
```

See [`fixture-template.json`](../stand-test-fixture-authoring/fixture-template.json).

## Forbidden

- Secrets / credentials / `Authorization`-like values in any field.
- Real URLs, hosts, JDBC strings, bootstrap servers inside payloads.
- Copying response payloads captured from production systems.
- Binary content (fixtures are UTF-8 text; Kafka values are string-serialized JSON).

## Checklist

- [ ] Every `fixture:`/`*FromResource` reference has a file; paths relative, no `..`.
- [ ] Valid JSON (parse it); for gRPC — field names match the proto request type.
- [ ] Unique-key fields use `${testRunId}`; no fixed ids.
- [ ] No secrets/PII/production values (grep: `password|token|secret|Bearer|Basic |api[-_]key`).
- [ ] Placeholders used are produced earlier in the scenario or are built-ins.
