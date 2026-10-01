---
version: 1
---

# Rules: stand-test-sdk autotest generation guardrails

Non-negotiable rules for ANY agent work that creates or modifies stand-test autotests.
They mirror the runtime `ru.alfa.stand.test.core.validation.ForbiddenOperation` enum and the
review-only rules that have no runtime enforcement — including the parallel-safety guardrails
(`db.seed` tag column, `kafka.expect` per-run discriminator) that now FAIL CLOSED at run time.
Details and detection patterns: `.claude/skills/stand-test-safety-review/safety-checklist.md`.

Which layer enforces each rule, and what it defends against:
[`../reference/stand-test-guardrails-rationale.md`](../reference/stand-test-guardrails-rationale.md)
— not auto-loaded, read it before CHANGING a rule and edit the two together.

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
  servers. In the Spring-starter REGISTRY the sanctioned exception is the endpoint value twins
  (`base-url`/`url`/`target`/`bootstrap-servers`/`security-protocol`), and even there only as
  `${ENV_VAR:...}` placeholders — a hardcoded endpoint in a value field is a review finding, caught
  by no runtime check.
- No secrets anywhere: no `Authorization`/token/cookie/api-key headers or Bearer/Basic
  values; auth comes from the registry (`auth:` with `*-ref` env-var NAMES).
- No production environments in any test registry.
- No destructive SQL: no DDL/TRUNCATE/MERGE/upserts/multi-statement; DB writes only via
  `db.seed`/`db.cleanup`/`db.write` on `write-allowed` datasources into whitelisted schemas —
  `db.seed` scoped by `:testRunId` (`whereTestRunId`, no author WHERE in cleanup SQL), `db.write`
  scoped by the primary key it declares in `identifiedBy(...)` and undone by the run's undo-log.
- **DB write logic — when the case needs it, WRITE it (do not dodge into assumptions):**
  - *When*: the case requires precondition rows or test data that cannot be created through the
    system's API (the API path is preferred — scenario-design rule 11). DB writes exist only on the
    Java DSL track (the AI format has no `db.seed`/`db.cleanup`/`db.write`).
  - *Which shape*: the TARGET TABLE decides. A table carrying a `test_run_id` marker column →
    `db.seed` + a paired `db.cleanup` (below). A table WITHOUT one — an ordinary business table —
    → `db.write` with `identifiedBy("<pk>")`, undone after the run by a primary-key compensation in
    the run's undo-log; no marker column and no paired cleanup are needed. `identifiedBy` is
    mandatory there and every column it names must be bound as `:<column>` in the INSERT. Timing is
    the scenario's `cleanupPolicy`: `ON_FAILURE` (default), `ALWAYS`, `NEVER`. "No marker column" is
    therefore NOT a reason to declare the case blocked.
  - *Preconditions first*: the datasource must have `write-allowed: true` in the registry
    (KB: `access.mode: write-allowed`) AND the target schema must be in `allowed-schemas`.
    Either missing ⇒ a blocking question to the human (registry/KB changes are human-approved) —
    never a workaround, never a silent downgrade of the case.
  - *Seed shape*: `INSERT` into a schema-qualified whitelisted table; the row carries a
    `test_run_id` column bound to the reserved `:testRunId` (auto-bound — never
    `param("testRunId", ...)`), AND the seed DECLARES that column with
    `taggedByTestRunId("<column>")` — the SAME column the paired cleanup filters. The write-guard
    fails closed if the tag column is not declared or does not appear in the INSERT column list
    bound to `:testRunId`. Data ids derive from `${testRunId}`/captures; table/column names trace to
    the KB/case/recorded assumption ("No invented contracts" applies).
  - *Every seed has a paired cleanup*: `db.cleanup` with a bare `DELETE FROM <schema>.<table>`
    (no author WHERE — the SDK appends the filter) + `whereTestRunId("<column>")`, the SAME
    `<column>` the seed tagged; step order seed → trigger → awaits → cleanup; cleanup does NOT run
    after a failed step — a residual-data note in the javadoc/design is mandatory.
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
  is refused at run time (BLOCK); it is allowed ONLY alongside `correlationIdFromContext()`, where
  it merely narrows among the run's own correlated messages.
