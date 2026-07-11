package ru.alfa.stand.test.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Pins the REST adapter's metadata-only DEBUG tracing: a real request over the offline
 * {@link RecordingHttpServer} must emit the method + path and the status at DEBUG, and no captured
 * message may leak a secret (the {@code Authorization} credential) or the response body.
 */
class RestStepExecutorLoggingTest {

    private static final String BODY_SECRET_MARKER = "SECRET_BODY_MARKER";
    private static final String BASIC_TOKEN = "QWxhZGRpbjpvcGVuIHNlc2FtZQ==";

    @Test
    @DisplayName("a request logs method, path and status at DEBUG without leaking the auth header or response body")
    void logsMethodPathAndStatusWithoutSecrets() {
        Logger restLogger = (Logger) LoggerFactory.getLogger(RestStepExecutor.class);
        Level previousLevel = restLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        restLogger.setLevel(Level.DEBUG);
        restLogger.addAppender(appender);
        try (RecordingHttpServer server = new RecordingHttpServer().respond(200, "{\"requestId\":\"r-1\",\"secret\":\"" + BODY_SECRET_MARKER + "\"}")) {
            StepExecutionContext context = RestTestSupport.context(RestTestSupport.registryWithBasicAuth(server.baseUrl()), new VariableStore());
            EnvironmentAuthHeaderResolver authResolver = new EnvironmentAuthHeaderResolver(
                    Map.of("CLIENT_USER", "Aladdin", "CLIENT_PASSWORD", "open sesame")::get);
            RestStepExecutor executor = new RestStepExecutor(new WebClientHttpCaller(), ref -> ref, authResolver);
            ScenarioStep step = RestStep.get(RestTestSupport.SERVICE, "/api/items").expectStatus(200).build();

            StepResult result = executor.execute(step, context);

            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
            assertThat(server.capturedHeader("Authorization")).isEqualTo("Basic " + BASIC_TOKEN);
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("GET").contains("/api/items");
            });
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("GET").contains("/api/items").contains("200");
            });
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(BODY_SECRET_MARKER)
                    .doesNotContain(BASIC_TOKEN)
                    .doesNotContain("open sesame")
                    .doesNotContain("Authorization"));
        } finally {
            restLogger.detachAppender(appender);
            restLogger.setLevel(previousLevel);
        }
    }
}
