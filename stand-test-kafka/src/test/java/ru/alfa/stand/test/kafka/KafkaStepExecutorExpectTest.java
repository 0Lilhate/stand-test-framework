package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.await.DefaultAwaiter;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class KafkaStepExecutorExpectTest {

    private final MockConsumer<String, String> consumer = KafkaTestSupport.emptyConsumer(KafkaTestSupport.RESPONSE_NAME);
    private final FakeKafkaClientFactory factory = new FakeKafkaClientFactory(null, this.consumer);
    private final VariableStore store = new VariableStore();
    private final EnvironmentRegistry registry =
            KafkaTestSupport.registry(KafkaTestSupport.headerTopic(KafkaTestSupport.RESPONSE_ALIAS, KafkaTestSupport.RESPONSE_NAME));
    private final StepExecutionContext context = KafkaTestSupport.context(this.registry, this.store);

    private KafkaStepExecutor executor(Awaiter awaiter) {
        return new KafkaStepExecutor(this.factory, reference -> reference, awaiter);
    }

    private String correlationId() {
        return this.context.scenarioContext().correlationId().value();
    }

    private void addResponse(long offset, String key, String value, String correlationId) {
        this.consumer.addRecord(KafkaTestSupport.record(KafkaTestSupport.RESPONSE_NAME, offset, key, value, Map.of(KafkaTestSupport.CORRELATION_HEADER, correlationId)));
    }

    @Test
    @DisplayName("expect arms a consumer, selects by correlation id, asserts and captures from the matched value")
    void expectHappyPath() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS)
                .correlationIdFromContext()
                .assertPath("$.status", "SUCCESS")
                .capture("entityId", "$.entityId")
                .build();
        executor.prepare(step, this.context);
        addResponse(0L, null, "{\"status\":\"SUCCESS\",\"entityId\":\"e-9\"}", correlationId());

        StepResult result = executor.execute(step, this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics())
                .containsEntry("kafka.operation", "expect")
                .containsEntry("kafka.realTopic", KafkaTestSupport.RESPONSE_NAME)
                .containsEntry("kafka.offset", 0L);
        assertThat(this.store.get("entityId")).contains("e-9");
    }

    @Test
    @DisplayName("a message with a non-matching correlation id is skipped in favour of the matching one")
    void selectsByCorrelationId() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().assertPath("$.n", 2).build();
        executor.prepare(step, this.context);
        addResponse(0L, null, "{\"n\":1}", "some-other-correlation");
        addResponse(1L, null, "{\"n\":2}", correlationId());

        assertThat(executor.execute(step, this.context).status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("a key discriminator narrows selection among same-correlation messages")
    void selectsByKeyDiscriminator() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().key("B").assertPath("$.who", "b").build();
        executor.prepare(step, this.context);
        addResponse(0L, "A", "{\"who\":\"a\"}", correlationId());
        addResponse(1L, "B", "{\"who\":\"b\"}", correlationId());

        assertThat(executor.execute(step, this.context).status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("consume-and-advance: a second expect on the same topic reads the next message")
    void consumeAndAdvance() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep first = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).id("e1").correlationIdFromContext().assertPath("$.n", 1).build();
        ScenarioStep second = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).id("e2").correlationIdFromContext().assertPath("$.n", 2).build();
        executor.prepare(first, this.context);
        executor.prepare(second, this.context);
        addResponse(0L, null, "{\"n\":1}", correlationId());
        addResponse(1L, null, "{\"n\":2}", correlationId());

        assertThat(executor.execute(first, this.context).status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(executor.execute(second, this.context).status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(this.factory.consumerCreations()).isEqualTo(1);
    }

    @Test
    @DisplayName("discriminators disambiguate out-of-order same-correlation messages by selecting from the buffer")
    void discriminatorOutOfOrder() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep wantsA = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).id("a").correlationIdFromContext().key("A").assertPath("$.who", "a").build();
        ScenarioStep wantsB = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).id("b").correlationIdFromContext().key("B").assertPath("$.who", "b").build();
        executor.prepare(wantsA, this.context);
        // Messages arrive out of order relative to the expect steps: B at offset 0, A at offset 1.
        addResponse(0L, "B", "{\"who\":\"b\"}", correlationId());
        addResponse(1L, "A", "{\"who\":\"a\"}", correlationId());

        assertThat(executor.execute(wantsA, this.context).status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(executor.execute(wantsB, this.context).status()).isEqualTo(StepStatus.SUCCESS);
    }

    @Test
    @DisplayName("the consumer group id is unique per run and includes the test run id and topic alias")
    void groupIdIsUniquePerRun() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();
        executor.prepare(step, this.context);
        assertThat(this.factory.groupId())
                .contains(this.context.scenarioContext().testRunId().value())
                .contains(KafkaTestSupport.RESPONSE_ALIAS);
    }

    @Test
    @DisplayName("a timeout with no matching message raises an assertion error carrying diagnostics")
    void timeoutRaisesAssertionError() {
        KafkaStepExecutor executor = executor(new DefaultAwaiter(new FakeTimeSource()));
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().within(Duration.ofMillis(100)).build();
        executor.prepare(step, this.context);

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("did not receive a matching message")
                .hasMessageContaining("messagesSeen=0");
    }

    @Test
    @DisplayName("a non-matching message is seen but not selected, so expect still times out with a message sample")
    void nonMatchingMessageStillTimesOut() {
        KafkaStepExecutor executor = executor(new DefaultAwaiter(new FakeTimeSource()));
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().within(Duration.ofMillis(100)).build();
        executor.prepare(step, this.context);
        addResponse(0L, "k-seen", "{\"n\":1}", "different-correlation");

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("messagesSeen=1")
                // §4 timeout diagnostics: a bounded sample of last-seen messages, with offsets and the
                // correlation header value, so a failed expect explains what was seen and why none matched.
                .hasMessageContaining("lastMessages=[0@0 key=k-seen " + KafkaTestSupport.CORRELATION_HEADER + "=different-correlation]");
    }

    @Test
    @DisplayName("start-from-now: a pre-existing backlog is not replayed (seekToEnd, not from-beginning)")
    void startFromNowSkipsBacklog() {
        // The topic already holds two records (offsets 0,1) before the consumer arms; the log end is 2.
        MockConsumer<String, String> backlogConsumer = KafkaTestSupport.consumer(KafkaTestSupport.RESPONSE_NAME, 2L);
        FakeKafkaClientFactory localFactory = new FakeKafkaClientFactory(null, backlogConsumer);
        StepExecutionContext localContext = KafkaTestSupport.context(this.registry, new VariableStore());
        String correlation = localContext.scenarioContext().correlationId().value();
        KafkaStepExecutor executor = new KafkaStepExecutor(localFactory, reference -> reference, Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().assertPath("$.fresh", true).build();
        executor.prepare(step, localContext);
        // Backlog carrying the run's own correlation id — would be selected if the consumer replayed from
        // the beginning instead of seeking to the end.
        backlogConsumer.addRecord(KafkaTestSupport.record(KafkaTestSupport.RESPONSE_NAME, 0L, null, "{\"fresh\":false}", Map.of(KafkaTestSupport.CORRELATION_HEADER, correlation)));
        backlogConsumer.addRecord(KafkaTestSupport.record(KafkaTestSupport.RESPONSE_NAME, 1L, null, "{\"fresh\":false}", Map.of(KafkaTestSupport.CORRELATION_HEADER, correlation)));
        // The fresh message produced after arming, at the end of the log.
        backlogConsumer.addRecord(KafkaTestSupport.record(KafkaTestSupport.RESPONSE_NAME, 2L, null, "{\"fresh\":true}", Map.of(KafkaTestSupport.CORRELATION_HEADER, correlation)));

        StepResult result = executor.execute(step, localContext);

        // Selected the fresh offset-2 message, not a backlog record; the backlog was never even seen.
        assertThat(result.diagnostics()).containsEntry("kafka.offset", 2L).containsEntry("kafka.messagesSeen", 1);
    }

    @Test
    @DisplayName("a failed JSONPath assertion on the matched message raises an assertion error")
    void assertionMismatchFails() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().assertPath("$.status", "SUCCESS").build();
        executor.prepare(step, this.context);
        addResponse(0L, null, "{\"status\":\"FAIL\"}", correlationId());

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("$.status");
    }

    @Test
    @DisplayName("an empty matched value on an assert/capture step is an assertion error")
    void emptyValueFails() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().assertPath("$.status", "SUCCESS").build();
        executor.prepare(step, this.context);
        addResponse(0L, null, "", correlationId());

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("capturing a JSONPath that resolves to null raises an assertion error")
    void captureNullFails() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().capture("entityId", "$.entityId").build();
        executor.prepare(step, this.context);
        addResponse(0L, null, "{\"entityId\":null}", correlationId());

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("entityId");
    }

    @Test
    @DisplayName("executing an expect without a prior prepare is an infrastructure error")
    void executeWithoutPrepareFails() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("No armed Kafka consumer");
    }

    @Test
    @DisplayName("correlationIdFromContext on a topic without a HEADER carrier is an infrastructure error")
    void correlationWithoutHeaderCarrierFails() {
        EnvironmentRegistry noCorrelation = KafkaTestSupport.registry(KafkaTestSupport.topicWithoutCorrelation(KafkaTestSupport.RESPONSE_ALIAS, KafkaTestSupport.RESPONSE_NAME));
        StepExecutionContext localContext = KafkaTestSupport.context(noCorrelation, new VariableStore());
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();
        executor.prepare(step, localContext);

        assertThatThrownBy(() -> executor.execute(step, localContext))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("HEADER carrier");
    }

    @Test
    @DisplayName("arming a topic with no partitions is an infrastructure error")
    void armingWithoutPartitionsFails() {
        MockConsumer<String, String> bare = new MockConsumer<>(org.apache.kafka.clients.consumer.OffsetResetStrategy.LATEST);
        FakeKafkaClientFactory bareFactory = new FakeKafkaClientFactory(null, bare);
        KafkaStepExecutor executor = new KafkaStepExecutor(bareFactory, reference -> reference, Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();

        assertThatThrownBy(() -> executor.prepare(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("no partitions");
    }

    @Test
    @DisplayName("prepare on a send step is a no-op (no consumer armed)")
    void prepareIgnoresSendSteps() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep send = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").build();
        executor.prepare(send, this.context);
        assertThat(this.factory.consumerCreations()).isZero();
    }

    @Test
    @DisplayName("the armed consumer is closed when the run-scoped ResourceScope is closed (no leak)")
    void armedConsumerClosedByResourceScope() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();
        executor.prepare(step, this.context);

        this.context.resourceScope().closeAll();

        assertThat(this.consumer.closed()).isTrue();
    }

    @Test
    @DisplayName("a selected record is compacted out of the buffer but can never be selected twice (selectedKeys guard)")
    void selectedRecordCompactedAndNeverReSelected() {
        KafkaStepExecutor executor = executor(new DefaultAwaiter(new FakeTimeSource()));
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();
        executor.prepare(step, this.context);
        addResponse(0L, null, "{\"n\":1}", correlationId());

        StepResult first = executor.execute(step, this.context);

        assertThat(first.diagnostics()).containsEntry("kafka.offset", 0L);
        // The only matching record was selected (and compacted); a second expect must time out, not
        // re-select the same offset.
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class);
    }

    @Test
    @DisplayName("exceeding the unmatched-buffer bound is an immediate infrastructure error, not a slow timeout")
    void bufferCapBreachFailsFast() {
        KafkaStepExecutor executor = executor(new DefaultAwaiter(new FakeTimeSource()));
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();
        executor.prepare(step, this.context);
        for (long offset = 0; offset <= ArmedConsumer.MAX_BUFFERED; offset++) {
            addResponse(offset, null, "{}", "other-correlation-" + offset);
        }

        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("buffered more than " + ArmedConsumer.MAX_BUFFERED)
                .hasMessageContaining(KafkaTestSupport.RESPONSE_NAME);
    }

    @Test
    @DisplayName("a topic naming a cluster alias arms its consumer against that named cluster, not the default one")
    void topicOnNamedClusterUsesItsBootstrap() {
        FakeKafkaClientFactory auditFactory = new FakeKafkaClientFactory(null, KafkaTestSupport.emptyConsumer(KafkaTestSupport.RESPONSE_NAME));
        KafkaStepExecutor executor = new KafkaStepExecutor(auditFactory, reference -> reference, Awaiter.create());
        TopicDefinition auditTopic = new TopicDefinition(
                KafkaTestSupport.RESPONSE_ALIAS, KafkaTestSupport.RESPONSE_NAME,
                new CorrelationConfig(CorrelationSource.HEADER, KafkaTestSupport.CORRELATION_HEADER),
                KafkaTestSupport.AUDIT_CLUSTER_ALIAS);
        StepExecutionContext auditContext = KafkaTestSupport.context(KafkaTestSupport.multiClusterRegistry(auditTopic), new VariableStore());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();

        executor.prepare(step, auditContext);

        // The identity reference resolver passes the ref through, so the recorded cluster shows which
        // definition was picked: the named audit cluster, not the environment default.
        assertThat(auditFactory.consumerCluster().bootstrapServers()).isEqualTo(KafkaTestSupport.AUDIT_BOOTSTRAP_REF);
        auditContext.resourceScope().closeAll();
    }

    @Test
    @DisplayName("a topic without a cluster alias keeps using the environment's default cluster")
    void topicWithoutClusterUsesDefault() {
        FakeKafkaClientFactory defaultFactory = new FakeKafkaClientFactory(null, KafkaTestSupport.emptyConsumer(KafkaTestSupport.RESPONSE_NAME));
        KafkaStepExecutor executor = new KafkaStepExecutor(defaultFactory, reference -> reference, Awaiter.create());
        TopicDefinition plainTopic = KafkaTestSupport.headerTopic(KafkaTestSupport.RESPONSE_ALIAS, KafkaTestSupport.RESPONSE_NAME);
        StepExecutionContext defaultContext = KafkaTestSupport.context(KafkaTestSupport.multiClusterRegistry(plainTopic), new VariableStore());
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();

        executor.prepare(step, defaultContext);

        assertThat(defaultFactory.consumerCluster().bootstrapServers()).isEqualTo(KafkaTestSupport.BOOTSTRAP_REF);
        defaultContext.resourceScope().closeAll();
    }

    @Test
    @DisplayName("prepare registers the armed consumer under the namespaced kafka.consumer: key, so it cannot collide with another adapter's alias")
    void prepareRegistersNamespacedScopeKey() {
        KafkaStepExecutor executor = executor(Awaiter.create());
        // Simulates a same-named logical alias owned by another adapter in the same run.
        this.context.resourceScope().register(KafkaTestSupport.RESPONSE_ALIAS, () -> {
        });
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();

        executor.prepare(step, this.context);

        assertThat(this.context.resourceScope().contains("kafka.consumer:" + KafkaTestSupport.RESPONSE_ALIAS)).isTrue();
        assertThat(this.factory.consumerCreations()).isEqualTo(1);
    }
}
