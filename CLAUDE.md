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

- `await`, `junit`, `rest`, `kafka`, `db`, `grpc`, `ui` → `core` (and the adapters + junit also → `await`)
- `allure` → `core`; `scenario-yaml` → **core only** (adapters resolved via SPI at runtime, no compile edges); `config` → **core only** (+ SnakeYAML; ships the `FileEnvironmentRegistry` SPI provider that loads `stand-test-environments.yml`)
- `spring-boot-starter` → the runtime modules it wires as `compileOnly` optionals (never the reverse); `bom` is the `java-platform` outside the compile graph — it constrains every published module plus the curated third-party versions (only external consumers import it)
- **Adapter modules must not depend on each other.** Each module's `build.gradle.kts` keeps its
  `Planned internal dependencies` as commented stubs that must match this target graph.
- `ModuleDependencyArchTest` (in `stand-test-example`, the only module with the whole graph on one
  classpath) now also pins the **external** edges, which nothing checked before: `coreHasNoUiOrIoDependencies`
  (core may see only the JDK + `slf4j-api` — an explicit allow-list), `scenarioHasNoUiFields` (the field
  list of `Scenario` is fixed), `playwrightIsConfinedToDriverPackage` plus its non-vacuity guard, and
  `nothingDependsOnUi`. Each was verified to fail on a deliberate violation before being committed.

### Core contracts to respect (plan §8)

- **`ScenarioContext` is immutable metadata only** (ids, environment, tags, createdAt). Runtime
  variables live in a separate **mutable `VariableStore`**, one per run, owned by the runner — never
  static/global/`ThreadLocal`. This is what makes parallel runs isolated.
- **Failure semantics:** assertion failures are raised as `StandTestAssertionError` (extends
  `AssertionError`, so JUnit/Allure treat them as a failed test); infrastructure/config problems as
  `StandTestException` (extends `RuntimeException`). A `StepStatus.FAILED` is a reporting record and
  must never silently substitute for a thrown failure. On a **thrown** failure the runner builds the
  step's diagnostics from the cause alone and adds only `exception.class` — anything richer is opt-in
  through the core marker **`FailureAttachments`**, whose `failureDiagnostics()`/`failureAttachments()`
  the runner folds into the failing `StepEvent`. Two adopters: the UI adapter (screenshot, masked-zone
  count) and **`DiagnosticAssertionError`** — a `StandTestAssertionError` carrying a diagnostics map,
  which is what every adapter's **await timeout** throws. So a timed-out `expectEventually` reaches a
  report twice over: `TimeoutDiagnostics.summary()` in the message for whoever reads a stack trace, and
  `toMap()` in the diagnostics for whoever reads the report, plus adapter keys added through
  `withAttribute` (`rest.service`, `kafka.messagesSeen`, `db.sql`, `ui.application`, …). That map is
  rendered verbatim into the report, so it takes metadata only — an alias, a count, a bounded query —
  never a response body or a message payload. A wrapper that re-throws such a failure must republish
  what its cause carried (`UiFailureDiagnostics.merge`), or the diagnostics die at the last hop.
  `DiagnosticAssertionError`'s causeless constructor is deliberately `super(message)` and never
  `super(message, null)`: the two-argument form of `Throwable` fixes the cause at null and the JDK then
  refuses `initCause`, which is exactly how `AwaitResult.orElseThrow` attaches the probe's last error.
- **`correlationId` is SDK-owned** and injected outbound (REST header / Kafka key / gRPC metadata);
  capturing it from a response is a fallback only.
