# Example: safety + quality review for case OT-101 artifacts

> Reviews of [`generated-java-example.java`](../stand-test-java-dsl-authoring/example-generated.java) and
> [`generated-yaml-example.yaml`](../stand-test-yaml-authoring/example-generated.yaml), filled from the templates.
> Shows what a normal, slightly imperfect result looks like.

## Safety Review: order-created-projection

**Verdict: PASS**

| # | Gate | Result | Evidence |
|---|---|---|---|
| 1 | No arbitrary URLs / hosts / JDBC | PASS | grep clean; only aliases `order-service`/`order-events`/`orders-db` |
| 2 | No secrets / inline auth | PASS | only `Content-Type` header; auth via registry BASIC refs |
| 3 | No destructive SQL | PASS | single SELECT; no writes anywhere |
| 4 | No production environment | PASS | `ift` present in test registry |
| 5 | Bounded timeouts | PASS | kafka 30s, db 30s; within 1h cap and AI grammar |
| 6 | No sleeps / manual polling | PASS | grep clean; waits via `kafka.expect` + `db.expectEventually` |
| 7 | No scripts / unknown fields | PASS | schema validation of the YAML variant: empty message set; parse gate clean |
| 8 | No raw clients | PASS | imports: SDK + JUnit + AssertJ + Spring only |
| 9 | No validator bypass | PASS | injected `StandClient`; no manual runner, no bean overrides |
| 10 | No fixed ids | PASS | `externalId` = `order-${testRunId}`; `orderId` captured |
| 11 | No hardcoded correlation | PASS | `injectCorrelationId()` / `correlation.inject` only |
| 12–15 | caught failures / masking reliance / PII / dependencies | PASS | negative case asserts a 200-REJECTED response, nothing caught; fixture data synthetic; no build changes |

## Generated Test Review: order-created-projection

**Verdict: APPROVE-WITH-NOTES**

| Dimension | Result | Notes |
|---|---|---|
| Coverage vs the case | Issues→documented | Manual step 5's "no event must be published" is NOT asserted — correctly documented in the class javadoc as an SDK gap (no negative-receive construct). Reviewer accepts. |
| Readability | OK | ids `create-order`/`await-order-event`/`verify-projection` tell the story |
| Assertion correctness | OK | `MATCHES` regex on REST (gRPC would also accept it); kafka/db equals-only respected; types exact |
| Non-flaky awaits | OK | trigger + `kafka.expect` in one scenario; `correlationIdFromContext`; DB SELECT keyed by `:orderId` (single row) |
| Correlation usage | OK | inject on trigger; registry HEADER config confirmed |
| Capture/resolve | OK | `${orderId}` produced by step 1, consumed by step 3; no dead captures |
| Cleanup | Note (MEDIUM) | No SDK cleanup possible (application-owned rows without `test_run_id`); residual-data note present and relies on stand purging — flagged for the human to confirm the purge policy |
| Diagnostics & reporting | OK | tags set; no assertions on Allure content |
| Wiring & gating | OK | `@SpringBootTest` + `@Autowired StandClient`; gate on `ORDER_SERVICE_URL`; skip-verified |

### Findings

| # | Severity | File:line | Finding | Suggested fix |
|---|---|---|---|---|
| 1 | MEDIUM | generated-java-example.java (class javadoc) | Residual reporting rows depend on stand purge policy | Human confirms purge policy OR the application team adds a test-data purge endpoint; do NOT invent a `db.cleanup` (datasource is `write-allowed: false`) |
| 2 | LOW | generated-yaml-example.yaml | YAML variant duplicates the Java happy path | Keep exactly one committed variant per case; the other stays an illustration |

### Recommendation to the human approver

Merge the Java test; confirm the reporting-purge assumption (finding 1) or open a task for a
purgeable test-data path. The uncovered "no event published" check stays a documented gap
until the SDK offers a negative-receive construct.
