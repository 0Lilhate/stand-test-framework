# stand-test-await

**Group:** core · **Gradle plugin:** `java-library`

The single await mechanism of the SDK — the project-wide replacement for `Thread.sleep` (plan
§2.4/§2.5). Instead of fixed pauses, callers wait on an `Awaiter` that polls a supplied probe until a
predicate holds or a configurable timeout elapses, then reports rich diagnostics on timeout.

**Internal dependencies:** `stand-test-core` (no third-party polling engine — Awaitility is not pulled
in, so consumers never inherit/conflict with a transitive version).

## Design

- **Transport-agnostic.** The awaiter never performs IO and knows nothing about REST/Kafka/DB/gRPC —
  it only evaluates the `Supplier`/`Predicate` it is given (plan §4). The probe runs on the **calling
  thread**, preserving thread-confinement of stand resources (JDBC connections, Kafka consumers).
- **No throw on timeout.** `await(...)` returns an `AwaitResult` the caller inspects, so the primitive
  stays neutral about whether a missed effect is an assertion failure or an infrastructure problem —
  the adapter assigns that meaning via `AwaitResult.orElseThrow(...)`.
- **Deterministic time.** All reading and waiting goes through the injectable `TimeSource`, so timeout
  behaviour is unit-tested exactly, without real sleeping (see `FakeTimeSource` in tests).

## API surface

| Type | Role |
|------|------|
| `Awaiter` | SPI: `await(policy, probe, condition)` + `awaitCondition(policy, booleanSupplier)`. |
| `DefaultAwaiter` | Minimal polling loop; constructed with a `TimeSource` (defaults to the system one). |
| `AwaitPolicy` | Immutable timing/behaviour: `description`, `timeout`, `pollInterval`, `pollDelay`, `ignoreExceptions`. |
| `AwaitResult<T>` | Outcome: `satisfied`, `value`, `attempts`, `elapsed`, `lastError`, `timeoutDiagnostics`. |
| `TimeoutDiagnostics` | Why it timed out: description, timeout, interval, attempts, elapsed, last value/error, free-form `attributes` (scenarioId/testRunId/correlationId/probe details). `toMap()`/`summary()` for reporting. |
| `TimeSource` | Monotonic reading + sleep seam; `TimeSource.system()` in production. |

## Usage sketch

```java
Awaiter awaiter = Awaiter.create();

AwaitPolicy policy = AwaitPolicy.builder("db.expectEventually request.status")
        .timeoutSeconds(20)
        .pollInterval(Duration.ofMillis(200))
        .build();

String status = awaiter
        .await(policy, () -> readStatusFromDb(requestId), "SUCCESS"::equals)
        .orElseThrow(diagnostics -> new StandTestAssertionError(diagnostics.summary()));
```

## Not here

No transport logic, no reporting/Allure wiring, no scenario execution — the awaiter is a primitive the
adapters and runner build on. Diagnostics are surfaced via `TimeoutDiagnostics` (its `toMap()` feeds a
`StepEvent`/`StepResult` diagnostics map); the awaiter does not publish reporting events itself.
