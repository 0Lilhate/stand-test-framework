package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.kafka.KafkaStep;

/**
 * Kafka {@code send}/{@code expect} usage example. Unlike the REST and DB examples, this cannot run
 * against an in-process double: {@code kafka.expect} arms a real {@code KafkaConsumer} and the fake
 * client factory is package-private to the adapter's own tests. It therefore needs a live broker and is
 * tagged {@code requires-broker} so the default {@code test} run (offline, no broker) excludes it — run
 * it explicitly with {@code -PincludeRequiresBroker} against a broker (see the module README).
 *
 * <p>The scenario is a self-contained round-trip on one topic: {@code send} publishes a message with the
 * SDK correlation id injected as a header, then {@code expect} — whose consumer the runner's prepare phase
 * armed at the log end before the send — matches that message by the same correlation id, asserts a JSON
 * field and captures another. No downstream service is required; the broker echoes the published record.
 * This is a round-trip sanity check of the DSL, not a template for a real async reply (which would
 * {@code send} to a request topic and {@code expect} from a separate response topic a service writes to).
 *
 * <p>The topic {@code stand-test-example-events} must already exist on the broker (or the broker must have
 * {@code auto.create.topics.enable=true}): {@code expect}'s arm reads the topic's partitions during the
 * prepare phase and fails fast with a {@code StandTestException} if it is absent.
 */
@Tag("requires-broker")
class KafkaExampleTest {

    @Test
    @DisplayName("kafka.send injects the SDK correlation header; kafka.expect matches it, asserts and captures")
    void sendThenExpect_correlatedRoundTrip() {
        StandClient stand = ExampleStand.kafkaStand(ExampleStand.kafkaRegistry());
        Scenario scenario = Scenario.builder("kafka-example")
                .environment(ExampleStand.ENVIRONMENT)
                .step(KafkaStep.send(ExampleStand.TOPIC)
                        .body("{\"status\":\"SUCCESS\",\"entityId\":\"kafka-1\"}")
                        .injectCorrelationId()
                        .build())
                .step(KafkaStep.expect(ExampleStand.TOPIC)
                        .correlationIdFromContext()
                        .assertPath("$.status", "SUCCESS")
                        .capture("entityId", "$.entityId")
                        .withinSeconds(10)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).hasSize(2);
    }
}