- **`ForbiddenOperation` is the single source of truth** for guardrails, and since the removal of
  `stand-test-ai-schema` (2026-08-12, a deliberate call by the line owner) the runtime
  `DefaultScenarioValidator` is the *only* thing deriving from it. The pre-flight JSON Schema pass and
  the cross-check test that pinned its rules table to the enum are gone with the module, so a
  declarative document now meets the guardrails at parse time (`AiScenarioParser`, fail-closed) and at
  validation time — never before it is loaded. The value-level guardrails the schema used to state
  first (secret headers, SQL sleep functions, timeout bounds) survive because the runtime validator
  enforces them itself. **`EnvironmentRegistry`** resolves logical
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
- **The registry configuration is versioned** by `EnvironmentConfigFormat` in `core/environment/` — one
  constant (`SUPPORTED_VERSION`) read by *both* front-ends, which is the anti-drift device for the two
  hand-maintained mappers. A document declaring no `version` (file root) / `stand.test.version` (starter)
  is format version 1, so every pre-existing configuration loads unchanged; a newer version is refused
  with a message naming both versions and the action, instead of the fail-closed loader's bare
  `Unknown field`. A section added after version 1 must declare the version it arrived in — that rule is
  what makes the promise real rather than nominal. **`ui-applications` (version 2)** whitelists UI
  application aliases (`base-url-ref`, `default-viewport`/`viewport-profiles`, `trace`, `auth` with the
  service spelling `scheme`); `Scenario` gets no browser fields — viewport and the rest are configuration.
  The `ui.*` steps themselves ship in **`stand-test-ui`**: `open`/`click`/`fill`/`expect`/
  `expectEventually` plus `login`. A failing step leaves artefacts — a screenshot with the sensitive zones
  painted over *before* the grab, the console and the network story as text, and, where the registry declares
  `trace: on-failure`, a Playwright trace — all under the retention of
  `stand.test.ui.artifacts.retention.days` (7 by default). The trace has one non-obvious rule that is
  load-bearing rather than editorial: a trace records the **parameters of the actions it saw**, and a
  `fill`'s parameter is the typed value, so `ui.login` brackets its credential fills with
  `suspendTracing()`/`resumeTracing()` — no recording chunk is open while a password is typed. Neither
  `asSensitive()` (a screenshot mask) nor `UiSecrets` (an exception-message sanitiser) nor the report's
  masker (text channel only; a ZIP is the file channel) reaches that, which is why the bracket exists.
  The pre-flight guardrail `NON_WHITELISTED_UI_APPLICATION` refuses a non-whitelisted alias before a
  browser is ever started, and `UI_LOGIN_ROLE_REQUIRED`/`UI_LOGIN_ROLE_UNKNOWN` refuse a sign-in naming no
  role (or an undeclared one) on an application that declares them — plain validator codes, not
  `ForbiddenOperation` constants, because they are scenario/registry mismatches rather than forbidden acts.
