# stand-test-db

**Group:** adapters · **Gradle plugin:** `java-library`

Database / JDBC adapter: a **seed / probe / assertion** layer for stand databases — *not* a generic DB
client. It owns the typed `DbStep` model (step types `db.query` / `db.expectEventually` / `db.seed` /
`db.cleanup`) and the DB `StepExecutor` (`DbStepExecutor`, registered via the core SPI in
`META-INF/services`), and is the single point of real JDBC IO to the stand. Reads are the default; writes
exist only as constrained test-data preparation.

**Internal dependencies:** `stand-test-core` (`api`), `stand-test-await` (`implementation`).
**External dependencies:** none at runtime — production code is JDK-only (`java.sql`); the JDBC driver is
supplied by the consumer. Named parameters (`:name`) are handled by this module's own `:name` → `?`
rewriter (`NamedParameterStatement`) over `PreparedStatement` (no Spring-JDBC / HikariCP). The SDK ships
no ORM and no generic "arbitrary SQL to any DB" mode (plan §4, §20). Tests run against **H2 in-memory**.

Key contracts (see `docs/arch/stand-test-sdk-implementation-plan.md`):
- §4 (Iteration 6, MVP) — `db.query` / `db.expectEventually` (await-query, expect single value) /
  `db.seed` (write-allow) / draft `db.cleanup` by `testRunId`
- **§8.8 — DB safety: SQL statement classification + write-guard**, the resolution of the
  destructive-SQL/schema-whitelist gap: readonly by default; a constrained single-statement grammar
  classified read/write/destructive; schema derived from a schema-qualified table name and checked
  against `allowedSchemas`; `UPDATE`/`DELETE` require a declared `testRunId` predicate
  (`DbStep.whereTestRunId(...)`); parameterized binds only; **fail-closed** on anything unparseable
- §8.6 — guardrails enforced at runtime in the executor (deriving from `ForbiddenOperation`); the same
  checks are *planned* statically in `ScenarioValidator` for defense-in-depth (see **Known follow-up** below)
- §8.7 — per-run JDBC connection held in the run-scoped `ResourceScope`, keyed by datasource alias
- §9 — `DatasourceDefinition` (`urlRef`/`userRef`/`passwordRef` references, `allowedSchemas`,
  `writeAllowed`) resolved via `EnvironmentRegistry`; references → values at run time, never hardcoded

**Implemented (MVP).** The full step set: `db.query` (one-shot read that captures column values),
`db.expectEventually` (await-query polling a single value through `stand-test-await`, with a `>1`-row
ambiguity guard), `db.seed` (write-allow) and a draft `db.cleanup` (delete-by-`testRunId`). The
`DbStep` builder is a lazy builder (no IO); the executor resolves the datasource via `EnvironmentRegistry`
(secret refs → values at run time), holds a per-run JDBC connection in the run-scoped `ResourceScope`
keyed by datasource alias (§8.7), enforces the §8.8 write-guard at runtime (defense-in-depth) and binds
all values through the `:name` rewriter.

**Core prerequisite (delivered, §8.8).** `SqlStatementClassifier` (+ `SqlClassification` /
`SqlStatementKind`) in `stand-test-core` `core.validation`: a fail-closed, comment/literal-aware
classifier (read / write / destructive / rejected) that both the runtime `DbWriteGuard` and the
`ScenarioValidator` derive from, so they cannot drift (§8.6). No new env contract is
required: `DatasourceDefinition` already exists.

> **Known follow-up.** Static guard enforcement currently lives in the adapter (runtime, before any IO).
> Wiring the classifier into the static `ScenarioValidator` (which needs a registry-aware validator pass)
> is deferred, consistent with the validator's existing deferral of env / forbidden-op checks. The safety
> guarantee — destructive or unauthorized SQL never reaches the stand — holds at runtime today.

> **Testing.** Broker-free / stand-free — H2 in-memory as the analog of the REST `HttpServer` / Kafka
> `MockConsumer` (plan §16); the `DriverManagerConnectionFactory` / `ReferenceResolver` seams let tests
> point the adapter at H2 with literal connection values.
