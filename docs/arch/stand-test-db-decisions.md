# stand-test-db — design decisions

Decisions made implementing `stand-test-db` (MVP per the implementation plan §4/§8.7/§8.8/§9), first
shipped 2026-06-29. The plan (`stand-test-sdk-implementation-plan.md`) remains the source of truth; this
file records the non-obvious *choices* made during implementation and the rationale behind them. The REST
and Kafka adapters' rationale lives in their module READMEs and the implementation plan.

## Core prerequisite & write-guard (§8.8)

- **Core prerequisite (§8.8):** `SqlStatementClassifier` (+ `SqlClassification`/`SqlStatementKind`) lives
  in `stand-test-core` `core.validation` — a fail-closed, comment/literal-aware classifier
  (READ/WRITE/DESTRUCTIVE/REJECTED). It is the single source of truth the runtime `DbWriteGuard` and a
  future static validator / `ai-schema` both derive from.
- **Write-guard gates `UPDATE`/`DELETE` on the SDK-declared `whereTestRunId` marker, NOT on a textual
  `:testRunId` reference.** `referencesTestRunIdBind` is a whole-statement substring match and is trivially
  bypassed (`UPDATE t SET note=:testRunId`, `DELETE ... WHERE id=:testRunId OR 1=1`). The guard takes a
  `testRunIdPredicateDeclared` boolean = the step carries `WHERE_TEST_RUN_ID_COLUMN`; the executor appends
  `WHERE <col> = :testRunId` and forbids an author `WHERE`. **This was a CRITICAL bug found by the
  post-impl adversarial review — do not regress it.**
- **Classifier fail-closed cases (also found by review):** `SELECT ... INTO` rejected (it writes);
  `INSERT ... ON CONFLICT`/`ON DUPLICATE KEY` upsert rejected; 3-part `catalog.schema.table` left
  unqualified (only 2-part `schema.table` is provable); `WITH` embedding a data-modifying/`INTO` keyword
  rejected; multi-statement rejected.
