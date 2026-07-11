---
name: stand-test-scenario-design
description: Turn a stand-test case analysis into a technical scenario design (scenario id, step order and types, captures, assertions, awaits, correlation, test-data and cleanup strategy, Java-DSL vs AI-format track choice). Use after stand-test-case-analysis and before any authoring.
---

# Skill: stand-test-scenario-design

Convert a `TestCaseAnalysis.md` into a technical scenario design — the exact step list an
authoring skill will implement. **Still no code generation here.**

## When to use

After `stand-test-case-analysis`, `stand-test-kb-lookup` and `stand-test-environment-mapping`
have run and blocking missing-info items (including KB `missing` rows) are resolved (or
explicitly assumed).

## Input

- `TestCaseAnalysis.md`.
- `KnowledgeBaseLookupResult` (from `stand-test-kb-lookup`, when the project keeps a KB) —
  the source for every contract detail: paths, response fields, message schemas, DB probes,
  gRPC methods. A detail present in neither the lookup result, nor the case text, nor the
  recorded assumptions must NOT appear in the design.
- Environment mapping report (which aliases exist, their `correlation:`/`auth:`/`write-allowed`
  properties).

## Output

`ScenarioDesign.md` following
[`scenario-design-template.md`](../stand-test-scenario-design/scenario-design-template.md).

## Design decisions to make (in order)

1. **Scenario id** — kebab-case, stable, business-meaningful (`order-status-projection`).
   Matches `^[A-Za-z0-9][A-Za-z0-9._-]*$`.
2. **Environment** — a registry key, verbatim (e.g. `ift`). Never a URL.
3. **Tags** — `integration` plus domain tags; they become Allure labels.
4. **Track** — Java DSL (default) vs AI JSON/YAML. Choose AI format **only if every step** fits
   the executable subset (see the per-type crib in ../stand-test-yaml-authoring/SKILL.md). Any `db.seed`/`db.cleanup`/`rest.put`/
   `rest.delete`/gRPC-custom-metadata/non-equals-outside-REST/negative-path requirement ⇒
   Java DSL. A computed per-run value (current/future date etc., rule 11) also ⇒ Java DSL —
   fixtures resolve only `${var}` placeholders, there are no value generators on the AI surface. (Non-secret custom HTTP headers are fine in the AI format on `rest.*` steps;
   secret-bearing header names are banned in both tracks.)
5. **Step order** — `seed → trigger → awaits/asserts → cleanup`, all inside ONE scenario:
   - Kafka `expect` consumers are armed in the runner's prepare phase **before any step runs**,
     so the trigger and the `kafka.expect` must live in the same scenario.
   - Steps run sequentially on one thread and share one per-run DB connection. This is the SDK's
     "one scenario run = one thread" invariant: SCENARIOS/test-classes parallelise (the consumer
     runs classes concurrently), but the steps of one scenario never do. Distinct runs get distinct
     `testRunId`/`correlationId`/`VariableStore`/DB connection/Kafka group — so a design that scopes
     all test data by `${testRunId}` is parallel-safe by construction.
6. **Step ids** — explicit, unique, kebab-case (`create-order`, `await-order-event`).
   Duplicate ids fail validation at run time; default derived ids collide on repeated
   method+path / operation+datasource / `UNARY <target>`.
7. **Variables & captures** — table of `variableName ← producing step ← JSONPath/column`,
   plus where each `${variableName}` is consumed. Built-ins available without capture:
   `${scenarioId}`, `${testRunId}`, `${correlationId}`, `${environment}`.
   Syntax is `${name}` — **not** `{{name}}`; no defaults, no expressions, no escaping.
8. **Assertions** — per step, with matcher:
   - REST: `EQUALS` (default), `CONTAINS`, `MATCHES` (full-string regex), `EXISTS` (true/false;
     JSON `null` counts as present), `NOT_NULL` (true/false).
   - Kafka / gRPC / DB: equals only. Numbers compare by value (`100` == `100.0`), strings never
     coerce (`"100"` != `100`).
   - Use definite JSONPaths with presence matchers (no `$..x`, no `[*]`).
9. **Awaits** — every async check is an `expectEventually`/`expect` step with an explicit
   timeout. Pick the smallest realistic SLA; cap 1h (AI grammar: ≤99999ms / ≤999s / ≤60m).
   Never design a sleep or a manual retry loop.
