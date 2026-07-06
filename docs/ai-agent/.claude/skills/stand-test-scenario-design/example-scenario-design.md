# Example: TestCaseAnalysis + ScenarioDesign for case OT-101

> Derived from [`text-case-example.md`](../stand-test-case-analysis/example-text-case.md). Generic aliases; the registry
> snippet uses env-var NAMES only.

## Part 1 — TestCaseAnalysis (condensed)

- **Business goal**: a created order is accepted synchronously, published as `ORDER_CREATED`,
  and projected to the reporting DB as `DONE`.
- **Preconditions**: none (order creation needs no pre-existing data).
- **Input data**: amount `100`; client-supplied `externalId` derived from `${testRunId}`
  (no fixed ids).
- **Trigger**: REST POST `/api/orders` on the order service; correlation injected
  (alias has HEADER correlation).
- **Expected effects**:
  - REST: status 200; `$.status` EQUALS `ACCEPTED`; `$.orderId` MATCHES `ord-[0-9]+`
    (regex → REST matcher, allowed).
  - Kafka: event on the order-events topic, `$.status` EQUALS `CREATED` — equals-only is
    fine here; correlation `fromContext`.
  - DB: `reporting.orders` row for the captured `orderId` reaches `status = 'DONE'` within 30s
    → `db.expectEventually`, single row by id, equals.
- **Timeouts**: DB 30s (from the case); Kafka 30s (assumption: same SLA — recorded).
- **Cleanup**: the flow itself inserts via the application; rows are keyed by the captured
  `orderId`, not by `test_run_id` → the SDK cleanup cannot target them
  (`whereTestRunId` requires a `test_run_id` column). **Assumption**: the reporting DB is
  periodically purged on the stand; recorded as residual-data note. No `db.seed` needed.
- **Negative path**: negative amount → 200 + `$.status` EQUALS `REJECTED`; "no event
  published" is **NOT-AUTOMATABLE** as a direct assertion (no "never receives" construct) —
  disposition: assert the REJECTED response only, note the gap.
- **Auth**: test user — registry `auth: {scheme: BASIC, ...}` on the service alias; the test
  itself carries NO auth headers.
- **Missing information**: none blocking (aliases exist below).

## Part 2 — Environment mapping (report excerpt)

| Case entity | Alias | Registry evidence |
|---|---|---|
| Order Service | `order-service` | `services.order-service`: `base-url-ref: ORDER_SERVICE_URL`, HEADER correlation `X-Correlation-Id`, BASIC auth refs |
| order events topic | `order-events` | `topics.order-events`: HEADER correlation |
| reporting DB | `orders-db` | `datasources.orders-db`: refs + `allowed-schemas: [reporting]`, `write-allowed: false` (read-only — consistent with no-seed design) |

Env vars required to run: `ORDER_SERVICE_URL`, `ORDER_USER`, `ORDER_PASSWORD`,
`ORDERS_DB_URL`, `ORDERS_DB_USER`, `ORDERS_DB_PASSWORD`, `KAFKA_BOOTSTRAP`.
Run gate: `ORDER_SERVICE_URL`.

## Part 3 — ScenarioDesign

- **Scenario id**: `order-created-projection` · **Environment**: `ift` · **Tags**:
  `integration`, `orders`
- **Track**: **Java DSL** — reasons: regex matcher lives on REST (fine for AI format too),
  but the negative path needs a second scenario + `assertThatThrownBy` is not needed here…
  the deciding factor: team convention (Spring Boot consumer) and the negative variant in the
  same class. An AI-format variant of the happy path is also produced for illustration:
  [`generated-yaml-example.yaml`](../stand-test-yaml-authoring/example-generated.yaml).
- **Wiring**: `@SpringBootTest` + `@Autowired StandClient`;
  `@EnabledIfEnvironmentVariable(named = "ORDER_SERVICE_URL", matches = ".+")`.

### Step table (happy path)

| # | Step id | Type | Alias | Timeout | Assertions | Captures |
|---|---|---|---|---|---|---|
| 1 | `create-order` | rest.post | `order-service` | — | status 200; `$.status`→`ACCEPTED`→EQUALS; `$.orderId`→`ord-[0-9]+`→MATCHES | `orderId` ← `$.orderId` |
| 2 | `await-order-event` | kafka.expect | `order-events` | 30s | `$.status`→`CREATED`→EQUALS | — |
| 3 | `verify-projection` | db.expectEventually | `orders-db` | 30s | singleValue `DONE` | — |

### Variables

| Variable | Produced by | Consumed in |
|---|---|---|
| `${testRunId}` | built-in | request body `externalId` |
| `${orderId}` | `create-order` capture | step 3 SQL bind |

### Correlation

`create-order` injects (HEADER `X-Correlation-Id` per registry);
`await-order-event` selects `fromContext`. Trigger and expect in ONE scenario.

### Negative path (separate scenario in the same class)

`order-rejected-negative-amount`: rest.post amount `-1` → status 200 +
`$.status`→`REJECTED`. Happy-path style assertion (the API responds 200), so NO
`assertThatThrownBy` needed. "No event published" — documented as not asserted.
