# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Язык общения

**Отвечай пользователю на русском языке.** Это касается объяснений, разборов, выводов и вопросов. При
этом технические артефакты оставляй на английском по существующему стилю репозитория: код и идентификаторы
(классы, методы, файлы), Javadoc и комментарии в коде, сообщения коммитов/PR. Термины и фрагменты кода
внутри русского текста не переводи.

## What this repo is

`stand-test-framework` is a multi-module Gradle (Kotlin DSL) build that produces **`stand-test-sdk`** —
an internal Java **test SDK** consumed by other teams as a Gradle `testImplementation` dependency. It
is a **thin facade** over mature tools (Spring WebClient, `kafka-clients`, JDBC, gRPC, JUnit 5,
Allure) that gives one consistent way to write integration/e2e tests against **real DEV/IFT stands**
(Testcontainers is explicitly *not* the basis). The SDK standardizes `scenarioId`/`testRunId`/
`correlationId`, a single await mechanism (no `Thread.sleep`), unified reporting, and a constrained
declarative format safe for AI-generated tests.

**`docs/arch/stand-test-sdk-implementation-plan.md` is the source of truth.** Read it before
implementing anything — it defines the module graph, the core contracts (§8 "Итерация 0"), the MVP
scope (§6), and the strict implementation order (§7). Each module also has a `README.md`, and the root
`README.md` is the consumer-facing quick start (module table, `stand-test-environments.yml` example,
plain-JUnit and Spring Boot setup) — keep it in sync when consumer-visible behaviour changes.

## Build & test commands

```bash
./gradlew build                       # full build: compile + checkstyle + tests, all modules
./gradlew :stand-test-core:build      # build one module
./gradlew :stand-test-core:test       # run a module's tests
./gradlew :stand-test-core:checkstyleMain :stand-test-core:checkstyleTest   # lint only (main + test)
./gradlew publishToMavenLocal         # publish modules locally (never needs the remote-repo properties)
./gradlew publish -PstandTestPublishUrl=<repo>   # remote publish; docs/publishing.md lists all properties

# Run a single test class / method (JUnit 5 platform):
./gradlew :stand-test-core:test --tests 'ru.alfa.stand.test.core.variable.VariableResolverTest'
./gradlew :stand-test-core:test --tests 'ru.alfa.stand.test.core.variable.VariableResolverTest.resolve_adjacentAndCoercion'
```

Use `--console=plain` for clean CI-style output. Configuration cache, parallel and build-cache are on
(`gradle.properties`), so unchanged tasks report `UP-TO-DATE`/`FROM-CACHE`.

## Build conventions (non-obvious, enforced)

- **No `buildSrc` / convention plugins.** All shared configuration lives in the root
  `build.gradle.kts` `subprojects { }` block plus the version catalog `gradle/libs.versions.toml`.
  Add dependencies to a module by referencing catalog accessors (`libs.junit.jupiter`,
  `libs.assertj.core`, …); add new versions/libraries to the catalog, not inline coordinates.
- **`stand-test-bom` is a `java-platform`** and is deliberately *skipped* by the root `subprojects`
  block (it must not get `java-library`/checkstyle). External consumers import it via
  `testImplementation(platform("ru.alfa.stand.test:stand-test-bom:<version>"))`.
