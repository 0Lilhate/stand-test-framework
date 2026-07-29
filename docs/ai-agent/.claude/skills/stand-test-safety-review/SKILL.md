---
name: stand-test-safety-review
description: Adversarial guardrail review of generated stand-test artifacts — detects arbitrary URLs, inline secrets, destructive SQL, production envs, missing/unbounded timeouts, Thread.sleep, raw clients, validator bypasses, fixed ids without testRunId. Mandatory gate after every stand-test generation; any BLOCK finding stops the workflow.
version: 1
---

# Skill: stand-test-safety-review

Adversarially review a generated scenario/test/fixture set for guardrail violations **before**
it is compiled, run or shown for human review. Assume the artifact is hostile until proven
safe.

## When to use

Mandatory gate in every authoring workflow, after generation and after every regeneration.

## Input

All generated artifacts of the change: test class(es), scenario document(s), fixtures,
registry additions, build-file diffs.

## What to detect (finding → severity)

| # | Finding | How to detect | Severity |
|---|---|---|---|
| 1 | Arbitrary URL / direct host | grep `https?://`, `host:port` literals, absolute paths in `path`, `jdbc:`, comma-separated broker lists in scenario/test/fixture files. Exception: the starter registry's endpoint value twins (`base-url`/`url`/`target`/`bootstrap-servers`/`security-protocol`) — there verify the value is a `${ENV_VAR:...}` placeholder, not a resolved endpoint | BLOCK |
| 2 | Real secrets / inline auth | grep `Authorization|Bearer |Basic |password|token|secret|api[-_]?key|cookie` in step headers, fixtures, Java literals; any `*-ref` field whose value looks like a value (whitespace, `://`, scheme prefix, or the SDK-internal `literal://` marker); any CREDENTIAL value twin (`user`/`password`/`username`/`token`/`sasl-jaas-config`) in generated artifacts — the starter accepts them since the credential-twins feature, but kit policy keeps secrets as `*-ref` (a literal secret in a twin has no `requireReferenceShape` guard); on the STARTER surface also flag `${VAR}` placeholders inside `*-ref` fields (Spring resolves them into VALUES before the SDK sees the ref — starter refs must be bare env-var NAMES) | BLOCK |
| 3 | Destructive SQL / unsafe DB write | any `DROP|TRUNCATE|ALTER|CREATE|MERGE|GRANT|REVOKE` in step SQL; `INSERT ... ON CONFLICT`/`ON DUPLICATE KEY`; multi-statement (`;` inside); `DELETE`/`UPDATE` outside `db.cleanup`/`db.seed`; cleanup SQL carrying its own `WHERE`; author-supplied `param("testRunId", ...)`; a `db.seed` INSERT WITHOUT `taggedByTestRunId(...)`, or whose declared tag column is absent from the INSERT column list or differs from the paired cleanup's `whereTestRunId` column (rows leak across concurrent runs — the write-guard fails closed at run time) | BLOCK |
| 4 | Production environment | environment value not present in the test registry, or a registry addition that names a production stand | BLOCK |
| 5 | Missing/unbounded timeout | async step (`kafka.expect`, `*.expectEventually`, `grpc.unary`) without explicit timeout in design; timeout > 1h; AI grammar violations (`24h`, `0s`, fractions) | BLOCK |
| 6 | `Thread.sleep` / manual polling | grep `Thread.sleep|Awaitility|while.*retry|for.*poll` in Java; SQL sleep functions `pg_sleep|sleep|waitfor|benchmark|dbms_lock` | BLOCK |
| 7 | Script/code execution in declarative docs | any `script`/expression/`$( )` construct; unknown fields (schema is `additionalProperties:false` — run the schema to find them) | BLOCK |
| 8 | Direct broker/JDBC/gRPC client | imports of `org.apache.kafka.clients.*`, `java.sql.DriverManager`, `io.grpc.ManagedChannelBuilder`, `WebClient`/`RestTemplate`/`HttpClient` in test code | BLOCK |
| 9 | Validator bypass | `new DefaultScenarioRunner(`/`new DefaultStandClient(` in consumer test code; a Spring `ScenarioValidator`/`ScenarioRunner`/`StandClient` bean override; use of one-arg `validate(Scenario)` as a gate; `stand.test.enabled=false` | BLOCK |
| 10 | Fixed ids / hardcoded entity-instance-handles | literal unique keys in seeds/fixtures/paths/bodies — BOTH ids for entities the test creates AND ids that POINT AT one specific pre-provisioned stand row (client id, pinEQ, account id/number, deal id, an approved-ТУ instance). Heuristic grep over fixtures/bodies/paths, eye-checked against the SOURCE CASE TEXT: long numerics `[0-9]{6,}`, account numbers `\b40[0-9]{16,18}\b`, 6-char PIN tokens `\b[A-Z0-9]{6}\b` — flag any value not derived from `${testRunId}`/a capture. "It's a pointer to a stand object, not created data" is NOT a defense (not `testRunId`-isolated; couples the run to out-of-band state; the SUT still 404s when the pointed-at row is absent). Test-ownable handles → provision+capture; shared catalog rows (ТУ) → out-of-band + read-probe. EXEMPT (constants, not handles): reference/dictionary CODES (service/ПУ/branch/currency), monetary amounts / numeric business constants, and expected-assertion literals (a returned code/hash/enum the test checks) | BLOCK when the literal is a real id copied from the case (curated `valueHints: entity-handle` — the KB enum value for an entity-instance-handle — or case-labelled); HIGH on shape heuristic alone (must-confirm) |
| 11 | Hardcoded correlation | a literal correlation value in a header/key/metadata instead of `injectCorrelationId`/`${correlationId}` | HIGH |
| 12 | Caught SDK failures | `catch (StandTestAssertionError`/`StandTestException`/`AssertionError` around `stand.run` outside `assertThatThrownBy` | HIGH |
| 13 | Secrets relying on Allure masking | secret-shaped values in bodies/diagnostics "because the sink masks them" — masking has documented holes (XML, nested objects, key=value lines) | HIGH |
| 14 | PII / business data in fixtures | realistic personal/production data | HIGH |
| 15 | Unsanctioned dependencies | consumer build diff adds anything beyond `allure-junit5`, JSON-Schema validator (+jackson), JDBC driver | HIGH |
| 16 | Kafka expect without a per-run discriminator | a `kafka.expect` with neither `correlationIdFromContext()`/`correlation: {fromContext: true}` nor a `${...}`-derived `key` — a constant `.key(...)` alone (no `fromContext`) is refused at run time (two concurrent runs match each other's messages on a shared topic) | BLOCK |
| 17 | Shared mutable state in the test class | `static` mutable fields, or reused mutable instance objects, holding run-varying data (counters, captured values, shared builders) instead of flowing through captures / `${testRunId}` — the runner and step executors are shared across parallel test threads, so this races | HIGH |

Runtime backstop for 1–7, 9 and 16 exists (`ForbiddenOperation`-keyed validator + adapter guards +
JSON Schema; the DB write-guard and Kafka executor fail closed on an untagged seed / undiscriminated
expect), but the review must catch them **statically** — a violation that only explodes at run time
is still a defective artifact. Items 8, 10–15 and 17 have **no runtime enforcement** (a raw client or
a shared static field never enters the SDK pipeline at all, so nothing can intercept it) — the review
is the only net.

## Procedure

1. `grep`-sweep the diff with the patterns above (do not trust reading alone).
2. If AI-format: run schema validation + `AiScenarioParser` parse (both must pass) — their
   failures are findings too.
3. Check every alias in artifacts against the registry (or the approved additions table).
4. Check every seed/cleanup pair (seed declares `taggedByTestRunId` naming the SAME column the
   cleanup filters), all test data `:testRunId`-scoped, every `kafka.expect` discriminated, and no
   shared static mutable state in the test class.
5. Write the report per
   [`safety-review-template.md`](../stand-test-safety-review/safety-review-template.md):
   verdict `PASS` / `PASS-WITH-NOTES` / `BLOCK`, findings with file:line, exact fix per finding.

## Rules

- Any BLOCK finding stops the workflow; regenerate, do not hand-patch around a guardrail.
- Never "fix" a finding by weakening the check it violates (e.g. removing an assertion or
  widening a timeout to 1h without SLA evidence).
- Zero findings still requires the checklist run:
  [`safety-checklist.md`](../stand-test-safety-review/safety-checklist.md).
