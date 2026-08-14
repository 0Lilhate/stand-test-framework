---
version: 1
---

# Reference: why the protocol guardrails are what they are

This is **not** a rule. The rule is [`../rules/stand-test-guardrails.md`](../rules/stand-test-guardrails.md):
it says what may never be produced, and it is loaded into every session automatically. This file
carries the arguments — what each constraint defends against, which layer actually enforces it, and
where a constraint reads narrower or wider than it is.

Not auto-loaded. Read it before CHANGING a rule, or when one looks excessive; following a rule needs
only the rule.

---

## Three layers, and why the difference matters more than the list

The rule mirrors `ru.alfa.stand.test.core.validation.ForbiddenOperation`, but the codes are not all
enforced in the same place, and treating them as one list is how a reviewer stops looking:

- **Pre-flight, by the validator.** Environment, datasource, service, topic, gRPC target and UI
  application whitelists; destructive SQL; unbounded timeouts; sleeps; secrets in source. These fail
  before a request leaves the JVM, so a violation is a failed run rather than a dirty stand.
- **At run time, fail-closed, by the adapter.** The `db.seed` tag column and the `kafka.expect`
  discriminator. Both were review-only rules once, and both moved because the failure they prevent is
  invisible in a green single-threaded run and appears only when two runs overlap.
- **Review-only, enforced by nobody.** The endpoint value twins of the Spring starter, the copied
  business id, the invented contract detail. Spring resolves `${ENV_VAR:...}` before the SDK ever
  sees the value, and a fixture resolves a literal before that — so no `ForbiddenOperation` can fire
  on either. These are the rules a human and the safety-review stage must actually carry, and saying
  so is the difference between a guardrail and a slogan.

The write hook adds a fourth layer for a subset of these — but it reads text, so it is a sieve, not
a perimeter.

## Aliases: why the starter's value twins are an exception that changes nothing

A scenario never spells an address, because the registry is the one place an address exists. The
Spring starter admits a `base-url`/`url`/`target`/`bootstrap-servers`/`security-protocol` twin
carrying a `${ENV_VAR:...}` placeholder, and that is genuinely sanctioned — the Environment resolves
it, so nothing is committed and the alias still owns the mapping.

What it costs: a hardcoded endpoint in one of those fields is indistinguishable, to the SDK, from a
resolved one. Spring hands over a string. The rule therefore calls it a review finding rather than a
guardrail, and the reviewer is the only mechanism there is.

## DB writes: why the target table decides the shape

Two shapes exist because two kinds of table exist, and picking by preference rather than by table is
what produced both failure modes the guard now closes.

A table with a `test_run_id` marker column is **reapable**: a paired `db.cleanup` deletes by that
column, and the SDK appends the `WHERE … = :testRunId` itself — which is why the cleanup statement is
a bare `DELETE FROM <schema>.<table>` and why an author-written `WHERE` is refused. An author WHERE
plus the appended one is a filter nobody reviewed.

The tag has to be **declared** (`taggedByTestRunId`) and not merely present, because the write-guard
cannot otherwise tell the reaped column from a lookalike. A seed that tags some other column looks
correct, passes its own cleanup, and leaks rows across concurrent runs — the exact failure that only
shows up under load.

An ordinary business table has no such column, and asking a team to add one is a schema change for a
test. So `db.write` compensates by **primary key** through the run's undo-log, `identifiedBy(...)` is
mandatory, and every column it names must be bound in the INSERT — a write whose key cannot be
resolved is refused before it reaches the stand, so nothing un-undoable is ever written. This is also
why "the table has no marker column" stopped being an acceptable reason to declare a case blocked: it
was becoming the standard dodge, and the capability to do it properly already existed.

Cleanup does **not** run after a failed step — the runner short-circuits, deliberately, because a
failed run's rows are evidence. The residual-data note is mandatory for the same reason: evidence
nobody was told about is just litter.

## Kafka: why a constant key is refused outright

Two concurrent runs share a topic. A `kafka.expect` selecting on a constant key matches the other
run's message as readily as its own, so it passes for the wrong reason — and it passes reliably
enough in a single-threaded local run that the defect is invisible until CI runs the suite in
parallel. Hence the requirement of a per-run-unique discriminator, and hence the narrow exception: a
constant key is fine **alongside** `correlationIdFromContext()`, where it only narrows a set that is
already the run's own.

## Fixed ids: why a business id copied from the case is the same violation

The obvious reading of "no fixed test-data ids" is "do not invent a literal id" — and it misses the
case that actually occurs, which is a case document handing the author a real client id, pin, account
number or contract number and the author using it verbatim because the system did mint it, just not
in this test.

That value is a **pointer into shared mutable state**. It goes stale when someone else's test
changes the row's status, and it collides when two runs use it at once. Both failures look like
application defects and cost a day each.

Resolution is by **ownership**, not by literal:

- A *test-ownable* entity (client, account, deal, pin) is created inside the scenario through a
  KB-attested endpoint and captured — or seeded into a write-allowed whitelisted schema when no
  create-endpoint exists.
- A *shared stateful catalog row* (an approved ТУ) may not be seeded at all: the boundary rule
  forbids writing rows the test does not own. It is provisioned out of band — legitimately, that is
  what the boundary rule leaves room for — and verified with a read probe before the trigger, so a
  missing precondition fails as a precondition rather than as the assertion.
- Only when NEITHER route exists is this blocking missing information.

A reference/dictionary code (`PRICEASAVE`, `PU_NWA`, a branch number) is a **constant**, not an
instance handle: it names a kind, not a row, and it stays verbatim. Confusing the two in the other
direction — parameterising a dictionary code — produces a test that no longer tests the case.

Nothing at run time catches a copied business id: the fixture or the Spring Environment resolves the
literal before the SDK sees it. This one lives or dies on stage 8.

## Parallel safety: why it is construction rather than annotation

The SDK's model is classes `concurrent`, methods `same_thread`, one scenario run per thread, with a
distinct `testRunId`, `correlationId`, `VariableStore` and Kafka group per run. Under that model a
test is parallel-safe by default, and `@StandParallelSafe` on every class would say nothing while
looking like it said something.

`@StandIsolated` / `@ResourceLock` exist for what isolation cannot reach: a fixed port, a shared
file, a process-wide singleton. Using them more widely is not caution — it serialises the suite and
hides the state-sharing defect the isolation model exists to surface. Gradle `maxParallelForks > 1`
is banned outright because a second JVM defeats every in-process pool the SDK owns, the UI account
pool first.

## Pipeline bypass: why the one-arg validate is named specifically

`DefaultScenarioValidator.validate(scenario)` checks structure and runs **zero** guardrails; the
registry overload is what enforces whitelists. The one-arg call therefore looks exactly like a
guardrail gate, returns green, and proves nothing — which is why the rule names it rather than
leaving "use the validator" to be interpreted.

The same reasoning covers raw clients and hand-built runners: they are not forbidden because they are
inelegant, but because every guardrail in this document lives inside the path they bypass.

## Dependencies: why the list is closed

A new dependency is how a raw transport arrives without anyone importing one, and it outlives the
change that brought it. The sanctioned set is small and each addition is a human decision:
`allure-junit5`, a JSON-Schema 2020-12 validator with Jackson for the declarative track, and the JDBC
driver.
