# Rules: stand-test-sdk autotest generation guardrails

Non-negotiable rules for ANY agent work that creates or modifies stand-test autotests.
They mirror the runtime `ru.alfa.stand.test.core.validation.ForbiddenOperation` enum and the
review-only rules that have no runtime enforcement — including the parallel-safety guardrails
(`db.seed` tag column, `kafka.expect` per-run discriminator) that now FAIL CLOSED at run time.
Details and detection patterns: `.claude/skills/stand-test-safety-review/safety-checklist.md`.

## Process

- Analysis before generation: text case → `stand-test-case-analysis` → `stand-test-kb-lookup`
  (when the project keeps a knowledge base) → `stand-test-environment-mapping` →
  `stand-test-scenario-design` → only then authoring.
- Prefer a safe, recorded assumption over a question; escalate only blocking
  `Missing information` (missing alias, exact expected values for equals-only checks,
  write permission, correlation strategy, auth identity, unknown operation contract —
  method/path/fields/schema/table/gRPC method with no case-text value and no KB entry).
- Every generated artifact passes `stand-test-safety-review` and compiles / validates
  before it is shown for human approval. A human makes the merge decision.

## Hard constraints (violations BLOCK, never work around)

- Logical aliases only in scenarios/tests — never URLs, hosts, ports, JDBC strings, bootstrap
  servers. In the Spring-starter REGISTRY, value fields may carry Spring-resolved `${ENV_VAR:...}`
  placeholders: non-secret endpoints (`base-url`/`url`/`target`/`bootstrap-servers`/
  `security-protocol`) and — as a consumer trade-off — secret credentials (auth `password`/`token`,
  datasource `password`, Kafka `sasl-jaas-config`). A `${VAR:default}` secret twin works but
  materialises the resolved secret in the Spring Environment and puts the default in the file, so
  **prefer the `*-ref` spelling for secrets**. Two hard rules: a bare hardcoded value (no `${}`) in a
  value field is a review finding; and a `*-ref` field must be a bare env-var NAME — NEVER a `${...}`
  placeholder (on the starter Spring collapses it before the SDK sees the ref, which is then misread as
  a variable name — the double-resolution trap; no runtime check catches it).
- No secrets in scenarios/tests: no `Authorization`/token/cookie/api-key headers or Bearer/Basic
  values in a scenario/step/fixture/Java literal; auth comes from the registry (`auth:` with a `*-ref`
  env-var NAME, or a `${ENV_VAR:default}` value twin on the starter).
- No production environments in any test registry.
- No destructive SQL: no DDL/TRUNCATE/MERGE/upserts/multi-statement; DB writes only via
  `db.seed`/`db.cleanup` on `write-allowed` datasources into whitelisted schemas, scoped by
  `:testRunId` (`whereTestRunId`, no author WHERE in cleanup SQL).
- **DB write logic — when the case needs it, WRITE it (do not dodge into assumptions):**
  - *When*: the case requires precondition rows or test data that cannot be created through the
    system's API (the API path is preferred — scenario-design rule 11). Seeds/cleanups exist
    only on the Java DSL track (the AI format has no `db.seed`/`db.cleanup`).
  - *Preconditions first*: the datasource must have `write-allowed: true` in the registry
    (KB: `access.mode: write-allowed`) AND the target schema must be in `allowed-schemas`.
    Either missing ⇒ a blocking question to the human (registry/KB changes are human-approved) —
    never a workaround, never a silent downgrade of the case.
  - *Seed shape*: `INSERT` into a schema-qualified whitelisted table; the row carries a
    `test_run_id` column bound to the reserved `:testRunId` (auto-bound — never
    `param("testRunId", ...)`), AND the seed DECLARES that column with
    `taggedByTestRunId("<column>")` — the SAME column the paired cleanup filters. The write-guard
    fails closed at run time if the tag column is not declared, or does not appear in the INSERT
    column list bound to `:testRunId` (a seed tagging some other, non-reaped column would leak rows
    across concurrent runs — BLOCK). Data ids derive from `${testRunId}`/captures — a fixed literal
    primary key collides when two runs seed at once; table/column names trace to the
    KB/case/recorded assumption ("No invented contracts" applies).
  - *Every seed has a paired cleanup*: `db.cleanup` with a bare `DELETE FROM <schema>.<table>`
    (no author WHERE) + `whereTestRunId("<column>")` — the SAME `<column>` the seed tagged with
    `taggedByTestRunId`; step order seed → trigger → awaits → cleanup; cleanup does NOT run after a
    failed step (runner short-circuits) — a residual-data note in the javadoc/design is mandatory.
  - *Boundary*: writes touch TEST-SCOPED data only — never mutate the system's business rows
    (no state UPDATEs, no editing rows the test did not seed); changes the SYSTEM is expected
    to make are verified with read probes (`db.expectEventually`), not written by the test.
