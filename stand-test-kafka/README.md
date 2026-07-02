# stand-test-kafka

**Group:** adapters · **Gradle plugin:** `java-library`

Kafka adapter for producing to / consuming from topics on a stand. It owns the typed `KafkaStep` model
(step types `kafka.send` / `kafka.expect`) and the Kafka `StepExecutor` (registered via the core SPI),
and is the **single point of real Kafka IO** to the stand (plan §4, Iteration 5 / MVP).

**Internal dependencies:** `stand-test-core` (`api`), `stand-test-await` (`implementation`).

**External dependencies:** raw Apache `kafka-clients` (for `assign`/`seek` control — the SDK never ships
its own Kafka client, plan §4/§20) plus `json-path` (JSONPath; the message value is read as a string, so
a separate JSON binding / Jackson is not required, mirroring `stand-test-rest`).

## What it does (MVP)

- **`kafka.send`** — publishes a JSON message to a topic alias. Substitutes `${...}` placeholders in the
  key, headers and value; optionally injects the SDK-owned `correlationId` outbound via the topic's
  HEADER carrier. The producer is created and closed inside `execute` (try-with-resources, `flush`
  before close); the value/key are serialized as UTF-8 strings, header values as UTF-8 bytes.
- **`kafka.expect`** — waits (through `stand-test-await`, **never** `Thread.sleep`) for a message
  selected by the SDK-owned `correlationId` (HEADER carrier) and, optionally, a `key` discriminator,
  then runs **JSONPath assertions** and **captures** against the matched value into the run's
  `VariableStore`. Assertion comparison is type-aware (numbers match by value; other type changes fail
  rather than being string-coerced), identical to the REST adapter.

### Seek-race resolution & offset strategy (plan §8.7)

The runner's `prepare` phase arms **one consumer per topic alias per run** *before any step runs*:
unique `group.id` (includes the `testRunId`), `assign(partitionsFor(topic))` → `seekToEnd` →
`position(...)` (start-from-now, forcing the lazy seek to resolve). Because positioning happens before
the triggering step (a `rest.post` / `kafka.send`) injects the correlation id outbound, the expect
consumer is already listening — this removes **`KAFKA-SEEK-RACE`**. The armed consumer lives in the
run-scoped `ResourceScope` (a core addition for this iteration) and is closed by the runner.

The consumer is **shared and advanced** across all expects on the same topic (consume-and-advance):
every polled record is buffered, and each expect selects the first *not-yet-selected* buffered record
that passes selection. Because selection tracks the buffer (not the raw consumer position), a later
expect can still pick a lower-offset message that a `key` discriminator skipped earlier — so
out-of-order, same-`correlationId` streams disambiguate correctly. On timeout the
`StandTestAssertionError` carries diagnostics: real topic, partitions, messages seen, the selection
criteria, and a bounded sample of the last-seen messages (partition@offset, key, correlation header) so
a failed expect explains what arrived and why none matched (plan §4).

## API surface

| Type | Role |
|------|------|
| `KafkaStep` | Lazy builder. Static `send`/`expect`; `body`/`bodyFromResource`/`key`/`header`/`injectCorrelationId` (send) and `correlationIdFromContext`/`withinSeconds`/`within`/`pollTimeout`/`key`/`assertPath`/`capture` (expect); `build()` → core `ScenarioStep`. Performs **no IO**. |
| `KafkaStepExecutor` | The `StepExecutor` SPI implementation (`supports("kafka.*")`). Stateless / thread-safe; discovered via `ServiceLoader`. Implements `prepare` (arming) + `execute` (send / expect). The single point of real Kafka IO. |
| `KafkaStepParameters` | The shared parameter-map schema (key names + readers) — the one contract `KafkaStep` writes and `KafkaStepExecutor` reads, so a future YAML front-end can target the same map. |
| `KafkaClientFactory` / `DefaultKafkaClientFactory` | The client seam and its default `kafka-clients` implementation (`String` key/value, manual assignment, auto-commit off). |
| `ReferenceResolver` / `EnvironmentReferenceResolver` | Resolves a `KafkaClusterDefinition` reference (treated strictly as an environment-variable name) to its value. Tests inject their own resolver rather than embedding broker addresses in the registry. |
| `KafkaOperation` / `KafkaAssertion` / `KafkaCapture` / `ResolvedKafkaCluster` | Immutable value objects. |

