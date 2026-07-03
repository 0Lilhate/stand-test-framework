
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
is a **thin facade** over mature tools (RestAssured/OkHttp, `kafka-clients`, JDBC, gRPC, JUnit 5,
Allure) that gives one consistent way to write integration/e2e tests against **real DEV/IFT stands**
(Testcontainers is explicitly *not* the basis). The SDK standardizes `scenarioId`/`testRunId`/
`correlationId`, a single await mechanism (no `Thread.sleep`), unified reporting, and a constrained
declarative format safe for AI-generated tests.

**`docs/arch/stand-test-sdk-implementation-plan.md` is the source of truth.** Read it before
implementing anything — it defines the module graph, the core contracts (§8 "Итерация 0"), the MVP
scope (§6), and the strict implementation order (§7). Each module also has a `README.md`.

## Build & test commands

```bash
./gradlew build                       # full build: compile + checkstyle + tests, all modules
./gradlew :stand-test-core:build      # build one module
./gradlew :stand-test-core:test       # run a module's tests
./gradlew :stand-test-core:checkstyleMain :stand-test-core:checkstyleTest   # lint only (main + test)
./gradlew publishToMavenLocal         # publish modules locally (no remote repo configured yet)

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
- **Java toolchain is 24** (non-LTS), but the build compiles with **`--release 17`** (catalog
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
  adapter/IO library (JDK-only). It owns the generic model (`Scenario`/`ScenarioStep`), the SPI
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
  and is the whitelist enforcement point.
- Value types are immutable `record`s with defensive copies (`List`/`Set`/`Map.copyOf`).

## Current state & where to work

**All modules are implemented** (the plan's iterations 0–10 plus the follow-on modules):
**`stand-test-core`** (models, value objects, contracts, SPI, the `core.validation` SQL
classifier/`SqlSpanScanner`, the pre-flight guardrail validator and the `core.event` reporting events),
**`stand-test-await`**, **`stand-test-junit`**, **`stand-test-rest`**, **`stand-test-kafka`**,
**`stand-test-db`** (design record in `docs/arch/stand-test-db-decisions.md`, hardening in
`docs/arch/stand-test-db-remediation-plan.md`), **`stand-test-grpc`** (unary via server reflection +
`DynamicMessage`), **`stand-test-allure`**, **`stand-test-scenario-yaml`** (two surfaces: given/then
YAML and the AI steps/type format), **`stand-test-ai-schema`** (JSON Schema + generation rules),
**`stand-test-spring-boot-starter`** (Boot-3 auto-configuration, adapters as `compileOnly` optionals)
and **`stand-test-config`** (file-based `EnvironmentRegistry` SPI provider). **`stand-test-example`**
is a test-only showcase (offline doubles, not published); **`stand-test-bom`** is the `java-platform`
carrying constraints for every published module.

There is no remote publishing repository yet (`publishToMavenLocal` works; the internal
Nexus/Artifactory URL is deferred). Remediation from the 2026-07 full-library review is **complete**
(the critical, all 9 majors and all deferred minors are fixed and pinned by tests — see the memory note
`full-library-review-2026-07` for the item-by-item record). Current work is publishing setup. The
standing rules still apply: do not start work that destabilises a module's dependencies, and do not pull
adapter/IO, Spring, Allure, YAML or business logic into `stand-test-core`.

## Project rules

`.claude/rules/` defines standards: `common/` (language-agnostic) plus `java/` and `kotlin/`
(language-specific override common). Highlights already encoded above: AssertJ over JUnit assertions,
immutability/defensive copies, records for value types, and the 80% coverage target — enforced as a
JaCoCo INSTRUCTION gate wired into `check` by the root `subprojects` block.
`.claude/skills/`, `.claude/commands/` and `.claude/agents/` provide deeper task-specific tooling.
