# Checklist: before generating a test

Run BEFORE any authoring skill. If any box cannot be ticked, stop and resolve — do not
generate "to see how it looks".

## Analysis complete

- [ ] `TestCaseAnalysis.md` exists and every section is filled (or explicitly `n/a`).
- [ ] All BLOCKING missing-information items are answered by a human.
- [ ] All assumptions are written down (none live only in your head).
- [ ] Every NOT-AUTOMATABLE check has a disposition (reword / manual / drop with reason).

## Environment resolved

- [ ] Environment alias exists in the consumer registry (exact key match).
- [ ] Every service/topic/datasource/grpc-target the design uses is resolved to a registry
      alias — or its addition was human-approved and applied.
- [ ] Correlation config verified per alias for every planned inject/fromContext
      (HEADER for REST/Kafka topics; METADATA for gRPC; Kafka is HEADER-only).
- [ ] For any DB write: datasource has `write-allowed: true` and the target schema is in
      `allowed-schemas`.
- [ ] The list of env vars (`*-ref` names) needed at run time is known — one of them chosen
      for the `@EnabledIfEnvironmentVariable` gate.

## Design sound

- [ ] `ScenarioDesign.md` exists; step table complete (id, type, alias, timeout, assertions,
      captures).
- [ ] Track chosen; if AI format — every step verified against the executable subset
      (7 types; equals-only outside REST; fixture-only bodies; no seed/cleanup/put/delete).
- [ ] Every async expectation has an explicit bounded timeout (≤ 1h; AI grammar
      `≤99999ms / ≤999s / ≤60m`).
- [ ] Every `${var}` consumed is produced earlier or is a built-in
      (`scenarioId|testRunId|correlationId|environment`).
- [ ] Kafka trigger and `kafka.expect` are in the SAME scenario.
- [ ] Every seed has a `whereTestRunId`-scoped cleanup; seeded rows carry `test_run_id`.

## Workspace sane

- [ ] Consumer project identified (build file, test source root, base package, existing
      template tests read).
- [ ] Required SDK modules on the consumer test classpath for every step type used
      (missing adapter = runtime "No step executor registered").
- [ ] You are NOT about to modify SDK runtime code, add adapters, or touch core API.
