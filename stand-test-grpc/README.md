# stand-test-grpc

**Group:** adapters · **Gradle plugin:** `java-library`

gRPC adapter for calling unary methods on a stand. It owns the typed `GrpcStep` model (step type
`grpc.unary`) and the gRPC `StepExecutor` (registered via the core SPI), and is the **single point of real
gRPC IO** to the stand (plan §4, Iteration — gRPC MVP).

**Internal dependencies:** `stand-test-core` (`api`), `stand-test-await` (`implementation`, reserved for
future polling/streaming waits — the MVP unary call uses the native gRPC deadline).

**External dependencies:** grpc-java (`grpc-api`/`grpc-stub`/`grpc-protobuf`) + `grpc-services` (the Server
Reflection client) + `protobuf-java`/`protobuf-java-util` (`JsonFormat`) + `json-path` (JSONPath over the
response JSON, like REST/Kafka). The transport (`grpc-netty-shaded`) is `runtimeOnly`, so it never reaches
a consumer's compile graph; the in-process transport is test-only. The SDK never ships its own gRPC stack
and generates no service contracts (plan §4/§20).

## What it does (MVP)

- **`grpc.unary`** — calls a single unary method on a logical target alias and, optionally, asserts and
  captures over the response. It substitutes `${...}` placeholders in the request and metadata; injects
  the SDK-owned `correlationId` into gRPC metadata; enforces a mandatory deadline; then runs **JSONPath
  assertions** and **captures** against the response into the run's `VariableStore`. Assertion comparison
  is type-aware (numbers match by value; other type changes fail rather than being string-coerced),
  identical to the REST/Kafka adapters.

### Calling without generated stubs (decision A)

Declarative scenarios carry no compiled client stubs and contract generation is out of scope, so the
executor resolves the method **descriptor over gRPC Server Reflection**, builds the request from JSON into
a protobuf `DynamicMessage` (`JsonFormat`), invokes the method generically (a protobuf-marshalled
`io.grpc.MethodDescriptor` + `ClientCalls.blockingUnaryCall` under `CallOptions.withDeadlineAfter`), and
renders the `DynamicMessage` response back to JSON. That keeps a single declarative contract and the same
JSONPath assertion model as REST/Kafka. If Server Reflection is disabled on the target, the fallback is a
consumer-supplied `FileDescriptorSet` (a later extension of the `GrpcCallInvoker` seam).

## API surface

| Type | Role |
|------|------|
| `GrpcStep` | Lazy builder. Static `unary(target)`; `method`/`request`/`requestFromResource`/`metadata`/`injectCorrelationId`/`deadline`/`withinSeconds`/`assertPath`/`capture`/`id`; `build()` → core `ScenarioStep`. Performs **no IO**. |
| `GrpcStepExecutor` | The `StepExecutor` SPI implementation (`supports("grpc.*")`). Stateless / thread-safe; discovered via `ServiceLoader`. Implements `prepare` (channel creation) + `execute` (unary). The single point of real gRPC IO. |
| `GrpcStepParameters` | The shared parameter-map schema (key names + readers) — the one contract `GrpcStep` writes and `GrpcStepExecutor` reads, so a future YAML/AI front-end can target the same map. |
| `GrpcChannelFactory` / `DefaultGrpcChannelFactory` | The channel seam and its default (`ManagedChannelBuilder`, plaintext). |
| `GrpcCallInvoker` / `DefaultGrpcCallInvoker` | The unary-call seam and its default (Server Reflection + `DynamicMessage`). |
| `ReferenceResolver` / `EnvironmentReferenceResolver` | Resolves a `GrpcTargetDefinition.targetRef` (treated strictly as an environment-variable name) to a `host:port`. Tests inject their own resolver rather than embedding addresses in the registry. |
| `GrpcOperation` / `GrpcAssertion` / `GrpcCapture` / `ResolvedGrpcTarget` / `GrpcMethodName` | Immutable value objects. |

## Usage sketch

Each `GrpcStep.build()` result is a core `ScenarioStep` passed to `Scenario.Builder.step(...)`:

```java
var scenario = Scenario.builder("charge-flow")
        .environment("ift")
        .step(GrpcStep.unary("billing-grpc")                 // logical target alias, never host:port
                .method("billing.BillingService/Charge")     // package.Service/Method
                .requestFromResource("fixtures/charge.json") // JSON -> DynamicMessage
                .metadata("x-tenant", "${tenantId}")         // ${...} substituted; secrets rejected inline
                .injectCorrelationId()                       // SDK-owned correlationId -> gRPC metadata
                .withinSeconds(5)                            // mandatory deadline (no unbounded calls)
                .assertPath("$.status", "OK")
                .capture("chargeId", "$.chargeId")           // for later steps
                .build())
        .build();

stand.run(scenario);   // Validator → Runner (prepare → execute) → StepExecutor SPI → GrpcStepExecutor
```