- **Sign-in is `ui.login`, a step of its own** (ADR-UI-006, implemented): a technical account is leased
  **by role** from a per-JVM `AccountPool` whose roster lives behind `credentials-pool-ref` — a variable
  holding account ids, roles and the *names* of the credential variables. An application with exactly one
  account may instead name it directly with `credentials-username`/`credentials-password` (registry format
  **version 4**, mutually exclusive with the roster, answers every declared role, one `accountId` per role).
  **From registry format version 5 that pair is a VALUE TWIN** — the bare key holds the value, and
  `credentials-username-ref`/`credentials-password-ref` carry the reference, exactly as `base-url` and its
  `-ref` twin do. That flip exists because on the Spring starter a `${VAR:default}` placeholder is resolved
  before the SDK sees the field, so a value and a variable name arrive as the same string and cannot share
  one key; under version 4 the same spelling worked on the FILE front-end and failed on the starter with
  `variable 'tks_Admin' is not set`. A version-4 document keeps the old meaning forever, and a version-5
  document whose value field looks like a bare env-var NAME is refused pointing at `*-ref` — the flip's one
  silent failure, closed by refusing rather than guessing. The cost of the twin is the usual one: a value
  routed through Spring lives in the Environment (actuator `/env`, dumps) and a default written into the
  file stays in git after rotation, so `*-ref` remains the right spelling for a password — and the kit's
  gate calls a password value in a registry document a blocking finding. Both are deliberate relaxations of
  ADR-UI-006 §5 accepted by the line owner (version 4 made a credential *expressible*, version 5 made it
  expressible on the starter too), and "never give a default to a password" is a documented rule rather
  than a property of the construction. The lease is registered in
  the `ResourceScope`, so the runner's `finally`
  returns it on every outcome; waiting for a free account is bounded (`accountTimeout`, default 60 s,
  capped by `MAX_TIMEOUT_MILLIS`) and exhaustion is a `StandTestException` naming application, role, pool
  size and timeout. `FORM` fills the form every time; `STORAGE_STATE` restores a session saved per
  **account** at `<artifacts.dir>/storage-state/<environment>/<application>/<accountId>.json`, checks it
  against the registry's `signed-in-locator` and falls back to the form (re-saving) when it has expired.
  `StorageStateStore` can only check a file's *shape* (the module has no JSON parser, and the one library
  that could is confined to the driver package), so the browser has the last word: a state it refuses at
  context creation is deleted and the run signs in without it — otherwise one half-written file would break
  that account's every later run identically. The state file is effectively a secret: never attached, never
  logged, never printed. **There is deliberately no
  MFA/OTP/CAPTCHA bypass** — external gate G-1: an application declares `challenge`, and the SDK either
  finds a `UiLoginChallengeHandler` on the classpath or refuses with a message naming the gate and
  `STORAGE_STATE` as the alternative. `SSO` is a registry spelling with a speaking "not implemented".
- **The UI suite runs classes concurrently** (`stand-test-ui/src/test/resources/junit-platform.properties`,
  the same model `stand-test-example` uses), with `maxParallelForks = 1` on both `test` and `browserTest`
  because the account pool is in-process — a second JVM would hand the same account to a second run.
  `browserTest` overrides the parallelism downwards (a thread costs a Chromium); raise it with
  `-Pstand.test.ui.browser.parallelism=N`. The ceiling of a UI suite's parallelism is the size of the
  account pool: above it runs queue, they do not fail. `UiAccountPools` is instantiable for exactly this
  reason — a JVM-wide singleton with a `reset()` would make the suite that proves parallel isolation the
  one most likely to break it, so production takes `shared()` and each test takes its own.
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
reflection + `DynamicMessage`; all five assertion matchers, at parity with REST), **`stand-test-ui`** (the
wave-1 UI slice: five `ui.*` step types on Playwright, application by registry alias only, one
`BrowserContext` per run in the `ResourceScope`, browser-backed tests behind a separate `browserTest`
task — see `stand-test-ui/README.md` and `docs/ui-test-generation/`), **`stand-test-allure`** (with sink-side secret masking of attachment
bodies), **`stand-test-scenario-yaml`** (two surfaces: given/then YAML and the AI steps/type format),
**`stand-test-spring-boot-starter`** (Boot-3 auto-configuration, adapters as
`compileOnly` optionals) and **`stand-test-config`** (file-based `EnvironmentRegistry` SPI provider).
**`stand-test-example`** is a test-only showcase (offline doubles, not published); **`stand-test-bom`**
is the `java-platform` carrying constraints for every published module. Known asymmetry: **Kafka and DB** are
the equals-only adapters — `KafkaAssertion` carries no matcher field and `KafkaStepParameters` has no
`MATCHER` key, so `kafka.expect` runs EQUALS and `AiStepNormalizer` rejects any other matcher on it; `db`
has no assertion type at all (`db.expectEventually` takes a single `expectValue`, and `DbValues` delegates
straight to `equalsMatch`), so it is equals-only by construction rather than by a missing key. REST and
gRPC both carry the full five-matcher set over the `StepParameterKeys.MATCHER` wire key — `grpc.unary` is at
parity with REST, and any document calling it equals-only is stale. Equality itself is
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

