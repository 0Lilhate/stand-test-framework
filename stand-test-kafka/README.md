# stand-test-kafka

**Group:** adapters · **Gradle plugin:** `java-library`

Kafka adapter for producing to / consuming from topics on a stand. When implemented it will own the
typed `KafkaStep` model (step types `kafka.send` / `kafka.expect`) and the Kafka `StepExecutor`
(registered via the core SPI), and be the single point of real Kafka IO to the stand.

**Planned internal dependencies:** `stand-test-core`, `stand-test-await`.
**Planned external dependencies:** `kafka-clients` (raw Apache — for `assign`/`seek` control) and
`json-path` (JSONPath; message value is read as a string, so a separate JSON binding / Jackson is not
required, mirroring `stand-test-rest`).

Key contracts (see `docs/arch/stand-test-sdk-implementation-plan.md`):
- §4 (Iteration 5, MVP) — `kafka.send` / `kafka.expect`, offset strategy, timeout-diagnostics
- §8.4 — correlationId carriers (HEADER / KEY / PAYLOAD_FIELD) for inject and expect-match
- **§8.7 — consumer pre-arming (`StepExecutor.prepare` + run-scoped `ResourceScope`)**, the resolution
  of `KAFKA-SEEK-RACE`: consumers are positioned (`seekToEnd`) before any step runs, so the expect
  consumer is armed before the triggering step produces the message
- §9 — `KafkaClusterDefinition` (`bootstrapServersRef` + security secret-refs) for broker connection

> **Prerequisite:** Iteration 5 needs the core additions `StepExecutor.prepare` + `ResourceScope` (§8.7)
> and the `KafkaClusterDefinition` env model (§9) before the adapter itself.

> Skeleton stage: no Kafka producer / consumer implemented yet.
