---
name: stand-test-safety-review
description: Adversarial guardrail review of generated stand-test artifacts — detects arbitrary URLs, inline secrets, destructive SQL, production envs, missing/unbounded timeouts, Thread.sleep, raw clients, validator bypasses, fixed ids without testRunId. Mandatory gate after every stand-test generation; any BLOCK finding stops the workflow.
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
| 2 | Real secrets / inline auth | grep `Authorization|Bearer |Basic |password|token|secret|api[-_]?key|cookie` in step headers, fixtures, Java literals; any `*-ref` field whose value looks like a value (whitespace, `://`, scheme prefix, or the SDK-internal `literal://` marker); secrets in a starter VALUE field (`user`/`password`/`token` twins do not exist — flag any attempt) | BLOCK |
| 3 | Destructive SQL | any `DROP|TRUNCATE|ALTER|CREATE|MERGE|GRANT|REVOKE` in step SQL; `INSERT ... ON CONFLICT`/`ON DUPLICATE KEY`; multi-statement (`;` inside); `DELETE`/`UPDATE` outside `db.cleanup`/`db.seed`; cleanup SQL carrying its own `WHERE`; author-supplied `param("testRunId", ...)` | BLOCK |
| 4 | Production environment | environment value not present in the test registry, or a registry addition that names a production stand | BLOCK |
| 5 | Missing/unbounded timeout | async step (`kafka.expect`, `*.expectEventually`, `grpc.unary`) without explicit timeout in design; timeout > 1h; AI grammar violations (`24h`, `0s`, fractions) | BLOCK |
| 6 | `Thread.sleep` / manual polling | grep `Thread.sleep|Awaitility|while.*retry|for.*poll` in Java; SQL sleep functions `pg_sleep|sleep|waitfor|benchmark|dbms_lock` | BLOCK |
| 7 | Script/code execution in declarative docs | any `script`/expression/`$( )` construct; unknown fields (schema is `additionalProperties:false` — run the schema to find them) | BLOCK |
| 8 | Direct broker/JDBC/gRPC client | imports of `org.apache.kafka.clients.*`, `java.sql.DriverManager`, `io.grpc.ManagedChannelBuilder`, `WebClient`/`RestTemplate`/`HttpClient` in test code | BLOCK |
| 9 | Validator bypass | `new DefaultScenarioRunner(`/`new DefaultStandClient(` in consumer test code; a Spring `ScenarioValidator`/`ScenarioRunner`/`StandClient` bean override; use of one-arg `validate(Scenario)` as a gate; `stand.test.enabled=false` | BLOCK |
| 10 | Fixed ids without testRunId | literal unique keys in seeds/fixtures/paths for entities the test creates (heuristic: hardcoded UUIDs/`"id": "o-1"`-style values not derived from `${testRunId}` or a capture) | HIGH |
| 11 | Hardcoded correlation | a literal correlation value in a header/key/metadata instead of `injectCorrelationId`/`${correlationId}` | HIGH |
| 12 | Caught SDK failures | `catch (StandTestAssertionError`/`StandTestException`/`AssertionError` around `stand.run` outside `assertThatThrownBy` | HIGH |
| 13 | Secrets relying on Allure masking | secret-shaped values in bodies/diagnostics "because the sink masks them" — masking has documented holes (XML, nested objects, key=value lines) | HIGH |
| 14 | PII / business data in fixtures | realistic personal/production data | HIGH |
| 15 | Unsanctioned dependencies | consumer build diff adds anything beyond `allure-junit5`, JSON-Schema validator (+jackson), JDBC driver | HIGH |

Runtime backstop for 1–7 and 9 exists (`ForbiddenOperation`-keyed validator + adapter guards +
JSON Schema), but the review must catch them **statically** — a violation that only explodes
at run time is still a defective artifact. Items 8 and 10–15 have **no runtime enforcement**
(a raw client never enters the SDK pipeline at all, so nothing can intercept it) — the review
is the only net.

## Procedure

1. `grep`-sweep the diff with the patterns above (do not trust reading alone).
2. If AI-format: run schema validation + `AiScenarioParser` parse (both must pass) — their
   failures are findings too.
3. Check every alias in artifacts against the registry (or the approved additions table).
4. Check every seed/cleanup pair and `:testRunId` scoping.
5. Write the report per
   [`safety-review-template.md`](../stand-test-safety-review/safety-review-template.md):
   verdict `PASS` / `PASS-WITH-NOTES` / `BLOCK`, findings with file:line, exact fix per finding.

## Rules

- Any BLOCK finding stops the workflow; regenerate, do not hand-patch around a guardrail.
- Never "fix" a finding by weakening the check it violates (e.g. removing an assertion or
  widening a timeout to 1h without SLA evidence).
- Zero findings still requires the checklist run:
  [`safety-checklist.md`](../stand-test-safety-review/safety-checklist.md).
