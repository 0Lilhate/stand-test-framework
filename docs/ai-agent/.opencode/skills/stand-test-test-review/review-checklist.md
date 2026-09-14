# Review checklist (quality gate for generated tests)

Applied by `stand-test-test-review` and by the human approver. Complements — never replaces —
[`safety-checklist.md`](../stand-test-safety-review/safety-checklist.md).

## Traceability

- [ ] Business behaviour stated and linked — Java track: `@DisplayName` + javadoc; AI document:
      root `title`/`description` (a Java `.title(...)` is NOT expected — nothing reads it)
      the original case (ticket / manual case id).
- [ ] Every expected effect from the analysis is asserted somewhere; every dropped check is
      documented (assumptions + NOT-AUTOMATABLE list), not silently gone.
- [ ] For regression ports: step ↔ manual-case-step mapping recorded (javadoc on the Java track,
      step `description` in an AI document — human-readable only, Allure names steps type + id).
- [ ] KB alignment (projects with a knowledge base): every alias, path, topic, SQL statement
      and gRPC method in the test resolves to a KB entry or a recorded assumption — an
      unsourced contract detail is an "invented contract" HIGH finding.

## Structure

- [ ] Scenario id and step ids: kebab-case, business-meaningful, unique; explicit `.id(...)`
      on every step (derived defaults collide on repeats).
- [ ] Step order tells the story: seed → trigger → awaits/asserts → cleanup, one scenario.
- [ ] One scenario per test method; one behaviour per test.

## Assertions

- [ ] Matcher fits the phrase: equals vs contains vs full-string regex; EXISTS vs NOT_NULL
      chosen deliberately (JSON null counts as PRESENT for EXISTS).
- [ ] JSON types exact (`"100"` ≠ `100`; numbers compare by value; gRPC enums as protobuf
      JSON names).
- [ ] Equals-only respected on kafka/db — inexpressible checks reworded (e.g. moved to a REST
      or gRPC check, which take all 5 matchers) rather than approximated wrongly.
- [ ] No assertions on sensitive fields (failure messages echo expected/actual values).
- [ ] Negative paths use `assertThatThrownBy(...).isInstanceOf(StandTestAssertionError.class)`
      with a message fragment.

## Data flow

- [ ] Every `${var}` produced before use (capture or built-in); no dead captures.
- [ ] No fixed system-generated ids; uniqueness via `${testRunId}`.
- [ ] No stale static values: fields marked `run-unique` in KB `valueHints` (or unique by
      semantics) derive from `${testRunId}`/captures; date fields are computed in Java, never
      hardcoded calendar literals — a value that repeats or expires across runs is a finding.
- [ ] Fixtures exist for every reference, valid JSON, placeholder-correct.

## Robustness

- [ ] [`flakiness-checklist.md`](flakiness-checklist.md) fully green.
- [ ] Cleanup pairs every seed; `whereTestRunId` column correct; residual-data note present.
- [ ] Run gate: its ABSENCE is fine and is never a finding. If `@EnabledIfEnvironmentVariable` is
      present, it names a variable with no registry default, and the test SKIPS (not fails) without it.

## Style (consumer repos mirroring SDK checkstyle)

- [ ] AssertJ only; no `org.junit.jupiter.api.Assertions`/JUnit 4 imports; no `System.out`.
- [ ] `@DisplayName` present; one statement per line; blank line between members;
      Java-17-compatible sources.

## Verdict discipline

- [ ] CRITICAL/HIGH findings → CHANGES-REQUIRED (loop to authoring skill); never fixed by
      deleting checks or inflating timeouts.
- [ ] Human makes the final merge decision on the validation report.
