package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class KafkaStepExecutorLoggingTest {

    private static final String SECRET_VALUE = "{\"pan\":\"4111111111111111\"}";
    private static final String SECRET_KEY = "secret-key-777";
    private static final String SECRET_HEADER_VALUE = "top-secret-token";

    @Test
    @DisplayName("a kafka.send emits a metadata-only DEBUG line naming the topic and never the key, value or headers")
    void send_logsTopicMetadataOnly_neverPayload() {
        Logger executorLogger = (Logger) LoggerFactory.getLogger(KafkaStepExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        // The test-only logback config keeps the root at WARN, so raise this logger to DEBUG to capture
        // the produce trace, then reset it to inherit the root level again in the finally block.
        executorLogger.setLevel(Level.DEBUG);
        executorLogger.addAppender(appender);
        try {
            EnvironmentRegistry registry = KafkaTestSupport.registry(KafkaTestSupport.headerTopic(KafkaTestSupport.REQUEST_ALIAS, KafkaTestSupport.REQUEST_NAME));
            StepExecutionContext context = KafkaTestSupport.context(registry, new VariableStore());
            ScenarioStep step = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS)
                    .body(SECRET_VALUE)
                    .key(SECRET_KEY)
                    .header("X-Secret-Header", SECRET_HEADER_VALUE)
                    .injectCorrelationId()
                    .build();
            MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
            FakeKafkaClientFactory factory = new FakeKafkaClientFactory(producer, null);

            StepResult result = new KafkaStepExecutor(factory, reference -> reference, Awaiter.create()).execute(step, context);

            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
            // A DEBUG line names the real topic (metadata only).
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .contains("Kafka produce to topic")
                        .contains(KafkaTestSupport.REQUEST_NAME);
            });
            // No captured line leaks the message value/payload, the key or any header value.
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(SECRET_VALUE)
                    .doesNotContain("4111111111111111")
                    .doesNotContain(SECRET_KEY)
                    .doesNotContain(SECRET_HEADER_VALUE));
        } finally {
            executorLogger.detachAppender(appender);
            executorLogger.setLevel(null);
        }
    }
}