Adding a `testImplementation` dependency on this module makes `grpc.*` step types runnable: the runner
discovers `GrpcStepExecutor` via `ServiceLoader`, so no wiring code is needed in the test.

## Target alias resolution & security (plan §7/§9/§16)

The target is a **logical alias** resolved through the core `EnvironmentRegistry`
(`environment(name) → grpcTarget(alias)`, a fail-closed whitelist) — never a `host:port` in the scenario.
`GrpcTargetDefinition.targetRef` is a **reference** (an environment-variable name) resolved at run time,
so addresses stay out of source. Secrets are never written inline: the builder rejects secret-bearing
metadata keys (`authorization`/`token`/`password`/`secret`/`cookie`/`api-key`), and any such value is
masked in diagnostics. The deadline is mandatory (no unbounded calls).

## correlationId (plan §8)

`correlationId` is **SDK-owned**. `injectCorrelationId()` puts `ScenarioContext.correlationId` into the
gRPC metadata under the carrier name configured for the target
(`GrpcTargetDefinition.correlation`, which must be `CorrelationSource.METADATA`). A request to inject
without a METADATA carrier is a configuration error rather than a silent no-op. The correlation id is
never captured from the response.

## Variables, assertions & capture

`${name}` placeholders in the request and metadata are resolved from the run's `VariableStore`
(built-ins: `${scenarioId}`/`${testRunId}`/`${correlationId}`/`${environment}`). Assertions
(`assertPath`) and captures (`capture`) operate on the response rendered as JSON, using JSONPath. A missing
path, a non-JSON/empty response, or a mismatch is a `StandTestAssertionError`.

## Diagnostics & reporting

`execute` returns a `StepResult` carrying diagnostics (`grpc.target`, `grpc.method`, `grpc.deadlineMillis`,
`grpc.correlationId`, `grpc.metadata` with secret values masked, `grpc.elapsedMillis`) and **attachments**
(`grpc-request` / `grpc-response` JSON). Per the SDK's design the adapter does **not** publish reporting
events itself — the `DefaultScenarioRunner` emits the `StepEvent`s and threads the diagnostics/attachments
into them, so this module has **no dependency on Allure** (or JUnit/Spring).

## Failure semantics (plan §8.3)

- **Assertion failures** (JSONPath mismatch, missing path, non-JSON/empty response, null captured value)
  → `StandTestAssertionError` (a JUnit-native failure).
- **Infrastructure / configuration problems** (alias/environment not whitelisted, unresolved `targetRef`,
  correlation carrier other than METADATA, descriptor/reflection failure, malformed request JSON,
  unresolved `${...}`) and **gRPC status failures** (`DEADLINE_EXCEEDED`, `UNAVAILABLE`, …, with the
  status code preserved) → `StandTestException`. `StatusRuntimeException` is never swallowed.

## Why no Spring Boot / JUnit / Allure

This module is a pure adapter: it produces core `ScenarioStep`s and implements the core `StepExecutor`
SPI. Reporting flows through the core `StepResult`/`StepEvent` model, so Allure stays in the reporting
adapter; JUnit wiring and Spring auto-configuration live in their own modules. Adapters do not depend on
one another (plan §5), so `grpc` depends only on `core` (+ `await`) and the gRPC/protobuf libraries.

## Testing

Unit tests drive `GrpcStepExecutor` through a **fake `GrpcCallInvoker`** and **fake `GrpcChannelFactory`**
(no server): unknown alias/environment, happy path (assert + capture + diagnostics + attachments),
correlation-metadata injection, deadline propagation, custom-metadata `${}` substitution and masking,
assertion failure, gRPC status-failure mapping, and channel reuse across steps. `DefaultGrpcCallInvoker`
is covered end-to-end against an **in-process gRPC server** exposing the bundled Health service plus Server
Reflection, so the reflection → `DynamicMessage` → JSON round trip runs offline (happy path, unknown
method, unknown service). A `ServiceLoader` test asserts the `META-INF/services` registration. Instruction
coverage is ≥ 80% (JaCoCo). No `Thread.sleep`, no Testcontainers, no real stand.

## Not here / future work

No streaming (client/server/bidi); no stub generation; no TLS / channel credentials (plaintext MVP for
internal DEV/IFT); no `FileDescriptorSet`-from-config reflection fallback yet (the `GrpcCallInvoker` seam
is ready for it); the runtime executes `equals` matchers only (the schema's richer matchers are a later
sub-iteration). After this module, the tracked follow-ups are removing the `AiStepNormalizer` fail-closed
on `grpc.unary`, adding grpc to the `ai-schema` parity set (F8), and a `stand-test-example` scenario.