- **Checkstyle is zero-tolerance** (`maxWarnings = 0`, config `checkstyle.xml`) and runs on **both**
  `src/main/java` and `src/test/java`. The build fails on any violation. Notable rules that change how
  you write code:
  - `org.junit.jupiter.api.Assertions` and JUnit 4 `org.junit.Test` are **banned imports** → use
    **AssertJ** (`assertThat`, `assertThatThrownBy`, `assertThatCode`) with JUnit 5 (`@Test`,
    `@DisplayName`).
  - Non-JetBrains `@NotNull`/`@Nullable`/`@NonNull` are banned → use `Objects.requireNonNull` / blank
    checks instead of nullability annotations.
  - `OneStatementPerLine` (no `{ this.x = x; return this; }` one-liners — builders are verbose),
    `EmptyLineSeparator` (blank line between members), `MutableException` (exception fields must be
    `final`), no tabs, `System.out/err` forbidden. `LineLength` max is 1000 (so long lines are fine —
    don't wrap method chains, since `SeparatorWrapDot` would then require the `.` at line start).
- **Java toolchain is 21** (LTS), but the build compiles with **`--release 17`** (catalog
  `javaRelease`, applied in the root `subprojects` `JavaCompile` block), so bytecode targets Java 17 and
  the SDK loads on consumer JDK 17/21/24 (plan §14 resolved). `--release 17` also bans APIs newer than 17,
  so keep sources 17-compatible (no Sequenced-collection APIs, `Math.clamp`, virtual threads,
  record-patterns / pattern-switch). To retarget, change `javaRelease` only. Gradle wrapper is 9.3.0.
- Indentation: **4 spaces** for Java, **2 spaces** for `*.kts`/`*.toml`/`*.yaml` (`.editorconfig`).
- Coordinates: group `ru.alfa.stand.test`, base package `ru.alfa.stand.test.<module>`.

## Architecture: the single Scenario Model pipeline

Both DSL inputs converge on one immutable model; only that model is executed (no runtime duplication):

```
Java DSL (lazy builder) ─┐
                         ├─▶ Scenario Model ─▶ ScenarioValidator ─▶ ScenarioRunner ─▶ StepExecutor SPI ─▶ adapter executors ─▶ real DEV/IFT stand
YAML DSL ────────────────┘                                          (owns VariableStore)
```

- **The Java DSL is a lazy builder.** It assembles an immutable `Scenario` and executes nothing;
  imperative eager-IO in a fluent chain is forbidden because it would bypass the validator/guardrails.
- **`stand-test-core` is the dependency-graph sink** — it depends on no sibling module and on no
  adapter/IO library. Its one sanctioned external dependency is the logging facade `slf4j-api` (plan
  §17: SLF4J + MDC) — a pure facade with no binding/IO, so the "JDK-only, no IO" invariant still holds
  and the consumer supplies the binding. It owns the generic model (`Scenario`/`ScenarioStep`), the SPI
  (`ScenarioRunner`, `StepExecutor`, `StepExecutionContext`), value objects, result/event models,
  validation and exceptions. **Typed steps (`RestStep`/`KafkaStep`/…) and `StepExecutor`
  implementations live in the adapter modules**, never in core; the runner dispatches by
  `ScenarioStep.type()` through the SPI, so core needs no compile-time edge to any adapter.

### Module graph (`A → B` = A depends on B; keep this acyclic, core is the only sink)

- `await`, `junit`, `rest`, `kafka`, `db`, `grpc` → `core` (and the adapters + junit also → `await`)
- `allure` → `core`; `scenario-yaml` → **core only** (adapters resolved via SPI at runtime, no compile edges); `ai-schema` → **core only** (no runtime/adapter deps, no `scenario-yaml`); `config` → **core only** (+ SnakeYAML; ships the `FileEnvironmentRegistry` SPI provider that loads `stand-test-environments.yml`)
- `spring-boot-starter` → the runtime modules it wires as `compileOnly` optionals (never the reverse); `bom` is the `java-platform` outside the compile graph — it constrains every published module plus the curated third-party versions (only external consumers import it)
- **Adapter modules must not depend on each other.** Each module's `build.gradle.kts` keeps its
  `Planned internal dependencies` as commented stubs that must match this target graph.

### Core contracts to respect (plan §8)

- **`ScenarioContext` is immutable metadata only** (ids, environment, tags, createdAt). Runtime
  variables live in a separate **mutable `VariableStore`**, one per run, owned by the runner — never
  static/global/`ThreadLocal`. This is what makes parallel runs isolated.
- **Failure semantics:** assertion failures are raised as `StandTestAssertionError` (extends
  `AssertionError`, so JUnit/Allure treat them as a failed test); infrastructure/config problems as
  `StandTestException` (extends `RuntimeException`). A `StepStatus.FAILED` is a reporting record and
  must never silently substitute for a thrown failure.
- **`correlationId` is SDK-owned** and injected outbound (REST header / Kafka key / gRPC metadata);
  capturing it from a response is a fallback only.
- **`ForbiddenOperation` is the single source of truth** for guardrails: the runtime
  `DefaultScenarioValidator` and the `stand-test-ai-schema` JSON Schema both derive from it (a
  cross-check test pins the schema's rules table to the enum), and the runtime validator re-enforces
  the schema's value-level guardrails (secret headers, SQL sleep functions, timeout bounds) so a
  document that skipped the schema pass meets the same net. **`EnvironmentRegistry`** resolves logical
  aliases (service/topic/datasource/gRPC) to endpoints + **secret references** (never secret values),
  and is the whitelist enforcement point. Nuance: in the Spring starter path, non-secret ENDPOINT
  fields may instead carry Spring-resolved values via value twins (`base-url`/`url`/`target`/
  `bootstrap-servers`/`security-protocol`), wrapped internally as `SecretReferences.literal(...)` so
  adapters resolve them verbatim. Secret credential fields (auth password/token, datasource password,
  Kafka SASL) may use the same value-twin spelling — a Spring-resolved `${VAR:default}` is wrapped as a
  `literal` — with a real consumer trade-off: the resolved secret then materialises in the Spring
  Environment (actuator `/env`, logs) and any inline default lives in the config file, so the `*-ref`
  spelling (a bare env-var NAME resolved lazily, never bound into the Environment) stays the choice when a
  secret must appear nowhere but the variable. Two hard rules survive: the SDK-internal `literal://` marker
  is rejected fail-closed in user config, and a `*-ref` field must be a bare NAME — never a `${...}`
  placeholder (on the starter Spring collapses it before the SDK sees the ref, which is then misread as a
  variable name — the double-resolution trap). Service credentials otherwise follow this value-twin/ref
  model: an optional per-service `AuthConfig` (BASIC/BEARER) makes the REST executor inject
  `Authorization` at execution time — the sanctioned path; inline auth headers in scenarios stay banned.
- Value types are immutable `record`s with defensive copies (`List`/`Set`/`Map.copyOf`).

## Current state & where to work

**All modules are implemented** (the plan's iterations 0–10 plus the follow-on modules):
**`stand-test-core`** (models, value objects, contracts, SPI, the `core.validation` SQL
classifier/`SqlSpanScanner`, the pre-flight guardrail validator, the `core.event` reporting events and
the `core.assertion` matcher evaluator — `AssertionMatcher`/`AssertionMatchers`, absent wire key =
EQUALS), **`stand-test-await`**, **`stand-test-junit`**, **`stand-test-rest`** (all five assertion
matchers, registry-driven service auth, `rest.expectEventually` GET-polling through the await engine —
transport errors abort as infra failures, 5xx polls through, captures apply to the final response only),
**`stand-test-kafka`**, **`stand-test-db`** (design record in `docs/arch/stand-test-db-decisions.md`,
hardening in `docs/arch/stand-test-db-remediation-plan.md`), **`stand-test-grpc`** (unary via server
reflection + `DynamicMessage`; all five assertion matchers, at parity with REST), **`stand-test-allure`** (with sink-side secret masking of attachment
bodies), **`stand-test-scenario-yaml`** (two surfaces: given/then YAML and the AI steps/type format),
**`stand-test-ai-schema`** (JSON Schema + generation rules; a cross-check test pins the matcher grammar
to the core enum), **`stand-test-spring-boot-starter`** (Boot-3 auto-configuration, adapters as
`compileOnly` optionals) and **`stand-test-config`** (file-based `EnvironmentRegistry` SPI provider).
**`stand-test-example`** is a test-only showcase (offline doubles, not published); **`stand-test-bom`**
is the `java-platform` carrying constraints for every published module. Known asymmetry: **Kafka** is the
only adapter still equals-only — `KafkaAssertion` carries no matcher field and `KafkaStepParameters` has no
`MATCHER` key, so `kafka.expect` runs EQUALS and `AiStepNormalizer` rejects any other matcher on it. REST and
gRPC both carry the full five-matcher set over the `StepParameterKeys.MATCHER` wire key. Equality itself is
no longer duplicated: kafka's `MessageAssertions` and db's `DbValues` both delegate to core's
`AssertionMatchers.equalsMatch`, so all four adapters share one evaluator and cannot drift apart.

**There is no agent runtime in this repository, and building one is a closed question.** Four Gradle
modules (`stand-test-agent-{core,tools,llm,cli}`, ~31 500 lines) once implemented an orchestrator,
a state machine, a tool registry and an LLM client; they were removed because they rebuilt what the
subscription host (Claude Code / opencode) already provides and reached a model through a separate
API key. **Do not reintroduce them.** The agent is the host; what this repository ships for it is the
kit under `docs/ai-agent/`, and its roadmap is
[`docs/plans/ai-agent-kit-implementation.md`](docs/plans/ai-agent-kit-implementation.md) — read that
before picking up work on the kit.

Three artefacts of that effort survive because they were written before it and do not depend on it:
`docs/agent-analysis/current-state-analysis.md` (findings A-01…A-17 about the kit, the SDK and this
repository), `docs/agent-evaluation/dataset/` (15 cases with invariants — the only way to answer "did
the kit get better after a prompt edit", measurable by a person in the host), and six ADRs under
`docs/agent-architecture/adr/` (0004, 0006, 0007, 0012, 0013, 0014). The ADRs about the runtime —
0001, 0002, 0003, 0005, 0008 — are gone with it, as are 0009, 0010 and 0011, whose motivation was
real but whose remedy was Java.

Publishing is fully wired but endpoint-less: the repository URL/credentials arrive via
`standTestPublish*` Gradle properties or `STAND_TEST_PUBLISH_*` env vars (snapshot/release repo chosen by
the version suffix; `publish` without a URL fails loudly; see `docs/publishing.md`) — only the actual
internal Nexus/Artifactory coordinates and a first real publish run remain. Remediation from the 2026-07
full-library review is **complete** (the critical, all 9 majors and all deferred minors are fixed and
pinned by tests — see the memory note `full-library-review-2026-07` for the item-by-item record). The
standing rules still apply: do not start work that destabilises a module's dependencies, and do not pull
adapter/IO, Spring, Allure, YAML or business logic into `stand-test-core`.

## Project rules

`.claude/rules/` defines standards: `common/` (language-agnostic) plus `java/` and `kotlin/`
(language-specific override common). Highlights already encoded above: AssertJ over JUnit assertions,
immutability/defensive copies, records for value types, and the 80% coverage target — enforced as a
JaCoCo INSTRUCTION gate wired into `check` by the root `subprojects` block.
`.claude/skills/`, `.claude/commands/` and `.claude/agents/` provide deeper task-specific tooling.

Do not confuse this root harness with the **shippable bundle** under `docs/ai-agent/` — that one
exists twice (`.claude/` and `.opencode/`, same assets) and is copied into CONSUMER projects, not
used to work on the SDK itself. See [`docs/ai-agent/README.md`](docs/ai-agent/README.md).