## Usage sketch

Each `KafkaStep.build()` result is a core `ScenarioStep` passed to `Scenario.Builder.step(...)`:

```java
var scenario = Scenario.builder("example-flow")
        .environment("ift")
        .step(KafkaStep.send("request-topic")
                .bodyFromResource("fixtures/event.json")
                .key("${requestId}")
                .injectCorrelationId()                 // SDK-owned correlationId → topic HEADER carrier
                .build())
        .step(KafkaStep.expect("response-topic")
                .correlationIdFromContext()            // select by SDK-owned correlationId
                .withinSeconds(30)
                .assertPath("$.status", "SUCCESS")
                .capture("entityId", "$.entityId")     // for later steps
                .build())
        .build();

stand.run(scenario);   // Validator → Runner (prepare → execute) → StepExecutor SPI → KafkaStepExecutor
```

Adding a `testImplementation` dependency on this module makes `kafka.*` step types runnable: the runner
discovers `KafkaStepExecutor` via `ServiceLoader`, so no wiring code is needed in the test.

## Failure semantics (plan §8.3)

- **Assertion failures** (timeout with no matching message, JSONPath mismatch, missing/`null` captured
  value, non-JSON/empty matched value) → `StandTestAssertionError` (a JUnit-native failure).
- **Infrastructure / configuration problems** (alias not whitelisted, no Kafka cluster, unresolved
  reference, correlation carrier other than HEADER, topic with no partitions, transport error,
  unresolved `${...}` variable) → `StandTestException`.

## Correlation carriers (plan §8.4)

The MVP implements the **HEADER** carrier for both inject (send) and match (expect) — fully specified
and shared with the REST adapter. `KEY` / `PAYLOAD_FIELD` are a later sub-iteration; requesting them
raises a `StandTestException` rather than silently mis-injecting.

## Environment resolution & security

Topics and the Kafka cluster are resolved through the core `EnvironmentRegistry`
(`environment(name) → topic(alias)` / `kafkaCluster()`, a fail-closed whitelist). `bootstrapServersRef`
and the optional security references are **references** (environment-variable names) resolved at run
time — broker addresses and secrets stay out of source (plan §9). Diagnostics attached to the
`StepResult` (`kafka.topic` / `kafka.realTopic` / `kafka.partition` / `kafka.offset` / `kafka.key` /
`kafka.messagesSeen`) deliberately exclude header and value payloads.

## Environment registry

`KafkaStepExecutor` resolves the `topic` alias and the Kafka cluster through a populated
`EnvironmentRegistry`. Provide one via either path: the **Spring Boot starter**
(`@ConfigurationProperties("stand.test")`), or — for plain JUnit — the **`stand-test-config`** module
(add it to `testImplementation` and drop a `stand-test-environments.yml` on the classpath). Both build the
registry from environment-variable *references*, never inline broker addresses or secrets. Without either,
`StandTestExtension` falls back to an empty registry and alias resolution fails at run time.

## Not here

No `KEY`/`PAYLOAD_FIELD` correlation carriers (later sub-iteration); no JSONPath as a *selection* filter
(in the MVP JSONPath asserts the already-selected message); no complex offset/commit strategies, batch
assertions or schema-registry/Avro; no Allure attachments (those belong to the reporting adapter, fed by
the `StepResult` diagnostics).

## Testing

Unit tests drive `KafkaStepExecutor` through the Apache `MockProducer`/`MockConsumer` (no broker):
send (correlation header on the wire, variable substitution, resource body), expect (selection by
correlation id and key, consume-and-advance, out-of-order discriminator, assertions, captures), the
pre-arm offset strategy, and timeout diagnostics (driven by a deterministic fake time source, so no real
waiting). `DefaultKafkaClientFactory` is covered by constructing real clients (which do not connect). A
`ServiceLoader` test asserts the `META-INF/services` registration. Instruction coverage is ≥ 80%
(JaCoCo).
