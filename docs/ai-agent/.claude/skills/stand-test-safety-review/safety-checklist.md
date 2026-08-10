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
      `Bearer …`/`Basic …`; auth only via registry `auth:` refs. No CREDENTIAL value twins
      (`user`/`password`/`username`/`token`/`sasl-jaas-config`) in generated artifacts — the
      starter accepts them, kit policy keeps secrets `*-ref`-only; on the starter surface a
      `${VAR}` placeholder inside a `*-ref` is also a finding (Spring resolves it into a VALUE
      — starter refs are bare env-var NAMES).
- [ ] **No non-whitelisted environment** (`NON_WHITELISTED_ENVIRONMENT`) — scenario
      environment is a registry key; production is never declared in a test registry.
- [ ] **No non-whitelisted datasource** (`NON_WHITELISTED_DATASOURCE`) — db steps name
      declared datasources only.
- [ ] **No destructive SQL / unsafe write** (`DESTRUCTIVE_SQL_WITHOUT_ALLOW`) — no
      DDL/TRUNCATE/MERGE/GRANT/REVOKE/upserts/multi-statement; writes only in
      `db.seed`/`db.cleanup`/`db.write` on `write-allowed` datasources into `allowed-schemas`,
      schema-qualified 2-part targets; cleanup SQL carries NO own WHERE and declares
      `whereTestRunId(column)`; every `db.seed` INSERT declares `taggedByTestRunId(column)` naming
      the SAME column (present in the INSERT column list bound to `:testRunId`) — the write-guard
      fails closed otherwise (rows would leak across concurrent runs).
      `db.write` is SANCTIONED, not a finding: it is the shape for a business table with no marker
      column, and it must declare `identifiedBy(<pk>)` with every named column bound as `:<column>`
      in the INSERT (refused at run time otherwise, so nothing un-undoable reaches the stand). It is
      reaped by the run's undo-log per `cleanupPolicy`, so a `db.write` WITHOUT a paired
      `db.cleanup` is correct — do not raise it as a missing cleanup.
- [ ] **Kafka expect discriminated** — every `kafka.expect` selects by a per-run-unique
      discriminator (`correlationIdFromContext`/`correlation: {fromContext: true}` or a
      `${...}`-derived key); a constant key alone is refused at run time (parallel-unsafe).
- [ ] **No sleeps** (`THREAD_SLEEP`) — no `Thread.sleep`/Awaitility/manual polling in Java;
      no `pg_sleep|sleep|waitfor|benchmark|dbms_lock` in SQL.
- [ ] **No unbounded timeouts** (`UNBOUNDED_TIMEOUT`) — every
      timeout/pollInterval/pollTimeout/deadline explicit, positive, ≤ 3 600 000 ms;
      AI grammar `≤99999ms / ≤999s / ≤60m`.

## Design rules — review is the ONLY net (no runtime check)

- [ ] **No fixed test-data ids / hardcoded entity-instance-handles** (`FIXED_TEST_DATA_ID`) —
      unique keys derive from `${testRunId}`; system-generated ids only via `capture`. Covers
      case-supplied INSTANCE handles (client id, pinEQ, account id/number, deal id, an approved-ТУ
      instance): a value that identifies ONE specific stateful row the SUT resolves is NOT exempt
      because it came from the case — reframing it as "a pointer to a stand object, not created
      data" is exactly the blind spot, not a defense (not `testRunId`-isolated; couples the run to
      out-of-band state). Test-ownable handles are provisioned+captured in-scenario (or `db.seed`d
      into a write-allowed whitelisted schema when no create-endpoint exists); shared catalog rows
      (ТУ) are out-of-band + read-probe-verified; a test-ownable entity is blocking missing-info
      only when NEITHER a create-endpoint nor a seedable write-allowed schema exists. EXEMPT
      (constants, not handles): reference/dictionary CODES (service/ПУ/branch/currency), monetary
      amounts / numeric business constants, and expected-assertion literals. Detection: grep
      fixtures/bodies/paths for id-shaped literals `[0-9]{6,}` (account numbers `\b40[0-9]{16,18}\b`)
      and 6-char PIN tokens `\b[A-Z0-9]{6}\b`, then eye-check each hit against the SOURCE CASE TEXT —
      a value that also appears in the case is a copied real id → BLOCK (curated
      `valueHints: entity-handle` — the KB enum value for an entity-instance-handle — or
      case-labelled) or HIGH (shape heuristic only,
      must-confirm). A residual note deferring provisioning of a TEST-OWNABLE entity to a DBA runbook
      is a positive signal, not an excuse.
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

## Needs the artifact's PREVIOUS version — the one item a single read cannot answer

- [ ] **No failure concealment** (`FAILURE_CONCEALMENT`, finding 18) — this is the only item on this
      page that cannot be decided from the file in front of you: a deleted assertion is not in the
      file, and an inflated timeout looks exactly like a timeout. It needs both versions, which the
      write hook has (the file on disk vs the content about to replace it) and CI supplies with
      `scan --against <base>` (`git show origin/main:<path>`). Four signals, and they are not equally
      certain: **fewer assertions than before** and **a `@Disabled`/`@Ignore` with no ticket** BLOCK;
      **a new `catch`** and **a timeout raised while the number of waits stayed the same** are
      HEURISTIC/HIGH — legitimate work can produce either, so they are for a human to confirm.
      Most relevant on a REGENERATION and on any hand-edit of a merged test. With neither version
      available the item is **NOT RUN**, and the report says so — never "clean".

## One grep to start every sweep

```
grep -rnE 'Thread\.sleep|Awaitility|https?://|jdbc:|Authorization|Bearer |Basic |password|secret|api[-_]?key|cookie|pg_sleep|waitfor|benchmark|dbms_lock|DriverManager|KafkaConsumer|KafkaProducer|ManagedChannelBuilder|WebClient|RestTemplate|new DefaultScenarioRunner|new DefaultStandClient' <changed files>
```

(Expect hits only in registry `auth:`/`password-ref:` KEY names — verify each hit by eye.)

Copied-business-id heuristic (the credential grep above misses these — they are not
credential-shaped). Verify EVERY hit against the SOURCE CASE TEXT: a hit that also appears in the
case is a copied entity-instance-handle → finding (see `FIXED_TEST_DATA_ID` above):

```
grep -rnE '[0-9]{6,}|\b[A-Z0-9]{6}\b' <changed fixtures/bodies/paths>
```

(A hit derived from `${testRunId}`/a capture, a reference/dictionary CODE, a monetary amount /
numeric business constant, or an expected-assertion literal is clean; a real client/account/pin/deal
id transcribed from the case is a BLOCK.)