- Parallel-safe by construction: generated tests must be safe under JUnit in-JVM parallel execution
  (SDK model — classes `concurrent`, methods `same_thread`, `maxParallelForks=1`, one scenario run
  = one thread; distinct runs get distinct `testRunId`/`correlationId`/`VariableStore`/Kafka group).
  No shared mutable static or instance state in the test class — every run-varying value flows
  through captures / `${testRunId}`. Do NOT add `@StandParallelSafe` by default; mark a test
  `@StandIsolated` or `@ResourceLock("<alias>")` ONLY when it touches a resource that cannot be
  `testRunId`-isolated (a fixed port, a shared file, a process-wide singleton). Never Gradle
  `maxParallelForks>1`.
- No fixed test-data ids; system-generated ids only via `capture` → `${var}`. No stale statics
  in test data generally: run-unique fields derive from `${testRunId}`/captures, date-like
  values are computed in plain Java at run time (Java track) — never calendar literals that
  expire or collide across runs. This ban covers case-supplied ENTITY-INSTANCE-HANDLES — a value
  that identifies ONE specific stateful row the SUT resolves at run time (client id, pinEQ,
  account id/number, deal/contract id, an approved-ТУ instance) is NOT exempt because "the system
  didn't mint it in-test": copying it from the case is the SAME violation as a fixed test-data id
  (BLOCK). Resolve by OWNERSHIP — a *test-ownable* entity (client/account/deal/pin) is provisioned
  in-scenario via a KB-attested create-endpoint and captured (`${var}`), or `db.seed`d into a
  write-allowed whitelisted schema (tagged by `:testRunId`, §"DB write logic") when no
  create-endpoint exists; a *shared stateful catalog row* (an approved ТУ) is boundary-forbidden to
  seed, is provisioned OUT-OF-BAND and VERIFIED with a read-probe before the trigger; it is blocking
  missing information ONLY when NEITHER route exists (case-analysis item 7), never a case literal
  and never a DBA-runbook substitute. A reference/dictionary CODE (service/ПУ/branch/currency —
  e.g. `PRICEASAVE`, `PU_NWA`, `2932`) is a constant, NOT an instance-handle, and stays verbatim.
  Review-only: nothing at run time catches a copied business id — the detection pattern lives in
  `stand-test-safety-review/safety-checklist.md`.
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
- **EQ client provisioning (`EqSeed`).** An EQ client precondition is provisioned via `EqSeed`,
  never by a direct `RestStep.post("showcases", …)` in a NEW test: a direct showcases call is
  acceptable only for an explicitly IFT-only test (tagged as such), because on test the validator
  rejects the `showcases` alias (`NON_WHITELISTED_SERVICE`) before IO. PIN / account / deal come
  from the step's published variables (`${<alias>.pin}`, `${<alias>.account}`, …) — never a
  constant. **`EqSeed` is the one seeding step with NO cleanup pairing:** EQ clients are never
  deleted (there is no sanctioned deletion), so the "every seed has a paired cleanup" rule above
  applies to `db.seed`, and to `db.write`'s undo-log, but NOT to `EqSeed` — its created clients are
  tagged with `testRunId` and listed in `eq-seeded.jsonl` for operatives. Do not require, invent or
  write a cleanup for `EqSeed`.
- Never hide a failure: no assertion deletion, no `@Disabled` without a ticket, no blind
  timeout inflation.
- Do not modify SDK modules, add adapters, or change core APIs while authoring tests; new
  consumer dependencies limited to `allure-junit5:2.29.1` and the JDBC driver — each
  human-approved. The JSON track needs none: the JSON-Schema validator was required only for the
  schema resource that shipped in `stand-test-ai-schema`, removed on 2026-08-12.

## Definition of done for a generated test

- Java: compiles + checkstyle-clean; AssertJ-only assertions.
- A run gate (`@EnabledIfEnvironmentVariable`) is **OPTIONAL and is not generated by default**.
  The annotation reads the bare environment variable and knows nothing about a `${VAR:default}`
  default in the consumer's `application.yml`: where the registry carries defaults — the normal case —
  the gate silently SKIPS a test that would have run perfectly, and a green build then says nothing.
  Add one only when the variable genuinely has no default and the test cannot run without it, and
  record that reason. Its absence is never a review finding.
- AI-format document: `AiScenarioParser` parse clean + `DefaultScenarioValidator().validate(scenario,
  registry)` clean. **Both gates run after the document is loaded** — the pre-flight JSON Schema pass
  no longer exists, so a document that has not been parsed has not been checked at all.
- Safety review PASS + quality review APPROVE + human approval on the validation report.
