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
| `TimeoutDiagnostics` | Why it timed out: description, timeout, interval, attempts, elapsed, last value/error, free-form `attributes`. `summary()` renders one line for an exception message; `toMap()` renders the structured form. |
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
adapters and runner build on. It publishes no reporting events itself: it returns `TimeoutDiagnostics`
and the caller decides what to do with them.

### How a timeout reaches the report

Both renderings are used, for two different readers. `summary()` goes into the thrown failure's
**message**, for whoever reads a stack trace. `toMap()` goes into the failure's **diagnostics**, for
whoever reads the report: every adapter's timeout throws a
`ru.alfa.stand.test.core.exception.DiagnosticAssertionError`, which implements the core marker
`FailureAttachments`, and `DefaultScenarioRunner` folds that map into the failing `StepEvent`. In Allure
the await then renders as key/value rows — `attempts=30`, `elapsed=PT30S`, `lastValue=PENDING` — instead
of one long sentence.

`withAttribute(...)` is how an adapter adds what the await engine cannot know, and each one does:
`rest.service`/`rest.path`, `kafka.topic`/`kafka.realTopic`/`kafka.messagesSeen`,
`db.datasource`/`db.expected`/`db.sql`, `ui.application`/`ui.locator`. Keys are namespaced by adapter so
they cannot collide with the engine's own (`await`, `timeout`, `pollInterval`, `attempts`, `elapsed`,
`lastValue`, `lastError`), and an attribute can never overwrite one — `toMap()` appends with
`putIfAbsent`.

Anything put here is rendered verbatim into a report, so it must be metadata: an alias, a count, a
bounded query. Never a response body, a message payload or anything a producer has not already redacted.
