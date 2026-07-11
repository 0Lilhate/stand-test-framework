# stand-test-db — test-data rollback / undo-log design

Design for guaranteed rollback of test-mutated DB data when a scenario touches one or more datasources and
then calls an external API. Supersedes the "explicit `db.cleanup` step, no teardown hook" limitation
recorded in `stand-test-sdk-implementation-plan.md` §8.8 and the `whereTestRunId`/`taggedByTestRunId`
write-guard decisions in `stand-test-db-decisions.md` §8.8. **Status: design agreed, not yet implemented.**

This file is the source of truth for the feature. It was produced from an analysis pass + a multi-agent
design pass + an adversarial red-team pass; the red-team fixes are folded into the decisions below.

## MVP implementation status (2026-07)

**Implemented and build-green** (the reusable engine + the INSERT vertical slice, all additive/non-breaking):

- **core** `ru.alfa.stand.test.core.compensation`: `UndoLog`, `Compensator`, `CompensationOutcome`,
  `CompensationStatus`, `CompensationReport`, `CleanupPolicy` (`ON_FAILURE` default / `ALWAYS` / `NEVER`).
- **core** `StepExecutionContext` carries the per-run `UndoLog` (6th component; the old 4-/5-arg
  constructors kept as convenience overloads, so every existing call site compiles unchanged).
- **core** `DefaultScenarioRunner` drains the `UndoLog` in its `finally`, before `closeQuietly`, in reverse
  order, policy-gated, with local (never-instance) failure state; a green + failed-cleanup run fails, a
  failed run attaches the cleanup failure via `addSuppressed`; a CONFLICT fails strictly; compensation is
  reported via synthetic `db.compensate` `StepEvent`s.
- **core** `Scenario.cleanupPolicy` (default `ON_FAILURE`).
- **db** `DbOperation.WRITE` (`db.write`), `DbStep.write(...).identifiedBy(...)`, `DbWriteGuard` business /
  compensation lanes (INSERT only, no `testRunId` marker, schema-whitelist + non-destructive kept),
  `DbCompensator` (DELETE-by-PK, idempotent, never-throws), `DbStepExecutor` capture + register.
- Tests: `CompensationModelTest`, `ScenarioRunnerCompensationTest` (policy / reverse-order / preservation /
  aggregation / CONFLICT / parallel isolation / reporting events / Error-compensator), `DbWriteUndoTest`
  (H2, single + two physical datasources), `FailedApiRollbackExampleTest` (end-to-end via the real runner).

**Hardening from the adversarial review (2026-07), all build-green:**
- `db.write` fail-closes any non-single-row INSERT — multi-row `VALUES` and `INSERT … SELECT` are rejected
  (`SqlStatementClassifier.insertValuesRowArity`), so a single captured PK can never under-compensate.
- The drain net and `DbCompensator`/`closeQuietly` catch `Throwable` (not just `RuntimeException`): a
  contract-violating compensator or a JVM `Error` can never escape the `finally`, skip the close/publish
  tail, or mask the primary failure.
- `DbCompensator` **pre-counts** matching rows before deleting (autoCommit makes a DELETE irreversible): a
  non-unique `identifiedBy` yields FAILED and deletes nothing, instead of silently removing foreign rows.
- The compensator is registered only when the INSERT actually created a row (`rowsAffected > 0`); the
  `identifiedBy` column must be in the INSERT column list; `identifiedBy` is re-validated as a plain
  identifier on the runtime read path (defense for a raw/YAML producer); `.whereTestRunId(...)` is rejected
  on `db.write`.

