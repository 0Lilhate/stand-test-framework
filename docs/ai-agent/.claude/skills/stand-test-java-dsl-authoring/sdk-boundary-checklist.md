# SDK boundary checklist

The agent authors CONSUMER tests. It never crosses into the SDK. Verify on every change set.

## Untouchable

- [ ] No file under any `stand-test-*` module's `src/main/**` is modified.
- [ ] No new `StepExecutor`/adapter implementation added to run a business test
      (`GenericStep` probes like the example module's `VariableSnapshotProbe` are an
      SDK-example pattern, not a consumer-test tool).
- [ ] No core API change, no new wire keys, no `ForbiddenOperation` edits, no schema edits
      (`stand-test-scenario.schema.json` belongs to the SDK).
- [ ] No SDK version bumps / publishing config changes smuggled into a test change.

## Only sanctioned entry points used

- [ ] Scenario building: `Scenario.builder(...)` + `RestStep`/`KafkaStep`/`DbStep`/`GrpcStep`
      builders (or `AiScenarioParser`/`YamlScenarioParser` for documents). No hand-built
      `GenericStep` parameter maps for kafka/grpc in consumer tests — the raw wire path
      silently ignores a `matcher` key (degrades to equals without an error).
- [ ] Execution: injected `StandClient` only (`@Autowired` via starter, or `@StandTest`
      parameter). No `new DefaultScenarioRunner(...)`, no `new DefaultStandClient(...)`,
      no manual `ServiceLoader` calls.
- [ ] Validation: implicit via `stand.run(...)`; optional self-check uses the
      REGISTRY overload `validate(scenario, registry)` — never the one-arg overload as a gate.
- [ ] Waiting: `*.expectEventually` / `KafkaStep.expect`; direct `Awaiter` only for rare
      utility waits with a bounded `AwaitPolicy`.
- [ ] Reporting: none — Allure attaches via SPI; the test writes zero reporting code and
      never asserts on report content.

## Configuration stays configuration

- [ ] Endpoints/credentials appear ONLY as env-var references in the registry
      (`stand-test-environments.yml` or `stand.test.environments.*`), never in test code or
      fixtures.
- [ ] Registry changes are separate, human-approved edits — never bundled silently into a
      generated test commit.
- [ ] Exactly ONE `EnvironmentRegistry` provider on the test classpath (config module XOR a
      hand-written provider XOR the starter's bean — never two).
- [ ] Dependency additions limited to the sanctioned list and human-approved.

## Failure semantics respected

- [ ] `StandTestAssertionError` = failed expectation; `StandTestException` = infra/config.
      The test never re-maps, wraps, or suppresses either.
- [ ] `ScenarioResult` is used for the happy-path `isSuccessful()` assert and diagnostics —
      never as a silent failure channel (the runner throws; `StepStatus.FAILED` is a
      reporting record).