- **Enforcement is runtime-only in the adapter (defense-in-depth half).** DB `prepare` stays a no-op per
  §8.7; wiring the classifier into the static `ScenarioValidator` is deferred (needs a registry-aware
  validator pass that doesn't exist yet), consistent with the validator's existing deferral of
  env/forbidden-op checks. The safety guarantee (destructive SQL never reaches the stand) holds at runtime.

## Connection & binding

- **Per-run JDBC connection** held in the run-scoped `ResourceScope` keyed by `"db.datasource:" + alias`
  (namespaced to avoid collision with Kafka's bare topic-alias keys in the shared scope). Opened lazily,
  `setAutoCommit(true)` set explicitly, closed by the runner.
- **Own `:name` → `?` rewriter** (`NamedParameterStatement`) — parameterized binds only, no Spring-JDBC.
  The reserved `:testRunId` bind is auto-provided from context and ALWAYS wins over an author
  `param("testRunId", ...)` (put last in the bind map) — per-run isolation.
- **Tests = H2 in-memory** (broker-free analog), via `DriverManagerConnectionFactory` + a passthrough
  `ReferenceResolver` (datasource refs are literal H2 url/user="sa"/password="sa"). Instruction coverage is
  ≥ 80% (JaCoCo gate).

## Technology evaluation: jOOQ (rejected, 2026-06-29)

jOOQ was evaluated as an alternative foundation for this module (web-researched + adversarially verified)
and **rejected**. Basis: **"not needed / no benefit"**, not "legally blocked" — see the licensing note below.

- **Decisive (structural) reason — no benefit:** jOOQ's value is a type-safe DSL from schema **code
  generation**, which needs the consumer's schema at SDK build time. A schema-agnostic SDK never has that
  (regardless of DB vendor), so jOOQ's headline value is unusable and it would run in its own
  vendor-discouraged codeless mode. The module already receives raw author SQL, one statement per step, so
  there is nothing for a builder to add.
- **Supporting reasons:**
  1. The **adapter's `java.sql`-only runtime rule** — any non-JDK runtime edge violates it (jOOQ also drags
     in jooq-core + transitively r2dbc / reactive-streams). This is a team rule (changeable), but adopting
     jOOQ means relaxing it and growing the footprint for zero gain.
  2. **No security gain** — jOOQ's injection guarantee covers only DSL-built SQL, not the raw strings this
     module executes; the classifier/guard are still required either way.
- **Licensing — NOT a blocker for the current fleet (stands are PostgreSQL).** PostgreSQL is fully
  supported by jOOQ OSS Edition (Apache-2.0, free), parser included, so the licensing argument does **not**
  apply here. It *would* bite only if the SDK were pointed at a commercial DB (Oracle/SQL Server →
  Professional, DB2 → Enterprise; jOOQ's parser API requires a license against those). On a single-dialect
  PostgreSQL fleet jOOQ's main strength — **multi-dialect rendering — is also irrelevant**, removing one of
  its few selling points.
- **Nuance:** §20 forbids the adapter *being* a generic DB client (its exposed capability), not *using* a
  builder internally — so "ORM category" alone isn't the blocker, and jOOQ could in principle live
  adapter-only; it still loses on the reasons above.
- **The one legitimate improvement direction is NOT jOOQ** but hardening the hand-rolled `strip()` lexer's
  **dialect blind spots** (mirrored in `NamedParameterStatement`). Closed so far:
  - **PostgreSQL dollar-quoting (`$$ … $$` / `$tag$ … $tag$`)** — `strip()` recognises dollar-quote
    delimiters before single quotes, fixing a real fail-open (a stray `'` inside a `$$…$$` body opened a
    spurious single-quoted span and blanked across a trailing `; DROP TABLE …`, classifying it READ).
  - **MySQL backtick-quoted identifiers (`` `…` ``)** — blanked like double-quoted identifiers (doubling
    escape), via the parameterized `blankQuotedIdentifier`; a `:name` inside backticks is left untouched.
  - **`GO` (sqlcmd) / lone `/` (SQL*Plus) batch separators** — a skeleton line of just `GO`
    (case-insensitive) or `/` is rejected fail-closed (`containsBatchSeparatorLine`). Accepted trade-off: a
    column/table literally named `go` alone on its own line is falsely rejected (rare, fail-closed).
  - **`[bracketed]` identifiers — INTENTIONALLY NOT handled.** On the PostgreSQL fleet `[…]` is
    array-subscript syntax (`tags[1]`, a bind index `arr[:idx]`), not quoting as in T-SQL; treating it as a
    quoted span would corrupt valid arrays and stop `:name` binds inside subscripts from being rewritten.
    Leaving `[`/`]` as ordinary characters is correct for PostgreSQL.
  Still open: unterminated-literal fail-closed hardening (still partly leans on the driver rejecting
  malformed SQL). If ever made parser-backed, **JSqlParser** (pure-Java, no DB, Apache-2.0-electable) is the
  candidate, used as an **adapter-side fail-closed corroborator that may only ADD rejections, never overturn
  a skeleton reject** (its `TablesNamesFinder` under-reports tables in subqueries/CTE/MERGE = its own
  fail-open risk). Keep the core skeleton as the authoritative §8.6 single source of truth.
- **Dominant residual risk is semantic** (`SELECT side_effecting_fn()`, `... FOR UPDATE`,
  `INSERT ... SELECT` from a non-whitelisted source) — invisible to any classifier; contain via read-only DB
  accounts + `writeAllowed` + schema whitelist + the testRunId marker.

## Acknowledged trade-offs (post-review hardening, 2026-06-30)

A hard adversarial review hardened the module (a CRITICAL bare-`\r` line-comment fail-open was fixed; see
the §8.8 note above) and confirmed the following as **deliberate, accepted** choices rather than accidents:

- **Shared span lexer.** `SqlStatementClassifier.strip()` (skeletoniser) and the DB `:name` rewriter
  (`NamedParameterStatement`) now share one span-boundary scanner, **`core.validation.SqlSpanScanner`** — the
  single source of where comments/literals/quoted/dollar-quoted spans begin and end. Each keeps only its own
  per-character policy (blank vs copy). A drift between the two duplicated lexers was the root of the bare-CR
  fail-open; centralising the boundaries makes that class of bug structurally impossible (a differential test
  asserts they agree on `:testRunId` span-membership).
- **Schema whitelist folds the target, not the config.** `DatasourceDefinition.isSchemaAllowed` lower-cases
  the (unquoted, ASCII) target schema with `Locale.ROOT` and matches it exactly against `allowedSchemas`,
  which must therefore hold **physical lower-case** schema names. This mirrors how PostgreSQL folds an
  unquoted identifier; a non-lower-case whitelist entry is inert (fail-closed) rather than leniently matched.
  A quoted, case-distinct schema is unreachable anyway (the classifier blanks quoted targets → unprovable).
- **`db.expectEventually` fails fast on a probe `SQLException`** (`ignoreExceptions=false`); it does not
  reconnect or retry SQL errors per poll, and a `>1`-row result aborts immediately as ambiguous. On the
  PostgreSQL fleet a probe SQL error is a real config error, not a transient. Revisit if non-PostgreSQL
  stands or long poll windows appear.
- **Error-classification split.** `db.query` capturing no rows or a NULL column is an infrastructure error
  (`StandTestException` — it is a probe/precondition fetch), whereas the analogous REST capture is an
  assertion. The actual assertion step, `db.expectEventually`, uses `StandTestAssertionError` correctly.
- **Fail-closed false-positives are accepted.** `UPDATE/DELETE … ONLY`, an aliased write target, a
  subquery `WHERE` on a `whereTestRunId` step, a non-reserved keyword used as a column/alias inside a CTE,
  and non-nested block comments are *rejected* (never wrongly allowed) — safe over-restrictive trade-offs,
  like the `GO`/`/` batch-separator handling.
- **Lexers assume SQL-standard quote doubling, not backslash escapes** — correct on PostgreSQL
  (`standard_conforming_strings=on`); revisit (in the one `SqlSpanScanner`) if a MySQL datasource is added.
- **H2 (`MODE=PostgreSQL`) is not the production database.** Classifier guarantees are proven on the string
  skeleton and on H2; a few constructs (dollar-quoting, `RETURNING`, identifier case folding) are not executed
  end-to-end against real PostgreSQL — a known fidelity gap.
- **Static `ScenarioValidator` does not yet consume the classifier** — the defense-in-depth static half is a
  known follow-up; the runtime guard holds the safety guarantee today (see the module README).
