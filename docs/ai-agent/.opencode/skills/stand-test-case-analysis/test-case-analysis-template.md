# Test Case Analysis: <case-id-or-title>

> Output of skill `stand-test-case-analysis`. Fill EVERY section; write `n/a` explicitly
> rather than deleting a section. No code in this document.

## Source

- Origin: <ticket id / manual case id / free text>
- Original text: <verbatim or link>

## Business goal

<One sentence: what behaviour this test proves.>

## Preconditions

| # | State required before the trigger | How it will be established (API call / db.seed / already exists) |
|---|---|---|
| 1 | | |

## Input data

| Field | Value strategy | Notes |
|---|---|---|
| <field> | literal `100` / `${testRunId}`-derived / captured from step <id> | unique keys MUST derive from `${testRunId}` |

## Trigger action

- Transport: REST / Kafka / gRPC
- System (business name): <name> → alias resolution: see environment mapping
- Operation: <method + path / topic / package.Service/Method>
- Correlation: inject SDK correlationId? (yes/no; requires `correlation:` config on the alias)

## Expected effects

### REST response
- Status: <int>
- Fields: <path → expected value → matcher (EQUALS/CONTAINS/MATCHES/EXISTS/NOT_NULL)>

### Kafka events (equals-only!)
- Topic (business name): <name>
- Fields: <path → exact expected value>
- Within: <seconds>

### DB state (equals-only, single row/value!)
- Table/column: <schema.table.column>
- Expected value: <exact value>
- Row selector: <column = which captured variable>
- Within: <seconds>

### gRPC response (equals-only!)
- Method: <package.Service/Method>
- Fields: <path → exact expected value (enums as protobuf JSON names)>
- Deadline: <seconds>

## Timeout requirements

| Wait | Source in the case ("within a minute") | Chosen timeout |
|---|---|---|

## Cleanup requirements

| Data created | Cleanup step | Scoping |
|---|---|---|
| <schema.table rows> | db.cleanup + whereTestRunId(`<column>`) | rows tagged with `test_run_id` = `:testRunId` |

## Negative paths (Java track only)

| # | Scenario | Expected failure |
|---|---|---|
| 1 | | `StandTestAssertionError` containing "<fragment>" |

## NOT-AUTOMATABLE (current SDK)

| Check from the case | Why (SDK limitation) | Disposition (reword / drop / manual) |
|---|---|---|

## Assumptions (safe, proceeding without asking)

1. <assumption + why it is safe>

## Missing information (BLOCKING — needs a human answer)

1. <question + why it blocks>
