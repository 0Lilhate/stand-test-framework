# Safety checklist (guardrails)

The hard gate. Mirrors the runtime `ru.alfa.stand.test.core.validation.ForbiddenOperation`
enum plus review-only rules that have NO runtime enforcement. Every item is binary; any FAIL
blocks the workflow.

## Enforced at runtime too — but you must catch them statically

- [ ] **No hardcoded stand URLs / hosts / ports** (`HARDCODED_STAND_URL`) — only logical
      aliases; grep `https?://`, `host:port`, absolute `path` values. Sanctioned exception:
      the starter registry's endpoint value twins (`base-url`/`url`/`target`/
      `bootstrap-servers`/`security-protocol`) — verify each is a `${ENV_VAR:...}` placeholder,
      NOT a resolved endpoint (review-only rule; Spring resolves before the SDK sees it).
- [ ] **No JDBC strings / bootstrap servers in artifacts** (`RAW_JDBC_CLIENT` /
      `RAW_KAFKA_CLIENT` adjacent) — endpoints exist only as env-var refs (or the placeholder
      value twins above) in the registry.
- [ ] **No inline secrets** (`SECRET_IN_SOURCE`) — no header/metadata NAME matching
      `authorization|token|password|secret|api[-_]?key|cookie`; no VALUE shaped
      `Bearer …`/`Basic …`; auth only via registry `auth:` (a `*-ref` NAME, or a `${VAR:default}`
      value twin on the starter — the resolved secret then lives in the Spring Environment, so
      prefer `*-ref`). A BARE inline secret VALUE (no `${}`) in a starter credential value field is a
      finding; and a `${VAR}`/`${VAR:default}` placeholder inside a `*-ref` field is the
      double-resolution trap (Spring resolves it into a VALUE, then the ref is misread as a NAME —
      starter `*-ref` fields are bare env-var NAMES). Plain-JUnit has no value keys.
- [ ] **No non-whitelisted environment** (`NON_WHITELISTED_ENVIRONMENT`) — scenario
      environment is a registry key; production is never declared in a test registry.
- [ ] **No non-whitelisted datasource** (`NON_WHITELISTED_DATASOURCE`) — db steps name
      declared datasources only.
- [ ] **No destructive SQL / unsafe write** (`DESTRUCTIVE_SQL_WITHOUT_ALLOW`) — no
      DDL/TRUNCATE/MERGE/GRANT/REVOKE/upserts/multi-statement; writes only in
      `db.seed`/`db.cleanup` on `write-allowed` datasources into `allowed-schemas`,
      schema-qualified 2-part targets; cleanup SQL carries NO own WHERE and declares
      `whereTestRunId(column)`; every `db.seed` INSERT declares `taggedByTestRunId(column)` naming
      the SAME column (present in the INSERT column list bound to `:testRunId`) — the write-guard
      fails closed otherwise (rows would leak across concurrent runs).
- [ ] **Kafka expect discriminated** — every `kafka.expect` selects by a per-run-unique
      discriminator (`correlationIdFromContext`/`correlation: {fromContext: true}` or a
      `${...}`-derived key); a constant key alone is refused at run time (parallel-unsafe).
- [ ] **No sleeps** (`THREAD_SLEEP`) — no `Thread.sleep`/Awaitility/manual polling in Java;
      no `pg_sleep|sleep|waitfor|benchmark|dbms_lock` in SQL.
- [ ] **No unbounded timeouts** (`UNBOUNDED_TIMEOUT`) — every
      timeout/pollInterval/pollTimeout/deadline explicit, positive, ≤ 3 600 000 ms;
      AI grammar `≤99999ms / ≤999s / ≤60m`.

## Design rules — review is the ONLY net (no runtime check)

- [ ] **No fixed test-data ids** (`FIXED_TEST_DATA_ID`) — unique keys derive from
      `${testRunId}`; system-generated ids only via `capture`.
- [ ] **No eager IO in builders** (`IMPERATIVE_EAGER_IO`) — builders build; the only
      execution point is `stand.run(scenario)`.
- [ ] **No raw clients** (`RAW_KAFKA_CLIENT`/`RAW_JDBC_CLIENT`) — no
      WebClient/RestTemplate/HttpClient/KafkaProducer/KafkaConsumer/DriverManager/
      gRPC-stub imports in consumer tests.
- [ ] **No business logic in the SDK** (`BUSINESS_LOGIC_IN_SDK`) — the change set touches no
      SDK module source.
- [ ] **No validator/pipeline bypass** — no manual
      `new DefaultScenarioRunner(...)`/`new DefaultStandClient(...)` in consumer code
      (the list-only constructor wires an EMPTY registry); no Spring
      `ScenarioValidator`/`ScenarioRunner`/`StandClient` bean overrides; never use one-arg
      `validate(Scenario)` as a gate (it skips guardrails); no `stand.test.enabled=false`.
- [ ] **SDK-owned identities** — `testRunId`/`correlationId` never invented or hardcoded;
      correlation only via `injectCorrelationId()`/`correlation: {inject|fromContext}`.
- [ ] **Parallel-safe** — no shared mutable static/instance state in the test class (counters,
      captured values, reused builders); run-varying values flow through captures / `${testRunId}`;
      a class touching a non-`testRunId`-isolable resource (fixed port, shared file, global
      singleton) carries `@StandIsolated`/`@ResourceLock` (never `@StandParallelSafe` by default).
- [ ] **No caught SDK failures** — `StandTestAssertionError`/`StandTestException` are never
      caught to make a test pass; negative paths use `assertThatThrownBy` only.
- [ ] **No secrets trusted to Allure masking** — masking is best-effort with documented
      holes; artifacts are pre-redacted (i.e. secrets simply never appear).
- [ ] **No PII / production data** in fixtures, bodies, SQL, examples.
- [ ] **No unsanctioned dependencies** — additions limited to `allure-junit5:2.29.1`,
      a JSON-Schema 2020-12 validator (+ jackson) for the JSON track, the JDBC driver.

## One grep to start every sweep

```
grep -rnE 'Thread\.sleep|Awaitility|https?://|jdbc:|Authorization|Bearer |Basic |password|secret|api[-_]?key|cookie|pg_sleep|waitfor|benchmark|dbms_lock|DriverManager|KafkaConsumer|KafkaProducer|ManagedChannelBuilder|WebClient|RestTemplate|new DefaultScenarioRunner|new DefaultStandClient' <changed files>
```

(Expect hits only in registry `auth:`/`password-ref:` KEY names — verify each hit by eye.)