**Staged (per this design, not in the MVP slice):** `db.write` UPDATE/DELETE before-image capture and undo;
`DatabaseMetaData` PK auto-resolution and DB-generated identity-key capture (`.identifiedBy` + a `:<pk>`
bind is the current path); the removal of the legacy `db.cleanup`/`whereTestRunId`/`taggedByTestRunId` lane
(still present and passing — decision #5 is deferred to a follow-up breaking change); the
`ForbiddenOperation.UNDOABLE_WRITE_WITHOUT_KEY` code + its ai-generation-rules row; a `CompensationReport`
on `ScenarioResult`; YAML `cleanupPolicy` key; `.force()` conflict override.

## 1. Problem & the load-bearing constraint

A test may INSERT/UPDATE/DELETE across several datasources, then call a REST/gRPC/Kafka service. If the API
is unavailable or an assertion fails, every DB change the test made must be undone.

**Committed vs uncommitted visibility (why a JDBC rollback is not enough).** The external service uses its
own connection pool and its own transaction/snapshot. Under `READ COMMITTED` it sees only *committed* rows.
So test data prepared inside an *open, uncommitted* transaction is invisible to the API — the "prepare data
→ call API" flow breaks. The SDK already runs writes with `setAutoCommit(true)` precisely so a seed is
immediately visible to a later step and to the external service.

Consequence: you cannot both (a) let the API see prepared data and (b) undo it with a transaction
`rollback` — a rollback requires the data to stay uncommitted, which the API cannot see. Committed data the
API can see, but it can only be undone by **compensation** (an inverse DML statement). And an external
REST/gRPC/Kafka call is not an XA resource manager — its side effects are never rolled back by a DB
rollback, so "atomic test + API" is impossible; the best achievable is best-effort compensation of the DB
side. XA/2PC would span only the databases (not the API) and is deferred.

**User-locked constraint:** no new columns may be added to any target table. This retires the entire
`test_run_id` marker model for business tables — row identity for compensation must come from the
**primary key**, never from a marker column.

## 2. Chosen model

Committed writes (keep `autoCommit=true`) + a **per-run, in-JVM undo-log** that captures a row image at
execution time and compensates by **primary key**, drained in the runner's `finally`.

- Row identity: **HYBRID** PK resolution — author `.identifiedBy(cols)` override first, else
  `DatabaseMetaData.getPrimaryKeys(catalog, schema, table)` with identifiers folded to the driver's storage
  case (`storesUpperCaseIdentifiers`/`storesLowerCaseIdentifiers`). No usable unique key ⇒ the write is
  **rejected fail-closed** at pre-flight (`ForbiddenOperation.UNDOABLE_WRITE_WITHOUT_KEY`).
- Per operation:
  - **INSERT** undo = `DELETE FROM t WHERE <pk>=…` by captured PK. PK captured via `getGeneratedKeys()`
    (primary) or explicit `:bind` values mapped by parsing the `VALUES` tuple (never inferred from a
    same-named bind). Undo is keyed on **PK existence only** — no full-row compare — because the API under
    test is *expected* to mutate the seeded row.
  - **UPDATE** undo = restore the before-image of the changed columns by PK. Before the mutation, a
    predicate-preserving pre-SELECT `SELECT <pk>,<set-cols> FROM t <same WHERE>` (reusing the mutation's
    `:name` binds) captures the before-image; an after-image of the set-cols is captured post-mutation for
    the conflict check.
  - **DELETE** undo = re-INSERT the full captured row. Before the mutation, `SELECT * FROM t <same WHERE>`
    captures the full before-image.
- **Capture is always-on** (before every mutation); only *application* of compensation is gated by policy.

### 2.1 Split across modules (Variant C)

- **stand-test-core** (JDK-only sink) owns only the generic, opaque caraffold: `UndoLog`, the `Compensator`
  callback interface, `CompensationOutcome`/`CompensationStatus`, and `CleanupPolicy`. Core **sequences**
  compensators in reverse and applies the `addSuppressed` discipline. **No JDBC types, no table/PK/image
  structure in core.**
- **stand-test-db** owns all JDBC: PK resolution, pre-SELECT capture, generated-key capture, undo-SQL
  generation, and the concrete `DbCompensator` (which closes over the live run-scoped connection).
- No new module (Variant B deferred until a second compensating adapter exists). No all-in-db (Variant A
  impossible — the drain seam is in the core runner's `finally`).

## 3. Lifecycle integration

Compensation runs **inside `DefaultScenarioRunner.run()`'s `finally`, before `closeQuietly(resourceScope)`**
(the run-scoped JDBC connection is still open and, being `autoCommit=true`, its re-SELECTs see committed
rows). This is the single choke point for JUnit, Spring starter, programmatic and YAML runs — all funnel
through `DefaultStandClient.run(scenario)` → `ScenarioRunner.run(scenario)`. `StandTestExtension.afterEach`
is *not* used: it is JUnit-only and, by the time it could fire, the connection has already been closed.

**Control flow (red-team-hardened):**

1. `primary` (the in-flight `Throwable`) and the success flag are **local variables** in `run()` — never
   instance fields. `DefaultScenarioRunner` is a shared singleton invoked concurrently, so instance state
   would race across runs and break per-run isolation. The step loop throws `StandTestAssertionError`
   (which extends `AssertionError extends Error`), so capture uses `catch (Throwable)`.
2. `finally` order is **drain → `closeQuietly(resourceScope)` → `publishScenario(FINISHED)` → then, as the
   final statement**: `if (primary == null && result.failed()) throw result.toException(); else if
   (result.failed()) primary.addSuppressed(result.toException());`. Never throw before the close/publish
   tail — otherwise the connection leaks and Allure sees a STARTED scenario with no FINISHED on the green
   path.
3. `Compensator.compensate()` **never throws** — it folds infra errors into a `FAILED` outcome — so an
   escaping exception can never skip the tail.

## 4. Public API (real DSL)

The Java DSL is `Scenario.builder(...)` + typed steps (there is no `.given/.when/.then` in the Java DSL —
that is a YAML surface). No explicit cleanup step is needed; a `db.write`/`db.seed` auto-registers its undo.

```java
Scenario scenario = Scenario.builder("create-request")
        .environment("ift")
        .cleanupPolicy(CleanupPolicy.ON_FAILURE)                 // default; shown for clarity
        .step(DbStep.write("client-db")
                .sql("insert into client.account(id, status) values (:accId, 'ACTIVE')")
                .param("accId", "${accId}")
                .identifiedBy("id")                              // PK source; else auto from metadata; else fail-closed
                .build())                                        // undo = DELETE FROM client.account WHERE id = :__pk_id
        .step(RestStep.post("client-service", "/api/requests")
                .bodyResource("create-request.json")
                .expectStatus(200)
                .capture("requestId", "$.id")
                .build())                                        // if it fails -> undo the write in finally
        .step(DbStep.expectEventually("request-db")
                .sql("select status from req.request where id = :requestId")
                .param("requestId", "${requestId}")
                .expectValue("CREATED")
                .withinSeconds(30)
                .build())
        .build();

standClient.run(scenario);
```

UPDATE example: `.sql("update client.account set balance = :bal where id = :accId")` + `.identifiedBy("id")`
→ undo restores the previous `balance` by `id`. The author WHERE is **required and must be a
PK/`.identifiedBy`-equality predicate** (not merely "a WHERE is present").

## 5. Operations (final)

| Op | Wire type | Meaning | Undo |
|----|-----------|---------|------|
| `query` | `db.query` | SELECT, captures | none |
| `expectEventually` | `db.expectEventually` | poll SELECT until match | none |
| `seed` | `db.seed` | INSERT preparation. **MVP: legacy lane** — still uses the `testRunId` tag column and an explicit `db.cleanup`; NOT auto-undo-captured yet (target: fold into the undo-log) | explicit `db.cleanup` (legacy) |
| `write` | `db.write` | general INSERT/UPDATE/DELETE with author WHERE, undo-captured | per §2 |

`db.cleanup`, `.whereTestRunId(...)`, `.taggedByTestRunId(...)`, `enforceSeedTagColumn`, the appended
`WHERE <col>=:testRunId`, and the `WHERE_TEST_RUN_ID_COLUMN`/`SEED_TEST_RUN_ID_COLUMN` wire keys are
**removed** (decision: no legacy lane). This is a breaking change — existing example/YAML tests that use the
marker model are migrated to `db.write`/`db.seed` + auto-undo. The reserved `:testRunId` bind stays
available for author predicates/diagnostics but is no longer a scoping mechanism.

## 6. Cleanup policies

`CleanupPolicy` is a typed field on `Scenario` (default `ON_FAILURE`), with an optional
`cleanupPolicy` top-level key on the given/then YAML surface. The AI steps/type schema is untouched (DB
writes are out of AI scope).

| Policy | Drain applies | Notes |
|--------|---------------|-------|
| `ON_FAILURE` (default) | only when the run failed (`primary != null`) | under the default, a "green + failed cleanup" can never occur |
| `ALWAYS` | always | green runs are cleaned too |
| `NEVER` | never | capture still runs; the log is discarded |

(The design also envisaged a `ROLLBACK_ONLY` alias for pure-DB tests; the shipped MVP enum is
`ON_FAILURE` / `ALWAYS` / `NEVER` — `ROLLBACK_ONLY` is staged.)

Capture is always-on; the policy gates only application.

## 7. Failure semantics

`addSuppressed` template from `ResourceScope.closeAll`; compensation **never masks** the original failure.

| Situation | Behaviour |
|-----------|-----------|
| Setup insert failed | the write throws (this is the in-flight failure); already-captured writes compensate in `finally`; comp errors `addSuppressed`. Test fails on the setup error |
| API unavailable | REST/gRPC infra failure (BROKEN); DB writes compensate; comp errors suppressed. Test fails on the API error. External side effects are **not** compensated |
| Assertion failed | `StandTestAssertionError` (FAILED); compensation runs; comp errors suppressed. Reports the original assertion |
| Cleanup failed + test already failed | comp error `addSuppressed` on the in-flight exception; never replaces it; visible as suppressed + `db.compensate` StepEvent(FAILED) |
| Cleanup failed (hard SQL error) + green test | the comp `StandTestException` is thrown and fails the test |
| **UNDO CONFLICT** (current row diverged from our after-image) | do not overwrite; record CONFLICT. **Decision: a CONFLICT fails the test** — on a green run it is thrown, on an already-failed run it is suppressed/reported. `.force()` downgrades to a best-effort overwrite (still reported). Divergence is compared only on the columns the test changed (UPDATE set-cols) / PK existence (INSERT), excluding volatile/DB-maintained columns, to avoid false positives |
| Multi-DS partial failure | continue other aliases; aggregate (first cause + `addSuppressed`); throw on green, suppress on failed |

## 8. Multi-datasource behaviour

Undo entries group by datasource alias (each alias owns its own `RunScopedConnection` keyed
`"db.datasource:" + alias`). The drain walks the whole log in reverse registration order (materialize
`values()` into a `List` and walk backwards — no `List.reversed()` under `--release 17`). A failure on one
alias does **not** abort the others (best-effort per alias); all failures aggregate into one
`StandTestException` (first as cause, remainder suppressed). A dead connection marks that alias failed and
reporting continues. **No XA, no 2PC, no cross-datasource atomicity, no retry** in the MVP.

## 9. Parallel behaviour

All undo state is per-run, created inside `run()` alongside `VariableStore`/`ResourceScope`; never a field
on the shared-singleton `DbStepExecutor`, never static/`ThreadLocal`. `primary`/`failed` are locals.
Compensation is strictly PK-equality-scoped.

**Honest scope limit:** PK-scoping isolates rows a run *created*, not shared pre-existing rows a run
*mutates*. Scenarios that mutate shared business rows must serialize via `@ResourceLock` / `@StandSerial` /
`@StandIsolated` (author responsibility).

## 10. Safety guardrails

Always (both business-write and compensation lanes): `G1` REJECTED ⇒ fail-closed; `G2` DESTRUCTIVE
forbidden; `G3` `writeAllowed`; `G4` schema-qualified two-part `schema.table`; `G5` schema ∈
`allowedSchemas`; `G6` single-statement + fully parameterized (upsert `ON CONFLICT`/`ON DUPLICATE` stays
REJECTED).

Undo-model specific:
- `G7` **undo-ability**: a PK (or `.identifiedBy`) must resolve ⇒ else `UNDOABLE_WRITE_WITHOUT_KEY`
  fail-closed at the DB-adapter pre-flight (which has the live connection for `DatabaseMetaData`); the core
  `DefaultScenarioValidator` does only a static shape/eligibility check.
- `G8` business UPDATE/DELETE: author WHERE **required and PK/`.identifiedBy`-equality-scoped**.
- `G9` INSERT: no `:testRunId`/tag column.
- `G10` every generated compensation statement re-enters the guard on the COMPENSATION lane (PK-equality,
  parameterized).
- **Fail-closed shapes** (red-team): reject `INSERT … SELECT`, multi-row `VALUES`, multi-table
  `UPDATE … FROM` / `DELETE … USING`, and `GENERATED ALWAYS`/computed-PK tables for DELETE-undo (re-INSERT
  is infeasible).

## 11. Reporting

Reuse `StepEvent` with a synthetic `db.compensate` stepType, `StepPhase.STARTED/FINISHED`, `StepStatus`, and
CONFLICT / before-after images / PK / rowsAffected in the diagnostics map (evidence as `Attachment`). No new
`ScenarioPhase`/`StepPhase` constant, no new `publish()` overload, no edit to the sealed `ReportingEvent`
permits, no change to `NoOpReportingEventPublisher` or the Allure sink. An optional green-path-only
`CompensationReport` on `ScenarioResult` is a fast-follow (out of strict MVP — `ScenarioResult` is returned
only on the green path, so failure-path outcomes travel via events + suppressed exceptions).

## 12. New / changed types

**core:** `UndoLog` (new), `Compensator` (new), `CompensationOutcome`/`CompensationStatus` (new),
`CleanupPolicy` (new), `StepExecutionContext` (add 6th component + convenience overloads),
`DefaultScenarioRunner` (drain in `finally`, local outcome state), `Scenario`/`Builder` (add
`cleanupPolicy`), `SqlStatementClassifier` (add `updateSetColumns`, `whereClauseOffset`, new fail-closed
gates), `ForbiddenOperation` (add `UNDOABLE_WRITE_WITHOUT_KEY`), `DefaultScenarioValidator` (static
undo-eligibility), `StepParameterKeys` (add `IDENTIFIED_BY`, `UNDO_FORCE`, `CLEANUP_POLICY`; remove the
testRunId marker keys), `CompensationReport` (new, optional).

**db:** `DbOperation.WRITE` (`db.write`), `DbStep` (add `write`/`.identifiedBy`/`.force`; remove
`cleanup`/`whereTestRunId`/`taggedByTestRunId`), `PkResolver` (new), `CompensationAction`/`CompensationRow`/
`RowImage` (new), `DbCompensator` (new), `UndoSqlBuilder` (new), `DbWriteGuard` (split into `enforceBaseWrite`
+ business-write + compensation lanes; remove the legacy testRunId lane and `enforceSeedTagColumn`),
`NamedParameterStatement` (add `RETURN_GENERATED_KEYS`/column-name overload), `DbStepExecutor` (pre-flight
undo-ability, always-on capture, adapter-side apply, drop the appended `WHERE`), `DbStepParameters` (surface
the new keys).

**scenario-yaml:** `YamlScenarioParser` (optional `cleanupPolicy` top-level key). **ai-schema:** a row for
`UNDOABLE_WRITE_WITHOUT_KEY` in `stand-test-ai-generation-rules.md` to keep `ForbiddenOperationCoverageTest`
green (the JSON Schema itself is untouched).

## 13. Implementation steps (TDD, build-green increments)

1. Core scaffold (`UndoLog`/`Compensator`/`CompensationOutcome`/`CompensationStatus`/`CleanupPolicy`;
   `StepExecutionContext` convenience overloads). RED tests: reverse-order drain; two concurrent runs — no
   suppress/gate leak.
2. Runner drain: local `primary`/`failed`; policy-gated reverse drain; order drain→close→publish→throw/
   suppress; `Scenario.cleanupPolicy`.
3. Classifier: `updateSetColumns`, `whereClauseOffset`, fail-closed gates (INSERT…SELECT, multi-row,
   multi-table); span/lexer-consistency tests.
4. `PkResolver` (metadata folding + `.identifiedBy`), fail-closed; H2 upper/lower tests.
5. Capture in `DbStepExecutor`: pre-flight undo-ability, before-image pre-SELECT (U/D), PK via
   generated-keys / parsed VALUES; register `DbCompensator`.
6. Apply: `UndoSqlBuilder` + `DbCompensator` (conflict-check on changed columns, idempotent, never-throws)
   via the compensation lane.
7. Guard split; drop the appended WHERE; `DbOperation.WRITE`, `DbStep.write/.identifiedBy/.force`; remove
   the legacy lane and migrate its call sites.
8. Reporting: `db.compensate` StepEvent; verify Allure mapping.
9. YAML `cleanupPolicy`; `stand-test-ai-generation-rules.md` row; green `ForbiddenOperationCoverageTest`.
10. Example: multi-DS demo — seed A + seed B → API fail → undo both → assert `db.compensate` events.
11. Docs: unblock plan §8.8; update `stand-test-db-decisions.md`, README, `.claude` skills
    (safety-review/java-dsl-authoring) — remove the stale "cleanup SQL must contain testRunId" rule.

## 14. Risks

1. Enterprise portability (DB2/AS400/Oracle): `getGeneratedKeys` unreliable, 3-part names outside the
   classifier ⇒ `.identifiedBy` + explicit PK only; metadata-case folding mismatch silently returns empty ⇒
   fail-closed reject (needs per-driver tests).
2. DELETE-undo infeasible for `GENERATED ALWAYS`/computed-PK, refires triggers ⇒ fail-closed + documented.
3. Weakening U/D scope: if `G8` (author WHERE = PK-equality) is not held strictly, a shared stand could be
   hit with a broad row set.
4. Conflict false positives (updated_at/computed) ⇒ mitigated by comparing only changed columns.
5. Crash-orphan: with the no-column constraint there is no reap key at all — a documented, conscious loss of
   crash recoverability on shared stands.
6. Immutable-record ripple on `StepExecutionContext`/`Scenario` — covered by convenience overloads but needs
   care in `equals`/`hashCode`/`toString`.

## 15. Locked decisions

1. Committed writes kept (API must see prepared data); rollback = SDK undo-log by PK, not JDBC rollback, not
   a marker column.
2. Undo INSERT/UPDATE/DELETE. Default `ON_FAILURE`; flag for `ALWAYS` (shipped enum: `ON_FAILURE`/`ALWAYS`/`NEVER`).
3. HYBRID PK resolution; no usable key ⇒ fail-closed.
4. **CONFLICT fails the test** (strict), with `.force()` escape hatch.
5. **Legacy testRunId-marker lane removed** (breaking; existing tests migrated).
6. **Separate `db.write` op** (INSERT/UPDATE/DELETE). MVP: `db.seed` stays on the legacy `testRunId`/`db.cleanup`
   lane and is NOT yet undo-captured — only `db.write` registers an undo (folding `db.seed` in is staged).
7. Crash-orphan documented only (no persistent undo file). No max-rows cap. No dry-run.
8. Write without a buildable undo ⇒ validation error (fail-closed).
9. External side effects (Kafka/REST/gRPC) not compensated — documented limitation.
10. XA/2PC / cross-datasource atomicity deferred; multi-DS = best-effort per alias.
