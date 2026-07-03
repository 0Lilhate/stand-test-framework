package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class KafkaStepExecutorSendTest {

    private MockProducer<String, String> producer;

    private StepResult run(ScenarioStep step, StepExecutionContext context) {
        this.producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        FakeKafkaClientFactory factory = new FakeKafkaClientFactory(this.producer, null);
        return new KafkaStepExecutor(factory, reference -> reference, Awaiter.create()).execute(step, context);
    }

    private static StepExecutionContext context(VariableStore store) {
        EnvironmentRegistry registry = KafkaTestSupport.registry(KafkaTestSupport.headerTopic(KafkaTestSupport.REQUEST_ALIAS, KafkaTestSupport.REQUEST_NAME));
        return KafkaTestSupport.context(registry, store);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return (header == null) ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("send publishes to the real topic with the key, value and injected correlation header")
    void sendHappyPath() {
        StepExecutionContext context = context(new VariableStore());
        StepResult result = run(KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{\"a\":1}").key("k-1").injectCorrelationId().build(), context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics()).containsEntry("kafka.realTopic", KafkaTestSupport.REQUEST_NAME).containsEntry("kafka.operation", "send");
        List<ProducerRecord<String, String>> history = this.producer.history();
        assertThat(history).hasSize(1);
        ProducerRecord<String, String> sent = history.get(0);
        assertThat(sent.topic()).isEqualTo(KafkaTestSupport.REQUEST_NAME);
        assertThat(sent.key()).isEqualTo("k-1");
        assertThat(sent.value()).isEqualTo("{\"a\":1}");
        assertThat(header(sent, KafkaTestSupport.CORRELATION_HEADER)).isEqualTo(context.scenarioContext().correlationId().value());
        assertThat(this.producer.closed()).isTrue();
    }

    @Test
    @DisplayName("placeholders in key, value and headers are substituted from the variable store")
    void substitutesVariables() {
        VariableStore store = new VariableStore();
        store.put("requestId", "req-42");
        run(KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{\"id\":\"${requestId}\"}").key("${requestId}").header("x-req", "${requestId}").build(), context(store));

        ProducerRecord<String, String> sent = this.producer.history().get(0);
        assertThat(sent.key()).isEqualTo("req-42");
        assertThat(sent.value()).isEqualTo("{\"id\":\"req-42\"}");
        assertThat(header(sent, "x-req")).isEqualTo("req-42");
    }

    @Test
    @DisplayName("the message body is loaded from a classpath resource and resolved")
    void bodyFromResource() {
        StepExecutionContext context = context(new VariableStore());
        run(KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).bodyFromResource("fixtures/event.json").build(), context);

        assertThat(this.producer.history().get(0).value())
                .contains(context.scenarioContext().correlationId().value())
                .contains("PAYMENT_REQUESTED");
    }

    @Test
    @DisplayName("no correlation header is added when injection is not requested")
    void noCorrelationWhenNotRequested() {
        run(KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").build(), context(new VariableStore()));
        assertThat(header(this.producer.history().get(0), KafkaTestSupport.CORRELATION_HEADER)).isNull();
    }

    @Test
    @DisplayName("the bootstrap reference is resolved and handed to the producer factory")
    void resolvesBootstrapReference() {
        MockProducer<String, String> mock = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        FakeKafkaClientFactory factory = new FakeKafkaClientFactory(mock, null);
        new KafkaStepExecutor(factory, reference -> "broker:9092", Awaiter.create())
                .execute(KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").build(), context(new VariableStore()));
        assertThat(factory.producerCluster().bootstrapServers()).isEqualTo("broker:9092");
    }

    @Test
    @DisplayName("requesting correlation injection on a topic without a HEADER carrier is an infrastructure error")
    void injectionWithoutHeaderCarrierFails() {
        EnvironmentRegistry registry = KafkaTestSupport.registry(KafkaTestSupport.topicWithoutCorrelation(KafkaTestSupport.REQUEST_ALIAS, KafkaTestSupport.REQUEST_NAME));
        ScenarioStep step = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").injectCorrelationId().build();
        assertThatThrownBy(() -> run(step, KafkaTestSupport.context(registry, new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("HEADER carrier");
    }

    @Test
    @DisplayName("an unknown topic alias is an infrastructure error")
    void unknownTopicFails() {
        ScenarioStep step = KafkaStep.send("unknown-topic").body("{}").build();
        assertThatThrownBy(() -> run(step, context(new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not whitelisted");
    }

    @Test
    @DisplayName("an unknown environment is an infrastructure error")
    void unknownEnvironmentFails() {
        ScenarioStep step = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").build();
        assertThatThrownBy(() -> run(step, KafkaTestSupport.context(new InMemoryEnvironmentRegistry(Map.of()), new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Environment 'ift' is not whitelisted");
    }

    @Test
    @DisplayName("an environment without a Kafka cluster is an infrastructure error")
    void noClusterFails() {
        EnvironmentRegistry registry = KafkaTestSupport.registryWithoutCluster(KafkaTestSupport.headerTopic(KafkaTestSupport.REQUEST_ALIAS, KafkaTestSupport.REQUEST_NAME));
        ScenarioStep step = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").build();
        assertThatThrownBy(() -> run(step, KafkaTestSupport.context(registry, new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("no Kafka cluster");
    }

    @Test
    @DisplayName("a non-GenericStep is rejected")
    void nonGenericStepFails() {
        assertThatThrownBy(() -> run(new StubStep("s", "kafka.send", ""), context(new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("GenericStep");
    }

    @Test
    @DisplayName("a parameter map with both body and bodyResource is rejected")
    void bothBodyAndResourceFails() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(KafkaStepParameters.TOPIC, KafkaTestSupport.REQUEST_ALIAS);
        parameters.put(KafkaStepParameters.BODY, "inline");
        parameters.put(KafkaStepParameters.BODY_RESOURCE, "fixtures/event.json");
        ScenarioStep step = new GenericStep("s", "kafka.send", "", parameters);
        assertThatThrownBy(() -> run(step, context(new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("a kafka.send parameter map with no body is rejected")
    void missingBodyFails() {
        ScenarioStep step = new GenericStep("s", "kafka.send", "", Map.of(KafkaStepParameters.TOPIC, KafkaTestSupport.REQUEST_ALIAS));
        assertThatThrownBy(() -> run(step, context(new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("requires");
    }

    @Test
    @DisplayName("a broker-side send failure maps to a StandTestException naming topic and alias, and the producer is still closed")
    void sendFailureMapsToInfraAndClosesProducer() {
        MockProducer<String, String> failing = new MockProducer<>(false, new StringSerializer(), new StringSerializer()) {

            @Override
            public void flush() {
                errorNext(new org.apache.kafka.common.errors.TimeoutException("Topic not present in metadata after 10000 ms"));
            }
        };
        FakeKafkaClientFactory factory = new FakeKafkaClientFactory(failing, null);
        KafkaStepExecutor executor = new KafkaStepExecutor(factory, reference -> reference, Awaiter.create());
        ScenarioStep step = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").build();

        assertThatThrownBy(() -> executor.execute(step, context(new VariableStore())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Failed to send")
                .hasMessageContaining(KafkaTestSupport.REQUEST_NAME)
                .hasMessageContaining(KafkaTestSupport.REQUEST_ALIAS)
                .hasCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
        assertThat(failing.closed()).isTrue();
    }

    private record StubStep(String id, String type, String description) implements ScenarioStep {
    }
}