10. **Correlation strategy** — SDK-owned:
    - Trigger step: `.injectCorrelationId()` / `correlation: {inject: true}` — requires the
      alias to declare `correlation: {source: HEADER|METADATA, name: ...}` in the registry
      (HEADER for REST/Kafka topics, METADATA for gRPC; Kafka is HEADER-only — KEY/
      PAYLOAD_FIELD carriers throw).
    - Consumer step: `kafka.expect` + `correlationIdFromContext` / `correlation: {fromContext: true}`
      — a per-run-UNIQUE discriminator is mandatory (the executor refuses an undiscriminated expect
      at run time). If a `.key(...)` narrows selection it must be per-run-derived (`${testRunId}`);
      a constant key is allowed only alongside `correlationIdFromContext`.
    - Never fabricate a correlation value. The header/metadata NAME comes from the registry,
      not from the SDK.
11. **Test data strategy** — all created identifiers derive from `${testRunId}` or are captured
    from responses. Prefer creating preconditions through the system's API over `db.seed`.
    If seeding: `INSERT` into a schema-qualified whitelisted table with a `test_run_id` column
    bound to the reserved `:testRunId` bind. When the case NEEDS write preconditions — design
    them, do not dodge into assumptions: the full when/preconditions/shape/boundary rule is
    guardrails §"DB write logic" (write-allowed + allowed-schemas verified, else blocking).
    **Classify EVERY request/payload field** (KB `valueHints` first, field semantics second):
    - *run-unique* (identifiers, external ids, idempotency keys) → `<prefix>-${testRunId}` or a
      capture — NEVER a literal that repeats across runs;
    - *current/future/past-date* → computed in plain Java before the builder (`java.time`),
      never a hardcoded calendar date — a literal "2026-07-08" is stale tomorrow. The AI format
      cannot express computed values ⇒ such a field FORCES the Java DSL track (see rule 4);
    - *constant* (business codes, amounts, enum values) → verbatim from the case/KB;
    - unknown semantics → recorded assumption, or blocking missing information when the value
      changes the test's meaning.
    The design's step table documents the class of every non-constant field.
12. **Cleanup strategy** — one `db.cleanup` per seeded table:
    `DELETE FROM <schema>.<table>` (no WHERE!) + `whereTestRunId("<column>")`, and the paired
    `db.seed` DECLARES the SAME column with `taggedByTestRunId("<column>")` (the write-guard fails
    closed at run time unless that column is in the seed's INSERT column list bound to `:testRunId`).
    Document the residual-data risk: cleanup does not run if an earlier step fails (runner
    short-circuits), so rows must be harmless to leave behind and identifiable by `test_run_id`.
13. **Parallel-isolation strategy** — the design defaults to parallel-safe (all test data scoped by
    `${testRunId}`, Kafka expects discriminated, no shared static/instance state; the class needs NO
    parallel annotation and runs concurrently under the consumer's config). Call for `@StandIsolated`
    / `@ResourceLock("<alias>")` in the design ONLY when a step touches a resource that cannot be
    `testRunId`-isolated — a fixed port, a shared file, a process-wide singleton, or a
    non-`testRunId`-scopable external job.

## Forbidden in this skill

- Emitting YAML/JSON/Java (that is the authoring skills' job).
- Designing steps around SDK bypasses (raw clients, sleeps, eager IO).
- Designing writes to datasources without `write-allowed: true` + schema whitelist evidence.

## Checklist before handing off

- [ ] Track chosen with justification; if AI format — every step verified against the
      executable subset.
- [ ] Every contract detail in the step table (path, JSONPath/field, table/column, SQL,
      gRPC method) cites its source: KB entry id, case-text value, or a recorded assumption.
- [ ] Step table complete: id, type, alias, purpose, timeout (async), assertions, captures.
- [ ] Every `${var}` consumed is produced earlier (or is a built-in).
- [ ] Correlation source verified against the registry for every inject/fromContext step.
- [ ] Cleanup present for every seed; the seed's `taggedByTestRunId` column == the cleanup's
      `whereTestRunId` column; both scoped by `testRunId`.
- [ ] Parallel-safe: all test data scoped by `${testRunId}`, Kafka expects discriminated, no shared
      static/instance state; `@StandIsolated`/`@ResourceLock` called for only when a resource is not
      `testRunId`-isolable.
- [ ] Negative paths listed with the Java construct that will express them.

## Example

[`example-scenario-design.md`](../stand-test-scenario-design/example-scenario-design.md).
