# Rules: stand-test-sdk autotest generation guardrails

Non-negotiable rules for ANY agent work that creates or modifies stand-test autotests.
They mirror the runtime `ru.alfa.stand.test.core.validation.ForbiddenOperation` enum and the
review-only rules that have no runtime enforcement. Details and detection patterns:
`.claude/skills/stand-test-safety-review/safety-checklist.md`.

## Process

- Analysis before generation: text case → `stand-test-case-analysis` →
  `stand-test-environment-mapping` → `stand-test-scenario-design` → only then authoring.
- Prefer a safe, recorded assumption over a question; escalate only blocking
  `Missing information` (missing alias, exact expected values for equals-only checks,
  write permission, correlation strategy, auth identity).
- Every generated artifact passes `stand-test-safety-review` and compiles / validates
  before it is shown for human approval. A human makes the merge decision.

## Hard constraints (violations BLOCK, never work around)

- Logical aliases only in scenarios/tests — never URLs, hosts, ports, JDBC strings, bootstrap
  servers. In the Spring-starter REGISTRY the sanctioned exception is the endpoint value twins
  (`base-url`/`url`/`target`/`bootstrap-servers`/`security-protocol`), and even there only as
  `${ENV_VAR:...}` placeholders — a hardcoded endpoint in a value field is a review finding
  (no runtime check catches it: Spring resolves before the SDK sees it).
- No secrets anywhere: no `Authorization`/token/cookie/api-key headers or Bearer/Basic
  values; auth comes from the registry (`auth:` with `*-ref` env-var NAMES).
- No production environments in any test registry.
- No destructive SQL: no DDL/TRUNCATE/MERGE/upserts/multi-statement; DB writes only via
  `db.seed`/`db.cleanup` on `write-allowed` datasources into whitelisted schemas, scoped by
  `:testRunId` (`whereTestRunId`, no author WHERE in cleanup SQL).
- No `Thread.sleep`, no Awaitility, no manual polling, no SQL sleep functions — async only
  via `rest.expectEventually` / `kafka.expect` / `db.expectEventually` (bounded `Awaiter`
  for rare utility waits).
- Every async wait has an explicit bounded timeout (≤ 1 hour; AI grammar
  `≤99999ms / ≤999s / ≤60m`).
- `testRunId`/`correlationId` are SDK-owned: never invented or hardcoded; uniqueness via
  `${testRunId}`; correlation via `injectCorrelationId()` / `correlation: {inject|fromContext}`.
- No fixed test-data ids; system-generated ids only via `capture` → `${var}`.
- No pipeline bypass: no eager IO in builders, no raw HTTP/Kafka/JDBC/gRPC clients, no
  `new DefaultScenarioRunner(...)` / `new DefaultStandClient(...)` in consumer code, no
  validator/runner/StandClient bean overrides, never the one-arg `validate(Scenario)` as a
  guardrail gate (structural-only).
- Never catch `StandTestAssertionError`/`StandTestException` to make a test pass; negative
  paths only via `assertThatThrownBy`.
- Never hide a failure: no assertion deletion, no `@Disabled` without a ticket, no blind
  timeout inflation.
- Do not modify SDK modules, add adapters, or change core APIs while authoring tests; new
  consumer dependencies limited to `allure-junit5:2.29.1`, a JSON-Schema 2020-12 validator
  (+ jackson) for the JSON track, and the JDBC driver — each human-approved.

## Definition of done for a generated test

- Java: compiles + checkstyle-clean; gated with `@EnabledIfEnvironmentVariable` (skips
  without stand config); AssertJ-only assertions.
- AI-format document: JSON Schema validation EMPTY + `AiScenarioParser` parse clean +
  `DefaultScenarioValidator().validate(scenario, registry)` clean.
- Safety review PASS + quality review APPROVE + human approval on the validation report.
