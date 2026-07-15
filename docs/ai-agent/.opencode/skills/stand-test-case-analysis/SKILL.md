---
name: stand-test-case-analysis
description: Extract the structure of a future stand-test-sdk autotest from a plain-text business case (goal, preconditions, trigger, expected REST/Kafka/DB/gRPC effects, timeouts, cleanup, missing info). Use FIRST whenever asked to create an autotest from a text case, ticket, or manual regression steps. Produces analysis only — no code.
---

# Skill: stand-test-case-analysis

Turn a plain-text business test case into a structured analysis that the design and authoring
skills can consume. **This skill produces no code and no scenario — analysis only.**

## When to use

Always first. Every authoring workflow starts here; never generate a test directly from raw
text.

## Input

- The text case (any format: ticket, Cucumber feature, manual regression step list, chat message).
- The consumer project's environment registry (`src/test/resources/stand-test-environments.yml`
  or `application.yml` under `stand.test.environments.*`) — read it if present.
- Existing tests in the consumer project — a STRUCTURAL/style reference only, NOT an authority on
  data lifecycle. A sibling that hardcodes business instance-handles (client/account/deal ids, pins,
  account numbers) copied from a case, or defers provisioning to an out-of-band runbook, is a KNOWN
  ANTI-PATTERN — do not adopt its data-origination shape (the lgot test
  `UlDiscountSchemeSimpleServiceRangeChangeTest` is exactly this shape). When an authoritative
  source exists for the same behaviour (a TestOps/Gherkin
  feature under `docs/tests/`, a manual regression that opens its own entities), its data lifecycle
  WINS over any sibling test — provision fresh via API+capture, see
  `../stand-test-java-dsl-authoring/example-provisioned-prelude.java`.

## What to extract

Fill every section of [`test-case-analysis-template.md`](../stand-test-case-analysis/test-case-analysis-template.md)
→ produce `TestCaseAnalysis.md` (or an equivalent structured markdown block in the reply):

| Section | What goes there | SDK mapping hint |
|---|---|---|
| Business goal | One sentence: what behaviour is proven | becomes scenario `title`/javadoc |
| Preconditions | State that must exist before the trigger | *Test-ownable entity* (client/account/deal/pin): provision via a KB-attested create-API + `capture` (or `DbStep.seed`, Java track) — a real business id/pin/account quoted in the case is a provisioning OUTPUT, never an inline literal. *Shared stateful catalog row* (approved ТУ): out-of-band + read-probe verify (`db.expectEventually` before the trigger), never seeded. *Reference CODE* (service/ПУ/branch/currency): a constant. Un-provisionable ⇒ Missing information, item 7 |
| Input data | Values sent in the trigger | inline body (Java) / `fixtures/*.json` (AI format); uniqueness via `${testRunId}` |
| Trigger action | The single action under test | `rest.post/put/delete`, `kafka.send`, `grpc.unary` + correlation inject |
| Expected REST response | status + response fields | `expectStatus` + `assertPath*` (all 5 matchers available) |
| Expected Kafka events | topic + message fields | `kafka.expect` — **equals-only**, HEADER correlation, must be in the same scenario as the trigger |
| Expected DB state | table/column + value | `db.expectEventually` — single row, single value, equals-only |
| Expected gRPC response | method + response fields | `grpc.unary` — equals-only, deadline mandatory, server must expose Reflection v1 |
| Timeout requirements | SLA phrases ("within a minute") | `withinSeconds(n)` / `"timeout": "60s"`; runtime cap 1h |
| Cleanup requirements | What data must be removed | `DbStep.cleanup(...).whereTestRunId(col)`; note: cleanup does NOT run after a failed step (short-circuit) |
| Negative paths | Error branches worth automating | Java track only: `assertThatThrownBy(() -> stand.run(...))` |
| Missing information | Everything you could not derive | see below |

## Missing information vs safe assumptions

Do **not** ask a question when a safe assumption exists. Record assumptions explicitly in an
`Assumptions` list. Safe assumptions:

