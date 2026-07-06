# Flakiness checklist

A generated test that flakes is worse than no test. Every item must hold.

## Waiting

- [ ] No fixed sleeps anywhere — all waiting is `rest.expectEventually` /
      `kafka.expect` / `db.expectEventually` (or a bounded `Awaiter` policy in rare
      plain-JUnit utility waits).
- [ ] Timeouts reflect the real SLA with headroom (~2–3×), not "1s because fast" and not
      "60m because safe". Defaults (30s) are acceptable only when stated as an assumption.
- [ ] Poll intervals left at defaults unless there is a reason (REST/DB 200ms, Kafka consumer
      poll 500ms).
- [ ] No assertions that depend on the wall clock, locale, or ordering the system does not
      guarantee.

## Kafka specifics

- [ ] Trigger and `kafka.expect` are in the SAME scenario — consumers are armed in the
      runner's prepare phase; a trigger fired outside the scenario (or before it) is
      invisible (start-from-now semantics).
- [ ] Selection is `correlationIdFromContext` (plus `.key(...)` only to disambiguate several
      same-correlation messages). A selection-free expect on a shared stand WILL flake.
- [ ] One `expect` per expected message (consume-and-advance: a matched record is never
      re-selected; a second identical expect times out).
- [ ] The test does not expect messages produced before the run.

## DB specifics

- [ ] `db.expectEventually` SELECT returns at most ONE row (id predicate present) — more than
      one row aborts immediately as ambiguous, and an under-constrained query can match
      another run's rows.
- [ ] All run data is scoped by `${testRunId}` / `test_run_id` column — parallel runs and
      leftover rows from crashed runs cannot collide.
- [ ] `db.query` with captures is not used where 0 rows is a legitimate outcome (0 rows under
      a capture is an infra error).
- [ ] Timestamps/dates are not asserted with `expectValue` (JDBC type equality is fragile);
      assert stable columns.

## REST specifics

- [ ] `expectEventually` used for anything phrased "eventually/after processing"; single-shot
      asserts only for synchronous responses.
- [ ] Presence matchers use definite JSONPaths (no `$..x`, no `[*]` — an empty list reads as
      "present").
- [ ] Assertions match JSON types exactly (`"100"` ≠ `100`); no assertions on
      volatile fields (generated timestamps, random tokens).

## Isolation

- [ ] Test data is created by the test (or seeded with `:testRunId`) — no reliance on data
      another test/team maintains.
- [ ] No static/shared state in the test class; everything flows through captures.
- [ ] The `@EnabledIfEnvironmentVariable` gate names a variable the scenario genuinely
      needs — the test skips cleanly on unconfigured machines.
- [ ] Residual-data note present: cleanup is skipped after an earlier failure
      (short-circuit) — leftovers must be harmless and identifiable.
