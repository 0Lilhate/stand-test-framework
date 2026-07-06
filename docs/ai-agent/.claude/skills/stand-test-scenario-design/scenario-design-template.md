# Scenario Design: <scenario-id>

> Output of skill `stand-test-scenario-design`. Input for the authoring skills.
> Still no generated code here.

## Identity

- Scenario id: `<kebab-case-id>` (pattern `^[A-Za-z0-9][A-Za-z0-9._-]*$`)
- Environment: `<registry key, e.g. ift>`
- Tags: `integration`, `<domain>`
- Title: <human sentence for reports>

## Track decision

- Track: **Java DSL** | **AI JSON/YAML**
- Justification: <why>
- If AI format — executable-subset verification: every step below is one of
  `rest.get | rest.post | rest.expectEventually | kafka.send | kafka.expect | db.expectEventually | grpc.unary`,
  equals-only outside REST, fixture-only bodies, no seed/cleanup: **confirmed / NOT confirmed → Java DSL**

## Wiring

- Consumer shape: `@SpringBootTest` + `@Autowired StandClient` | `@StandTest` (plain JUnit)
- Run gate: `@EnabledIfEnvironmentVariable(named = "<ENV_VAR>", matches = ".+")`
- Env vars required at run time: <list from the mapping report — `*-ref` names plus variables
  inside starter value-twin `${ENV_VAR:...}` placeholders>

## Step table (execution order)

| # | Step id | Type | Alias | Purpose | Timeout | Assertions (path → value → matcher) | Captures (var ← path/column) |
|---|---|---|---|---|---|---|---|
| 1 | `seed-...` | db.seed | `<ds>` | | n/a | n/a | n/a |
| 2 | `create-...` | rest.post | `<svc>` | trigger; injectCorrelationId | n/a | status 200; `$.status` → ACCEPTED → EQUALS | `orderId` ← `$.orderId` |
| 3 | `await-...` | kafka.expect | `<topic>` | | 30s | `$.status` → CREATED → EQUALS (equals-only!) | |
| 4 | `verify-...` | db.expectEventually | `<ds>` | | 10s | singleValue → DONE | |
| 5 | `cleanup-...` | db.cleanup | `<ds>` | whereTestRunId(`test_run_id`) | n/a | n/a | n/a |

## Variables

| Variable | Produced by (step / built-in) | Consumed in |
|---|---|---|
| `${testRunId}` | built-in | seed SQL binds, fixture unique fields |
| `${orderId}` | step `create-...` capture | steps 3–5 params/paths |

## Correlation strategy

- Trigger step `<id>`: inject — alias `<name>` has `correlation: {source: HEADER|METADATA, name: <hdr>}` ✔
- Consumer step `<id>`: fromContext — topic alias `<name>` has HEADER correlation ✔
- Kafka carrier is HEADER-only; never fabricate correlation values.

## Test data strategy

- Unique keys derive from `${testRunId}`; system-generated ids only via capture.
- Seeds: `INSERT` into `<schema>.<table>` (whitelisted, `write-allowed: true`),
  `test_run_id` column bound to reserved `:testRunId`.

## Cleanup strategy

- <one line per seeded table>: `DELETE FROM <schema>.<table>` (no WHERE) + `whereTestRunId("<column>")`.
- Residual-data note: cleanup skipped if an earlier step fails (runner short-circuits);
  leftover rows are identifiable by `test_run_id` and harmless because <reason>.

## Negative paths (Java track)

| # | Variant scenario | Assertion |
|---|---|---|
| 1 | <what differs> | `assertThatThrownBy(() -> stand.run(...)).isInstanceOf(StandTestAssertionError.class)` |

## Open items carried from analysis

- Assumptions: <list>
- NOT-AUTOMATABLE: <list>
