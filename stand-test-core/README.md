# stand-test-core

**Group:** core · **Gradle plugin:** `java-library` · Root of the module graph.

`stand-test-core` is the foundation of the stand-test SDK. It contains **only** immutable models,
value objects, contracts and SPI — the canonical `Scenario Model` that both the Java DSL and the
(future) YAML DSL converge to. It is the single sink of the dependency graph: it depends on **no
sibling module** and on **no adapter / IO library**. The one sanctioned external dependency is the
logging facade `slf4j-api` (plan §17) — a pure facade with no binding and no IO, so "core performs no
IO" still holds; the consumer supplies the SLF4J binding.

> ⚠️ **No REST / Kafka / DB / gRPC / JUnit / Allure / YAML / Spring logic lives here.** This module
> performs no IO. Transports and reporting are implemented by the adapter modules, which depend on
> core through its SPI — never the other way around.

## What is in core

| Area | Types |
|------|-------|
| Identifiers (`identifier`) | `ScenarioId`, `TestRunId`, `CorrelationId` (immutable, value-based; `generate()` for run/correlation) |
| Scenario model (`scenario`) | `Scenario` (+ lazy `Scenario.Builder`), `ScenarioStep` contract, generic `GenericStep` |
| Context (`context`) | `ScenarioContext` — immutable run metadata (ids, environment, tags, createdAt) |
| Variables (`variable`) | `VariableStore` (per-run mutable store), `VariableResolver` (`${name}` substitution, no expression language) |
| Validation (`validation`) | `ScenarioValidator` + `DefaultScenarioValidator`, `ValidationResult`, `ValidationIssue`, `ValidationSeverity`, `ForbiddenOperation` (single source of truth) |
| Environment (`environment`) | `EnvironmentRegistry` (+ `InMemoryEnvironmentRegistry`), `EnvironmentDefinition`, `ServiceEndpointDefinition`, `TopicDefinition`, `DatasourceDefinition`, `GrpcTargetDefinition`, `CorrelationConfig`/`CorrelationSource` — logical aliases & secret **references** only |
| Execution SPI (`execution`) | `ScenarioRunner`, `StepExecutor`, `StepExecutionContext` (contracts only) |
| Result (`result`) | `StepStatus`, `StepResult`, `ScenarioResult` (immutable, defensive copies) |
| Events (`event`) | `StepEvent`, `ScenarioEvent`, `StepPhase`/`ScenarioPhase`, `ReportingEventPublisher`, `NoOpReportingEventPublisher` |
| Exceptions (`exception`) | `StandTestException` (infra/config), `StandTestAssertionError` (extends `AssertionError`) |
| Facade | `StandClient` — future-facing `run(Scenario)` contract |

> **Note — `environment` is a raw `String` (accepted deviation).** Plan §4/§21 list `Environment`
> among the core value objects, but in this MVP iteration the logical environment name is modeled as a
> plain `String` (in `ScenarioContext`, `Scenario`, `EnvironmentRegistry` and `EnvironmentDefinition.name`)
> rather than a dedicated value record. It is still validated where it carries meaning — `ScenarioContext`
> rejects a blank environment and `DefaultScenarioValidator` raises `ENVIRONMENT_REQUIRED` — while
> `Scenario` stays intentionally permissive (the validator, not the builder, flags a missing environment).
> Introducing an `Environment`/`EnvironmentName` value object is deferred as a future, potentially
> breaking change. See `docs/stand-test-core-remediation-plan.md` (C-2).

## What is NOT in core

- No REST / Kafka / DB / gRPC clients or `StepExecutor` implementations (those live in the adapters).
- No JUnit extension, no Allure listener, no YAML parser, no Spring auto-configuration.
- No real stand URLs, secrets, fixtures or business scenarios.
- No `Thread.sleep` and no IO of any kind.

## Why core does not depend on adapters

Both DSL inputs build the **same generic `Scenario Model`**. The runner dispatches each step by
`ScenarioStep.type()` to a `StepExecutor` resolved through the SPI at runtime. Because core owns only
the generic model + SPI (typed steps and executors live in the adapter modules), core needs no
compile-time edge to any adapter, and the dependency graph stays acyclic with core as the sole sink.
See `docs/arch/stand-test-sdk-implementation-plan.md` (§3, §5, §8.5).

## Failure semantics

SDK assertion failures are raised as `StandTestAssertionError` (a JUnit-compatible `AssertionError`);
infrastructure/configuration problems as `StandTestException`. A `StepStatus.FAILED` is a reporting
record and never a silent substitute for a failed test.

## Building a `Scenario` (no IO)

```java
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

// The Java DSL is a lazy builder: it assembles an immutable model and executes nothing.
Scenario scenario = Scenario.builder(ScenarioId.of("example-flow"))
        .environment("ift")
        .step(GenericStep.of("post-request", "rest.post"))
        .step(GenericStep.of("await-event", "kafka.expect"))
        .tag("integration")
        .build();

// Validation is a separate, explicit step (no execution, no IO).
new DefaultScenarioValidator().validate(scenario).throwIfInvalid();
```

## Tests

Pure unit tests (JUnit 5 + AssertJ). They touch no external system, require no stand and use no
Testcontainers. Run them with:

```bash
./gradlew :stand-test-core:test
```