- Timeout absent → default to 30s for Kafka/DB/REST polling (the SDK defaults), state it.
- Environment absent → the single environment declared in the registry, state it.
- Correlation strategy absent → SDK-owned correlation (`inject` on trigger, `fromContext` on
  the consumer step) when the aliases carry a `correlation:` config.
- Cleanup absent for seeded data → add a `db.cleanup` scoped by `whereTestRunId`.

Escalate to `Missing information` (blocking) only when the answer changes the test's meaning:

1. Which service/topic/datasource/grpc-target — and **no matching alias exists** in the registry.
2. Exact expected values where kafka/db/grpc checks are involved (equals-only — "contains X"
   cannot be expressed there and must be reworded or moved to a REST check).
3. Whether test data may be written to the DB at all (`write-allowed`, schema whitelist).
4. How the async effect is correlated to the trigger when the alias has no `correlation:` config.
5. Authentication identity, when the case implies a specific user (auth is per-service in the
   registry; two users against one service need two aliases — a registry change).
6. **Operation contract unknown** — the case does not state the HTTP method/path, response
   field, message schema, table/column or gRPC method, AND the knowledge base has no entry for
   it (`stand-test-kb-lookup` reports it under `missing`). Contract details are never derived
   from plausibility.
7. **Precondition entity un-provisionable** — the trigger depends on a business entity that must
   exist first, given in the case by an instance id/handle (client id, pinEQ, account id/number,
   deal id) or a shared stateful catalog row (an approved ТУ matching the index versions), and it
   cannot be established in-test. Classify its origin — do NOT copy the literal or assume it
   pre-exists: (a) *test-ownable* AND a KB-attested create-endpoint returns its id → a future
   `capture`, not blocking; (b) *shared stateful catalog row* → provisioned out-of-band and
   confirmed by a read-probe, not blocking IF the probe can confirm it, else blocking; (c) neither
   a create-endpoint nor a seedable write-allowed schema exists → BLOCKING. When blocking,
   distinguish the kind so the right owner acts: a **KB-coverage gap** (a real create-endpoint
   exists but is not curated → a human-approved KB entry unblocks it) vs a **transport gap** (the
   only provisioning path has no SDK-drivable surface — e.g. AS400 / TM unit macros — ⇒ the case is
   `NOT-AUTOMATABLE (current SDK)` as specified; flag it, do not fabricate a step). A case-supplied
   instance id is NEVER a safe assumption or a copyable constant (observed: `не найдена ТУ …`); a
   reference/dictionary CODE (service/ПУ/branch/currency) IS a constant and stays verbatim.

## Feasibility flags (mark, do not silently drop)

Mark a check `NOT-AUTOMATABLE (current SDK)` when the case needs:

- Numeric comparison (`>`/`<`), row counts, multi-row/multi-column DB assertions.
- "Message contains substring" on Kafka / regex on gRPC (equals-only adapters).
- "Row must NOT exist" in DB (no absence assertion; `rowExists` is rejected).
- Asserting a non-OK gRPC status declaratively (only Java `assertThatThrownBy`).
- Messages published **before** the scenario run (Kafka is start-from-now).
- Capturing HTTP response headers or the status code into a variable.
- PATCH / multipart / non-JSON response body assertions (status-only checks still work).

## Forbidden in this skill

- Generating scenario/test/fixture content.
- Inventing aliases, URLs, credentials, topic names — and equally paths, JSON field names,
  table/column names, SQL statements and gRPC method names (contract details come from the case
  text or the knowledge base, or become blocking missing information).
- Dropping an inexpressible check silently — it must appear as a flag or missing-info item.

## Checklist before handing off

- [ ] Every expected effect is attributed to exactly one transport (REST/Kafka/DB/gRPC).
- [ ] Every async expectation has a timeout figure (from the case or a stated assumption).
- [ ] Every piece of data flowing between steps is identified as a future `capture`.
- [ ] Every alias mentioned exists in the registry or is listed under Missing information.
- [ ] Assumptions and NOT-AUTOMATABLE flags are explicit.

## Example

See [`example-text-case.md`](../stand-test-case-analysis/example-text-case.md) and the analysis inside
[`example-scenario-design.md`](../stand-test-scenario-design/example-scenario-design.md).