- No `Thread.sleep`, no Awaitility, no manual polling, no SQL sleep functions — async only
  via `rest.expectEventually` / `kafka.expect` / `db.expectEventually` (bounded `Awaiter`
  for rare utility waits).
- Every async wait has an explicit bounded timeout (≤ 1 hour; AI grammar
  `≤99999ms / ≤999s / ≤60m`).
- `testRunId`/`correlationId` are SDK-owned: never invented or hardcoded; uniqueness via
  `${testRunId}`; correlation via `injectCorrelationId()` / `correlation: {inject|fromContext}`.
- `kafka.expect` selects by a per-run-UNIQUE discriminator: `correlationIdFromContext()` (the
  SDK-owned unique id) or a `key(...)` that is per-run-derived (contains a `${...}` placeholder,
  e.g. `key("${testRunId}")` / AI format `correlation: {fromContext: true}`). A constant key alone
  is refused at run time — two concurrent runs would match each other's messages on a shared topic
  (BLOCK); a constant key is allowed ONLY alongside `correlationIdFromContext()`, where it merely
  narrows among the run's own correlated messages.
- Parallel-safe by construction: generated tests must be safe under JUnit in-JVM parallel execution
  (SDK model — classes `concurrent`, methods `same_thread`, `maxParallelForks=1`, one scenario run
  = one thread; distinct runs get distinct `testRunId`/`correlationId`/`VariableStore`/Kafka group).
  No shared mutable static or instance state in the test class — every run-varying value flows
  through captures / `${testRunId}`. Do NOT add `@StandParallelSafe` by default (isolation + the
  consumer's `junit-platform.properties` make the class parallel-safe); mark a test `@StandIsolated`
  or `@ResourceLock("<alias>")` ONLY when it touches a resource that cannot be `testRunId`-isolated
  (a fixed port, a shared file, a process-wide singleton). Never Gradle `maxParallelForks>1`.
- No fixed test-data ids; system-generated ids only via `capture` → `${var}`. No stale statics
  in test data generally: run-unique fields derive from `${testRunId}`/captures, date-like
  values are computed in plain Java at run time (Java track) — never calendar literals that
  expire or collide across runs.
- No invented contracts: every endpoint path, JSON field, topic, table/column, SQL statement and
  gRPC method in a generated artifact traces to the knowledge base
  (`knowledge-base/`, contract: `docs/ai-agent/knowledge-base/`), to the case
  text, or to a recorded assumption — an unsourced contract detail is a BLOCK finding. The KB
  itself stores refs/aliases/contracts only (schema-enforced): no secrets, no URLs, no
  production environments.
- No pipeline bypass: no eager IO in builders, no raw HTTP/Kafka/JDBC/gRPC clients, no
  `new DefaultScenarioRunner(...)` / `new DefaultStandClient(...)` in consumer code, no
  validator/runner/StandClient bean overrides, never the one-arg `validate(Scenario)` as a
  guardrail gate (structural-only).
- Never catch `StandTestAssertionError`/`StandTestException` to make a test pass; negative
  paths only via `assertThatThrownBy`.
- Never hide a failure: no assertion deletion, no `@Disabled` without a ticket, no blind
  timeout inflation.
- Do not modify SDK modules, add adapters, or change core APIs while authoring tests; new
  consumer dependencies limited to `allure-junit5:2.29.1`, a JSON-Schema 2020-12 validator
  (+ jackson) for the JSON track, and the JDBC driver — each human-approved.

## Definition of done for a generated test

- Java: compiles + checkstyle-clean; gated with `@EnabledIfEnvironmentVariable` (skips
  without stand config); AssertJ-only assertions.
- AI-format document: JSON Schema validation EMPTY + `AiScenarioParser` parse clean +
  `DefaultScenarioValidator().validate(scenario, registry)` clean.
- Safety review PASS + quality review APPROVE + human approval on the validation report.
