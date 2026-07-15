# KB entry review checklist

Apply to EVERY added or updated entry before the change is offered for human approval.
Binary items; any FAIL blocks the update.

## Identity and traceability

- [ ] `id`/`alias` are stable kebab-case, derived mechanically from the source, not hand-invented.
- [ ] The entry cites a real source (the report's "Derived from" column is filled).
- [ ] No existing id/alias was renamed or reused for a different contract.

## Contract correctness

- [ ] Endpoint: method+path match the source verbatim; success status and required fields taken
      from the source, not assumed.
- [ ] Kafka topic: direction matches publish/subscribe semantics of the source; correlation is
      HEADER with a named header; timeout present and bounded.
- [ ] DB probe: single-statement lowercase `select`, named `:params`, single-value expectation,
      bounded timeout; scoped by `:testRunId` or a captured unique id where rows are run-scoped.
- [ ] gRPC: `fullMethodName` matches the proto; method type is `unary`; deadline present.
- [ ] Correlation/auth sit on the SERVICE entry, not on endpoints.

## Safety

- [ ] No URLs, hosts, ports, JDBC strings anywhere — including descriptions.
- [ ] Every `*Ref` is an env-var NAME (`^[A-Z][A-Z0-9_]{2,63}$`); no values, no `${VAR:default}`
      credential defaults.
- [ ] No production environments, ids or names (schema rejects `prod|prd|live` — do not work around).
- [ ] Real topic names appear only under `environments[].kafka.topics[].actualName`.

## Consistency

- [ ] JSON Schema passes for the touched file (run it, do not eyeball).
- [ ] Owning service rollup lists the new child id; the child names its owner id.
- [ ] Every `allowedEnvironments` entry has (or is reported as needing) an environment binding.
- [ ] The KB validation tests pass after the change.
