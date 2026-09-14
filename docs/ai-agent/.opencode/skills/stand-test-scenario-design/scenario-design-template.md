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
  equals-only on `kafka.expect`/`db.expectEventually` (`rest.*` and `grpc.unary` take all five
  matchers), fixture-only bodies, no seed/cleanup: **confirmed / NOT confirmed → Java DSL**

## Wiring

- Consumer shape: `@SpringBootTest` + `@Autowired StandClient` | `@StandTest` (plain JUnit)
- Run gate: none (default) | `@EnabledIfEnvironmentVariable(named = "<ENV_VAR>", matches = ".+")` —
  optional, only for a variable with NO `${VAR:default}` default in the registry; state the reason
- Env vars required at run time: <list from the mapping report — `*-ref` names plus variables
  inside starter value-twin `${ENV_VAR:...}` placeholders>

## Step table (execution order)

| # | Step id | Type | Alias | Purpose | Timeout | Assertions (path → value → matcher) | Captures (var ← path/column) | Source (KB id / case / assumption) |
|---|---|---|---|---|---|---|---|---|
| 1 | `seed-...` | db.seed | `<ds>` | taggedByTestRunId(`test_run_id`) | n/a | n/a | n/a | KB `<probe/datasource id>` |
| 2 | `create-...` | rest.post | `<svc>` | trigger; injectCorrelationId | n/a | status 200; `$.status` → ACCEPTED → EQUALS | `orderId` ← `$.orderId` | KB `<endpoint id>` |
| 3 | `await-...` | kafka.expect | `<topic>` | | 30s | `$.status` → CREATED → EQUALS (equals-only!) | | KB `<topic id>`; timeout: assumption |
| 4 | `verify-...` | db.expectEventually | `<ds>` | | 10s | singleValue → DONE | | KB `<probe id>` |
| 5 | `cleanup-...` | db.cleanup | `<ds>` | whereTestRunId(`test_run_id`) | n/a | n/a | n/a | case (seed pairing rule) |

Every path/field/table/SQL/gRPC-method in the table cites its source: a KB entry id (projects
with a knowledge base), a case-text value, or a recorded assumption. `case` alone is only valid
when the case text literally states the detail.

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

- Unique keys derive from `${testRunId}`; system-generated ids only via capture (a fixed literal
  primary key collides when two runs seed at once).
- Seeds: `INSERT` into `<schema>.<table>` (whitelisted, `write-allowed: true`), `test_run_id` column
  bound to reserved `:testRunId` and DECLARED with `taggedByTestRunId("test_run_id")` (the same
  column the cleanup filters).

## Cleanup strategy

- <one line per seeded table>: `DELETE FROM <schema>.<table>` (no WHERE) + `whereTestRunId("<column>")`
  — the same `<column>` the paired seed tagged with `taggedByTestRunId`.
- Residual-data note: cleanup skipped if an earlier step fails (runner short-circuits);
  leftover rows are identifiable by `test_run_id` and harmless because <reason>.

## Parallel isolation

- Parallel-safe by construction: all test data scoped by `${testRunId}`, Kafka expects
  discriminated, no shared static/instance state → NO parallel annotation (the class runs
  concurrently under the consumer's `junit-platform.properties`).
- Non-isolable resource (fixed port / shared file / process-wide singleton), if any:
  `@StandIsolated` | `@ResourceLock("<alias>")` — reason: <why testRunId scoping is impossible here>.

## Negative paths (Java track)

| # | Variant scenario | Assertion |
|---|---|---|
| 1 | <what differs> | `assertThatThrownBy(() -> stand.run(...)).isInstanceOf(StandTestAssertionError.class)` |

## Open items carried from analysis

- Assumptions: <list>
- NOT-AUTOMATABLE: <list>
