/**
 * Stand test SDK — Kafka adapter.
 *
 * <p>Owns the typed lazy-builder {@link ru.alfa.stand.test.kafka.KafkaStep} (step types
 * {@code kafka.send} / {@code kafka.expect}) and the Kafka
 * {@link ru.alfa.stand.test.kafka.KafkaStepExecutor} (registered via the core {@code StepExecutor} SPI
 * in {@code META-INF/services}). The executor is the single point of real Kafka IO to a stand, built on
 * raw Apache {@code kafka-clients} (for {@code assign}/{@code seek} control); the SDK never ships its own
 * Kafka client (plan §4, §20).
 *
 * <p><strong>{@code kafka.send}</strong> publishes a JSON message to a topic alias, substituting
 * {@code ${...}} variables in key/headers/value and injecting the SDK-owned correlation id outbound via
 * the topic's HEADER carrier. The producer is created and closed inside {@code execute}
 * (try-with-resources, {@code flush} before close).
 *
 * <p><strong>{@code kafka.expect}</strong> waits (through the {@link ru.alfa.stand.test.await.Awaiter},
 * no {@code Thread.sleep}) for a message selected by the SDK-owned correlation id (HEADER carrier) and,
 * optionally, a {@code key} discriminator; it then runs JSONPath assertions and captures against the
 * matched value. The consumer is pre-armed in
 * {@link ru.alfa.stand.test.kafka.KafkaStepExecutor#prepare} (one per topic per run,
 * {@code assign}/{@code seekToEnd}, start-from-now) and shared/advanced across expects on the same
 * topic — the resolution of {@code KAFKA-SEEK-RACE} (plan §8.7). The consumer lives in the run's
 * {@link ru.alfa.stand.test.core.execution.ResourceScope} and is closed by the runner.
 *
 * <p>Broker connection and topics are resolved from the core
 * {@link ru.alfa.stand.test.core.environment.KafkaClusterDefinition} and
 * {@link ru.alfa.stand.test.core.environment.TopicDefinition} via the
 * {@link ru.alfa.stand.test.core.environment.EnvironmentRegistry}: addresses and secrets are references
 * resolved at run time (plan §9), never hardcoded.
 */
package ru.alfa.stand.test.kafka;