**`stand-test-ai-schema` was removed on 2026-08-12**, deliberately and with the cost accepted by the
line owner. It shipped the declarative format's JSON Schema, the generation-rules catalogue and a
60-line resource loader; its `src/test` had grown into the CI home of everything else — kit bundle
parity (`.claude/` ⇄ `.opencode/`), the `ForbiddenOperation` cross-check, the KB and evaluation-corpus
schemas, the capability censuses, `.gitlab-ci.yml` and the count-bearing docs. All 49 test classes went
with it. Two consequences to keep in mind rather than rediscover: the AI/declarative document has **no
pre-flight gate** any more (`AiScenarioParser` fail-closed + `DefaultScenarioValidator` are the whole
net, both after loading), and **nothing machine-checks the kit against this repository** — a kit asset
claiming an SDK capability that changed will now simply be wrong and stay green. The kit assets that
referenced the jar resources (`stand-test-yaml-authoring`, `/stand-test-yaml`, `/stand-test-validate`,
the `test-plan`/`failure-analysis` schemas used by `stand-test-scenario-design` and
`stand-test-debugging`) point at something that no longer exists and are pending a decision.

The kit now has **two branches**: the protocol one (REST/Kafka/DB/gRPC) and a **UI branch** — 9
skills + 5 commands + `rules/stand-test-ui-guardrails.md`, wave-1 backlog items S-5.1/S-5.2. Its
premise is the asymmetry that a REST contract can be read from a specification and a `data-testid`
cannot, so it carries a *discovery* stage against the live DEV/IFT UI (registry alias only, restricted
`discovery-account-ref`, no irreversible action) and a source order of KB → discovery → question.
Locators live in Page Objects, `ui.*` is Java-only (no declarative surface), and every generation ends
in the eight-section BR-07 report plus a preserved snapshot of the original generation — the diff base
without which KPI-4 is unobservable. `UITG-S020` (backlog S-5.4) shipped the UI half of the safety
gate, and `UITG-S021`/`F006` finished it: `detectors.json` now carries **eight** UI-specific detectors
— `UI_LOCATOR_OUTSIDE_PAGES`, `UI_LOGIN_WITHOUT_ROLE`, `XPATH_LOCATOR`, `UI_OPEN_OR_ASSERT_TEMPLATE`,
`EXPECT_EVENTUALLY_WITHOUT_WITHIN`, `UI_REPORT_STAND_ADDRESS`, plus `UI_DISCOVERY_PARITY` (U1 — a
locator with no row in the discovery report, checked against it by `scan --discovery`) and
`UI_GENERATION_REPORT_INCOMPLETE` (U16 — the eight headings counted by NUMBER, and `original.sha256`
must exist on disk) — for 26 findings in all, 18 protocol plus these eight. `THREAD_SLEEP` is
extended to driver-level waits, and U20 needs no detector of its own: the protocol-wide
`SHARED_MUTABLE_TEST_STATE` reads a static mutable field in a Page Object exactly as in a test class.
The gates that stay eye-only are U4 (semantic half), U8, U10, U11a/b, U12, U14, U15, U18, U19 —
enumerated in `ui-safety-checklist.md` and pinned in BOTH directions by `UiHumanGateCensusTest` /
`UiMachineGateCensusTest`, so a gate cannot be counted as machine-covered and eye-only at once. Every
asset still says that a clean hook run is not a clean UI review; UI entries in the knowledge base
remain S-5.3.

Three artefacts of that effort survive because they were written before it and do not depend on it:
`docs/agent-analysis/current-state-analysis.md` (findings A-01…A-17 about the kit, the SDK and this
repository), `docs/agent-evaluation/dataset/` (27 cases with invariants — 15 protocol plus 12 UI, the only way to
answer "did the kit get better after a prompt edit", measurable by a person in the host; the UI half
carries seeded discovery reports and `execution.outcome: NOT_RUN`, because no browser-side double
ships with the corpus — see `docs/agent-evaluation/ui-wave-1-readiness.md`), and six ADRs under
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
