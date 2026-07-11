package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

/**
 * Verifies the metadata-only DEBUG tracing the executor emits around a real unary call: the
 * fully-qualified method, the deadline and the gRPC status code are logged, while the request and response
 * protobuf bodies never reach the log. Runs one {@code grpc.health.v1.Health/Check} against the same
 * in-process reflection server the invoker test uses, capturing the executor's log lines with a logback
 * {@link ListAppender} raised to {@link Level#DEBUG}.
 */
class GrpcStepExecutorLoggingTest {

    private static final String METHOD = "grpc.health.v1.Health/Check";

    private final VariableStore store = new VariableStore();
    private Server server;
    private ManagedChannel channel;
    private StepExecutionContext context;

    @BeforeEach
    void startServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", ServingStatus.SERVING);
        this.server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(health.getHealthService())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();
        this.channel = InProcessChannelBuilder.forName(name).build();
    }

    @AfterEach
    void stopServer() {
        if (this.context != null) {
            this.context.resourceScope().closeAll();
        }
        this.channel.shutdownNow();
        this.server.shutdownNow();
    }

    @Test
    @DisplayName("execute logs the method, deadline and gRPC status at DEBUG and never leaks the request/response bodies")
    void logsMethodDeadlineAndStatus_withoutPayload() {
        Logger executorLogger = (Logger) LoggerFactory.getLogger(GrpcStepExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previousLevel = executorLogger.getLevel();
        executorLogger.addAppender(appender);
        executorLogger.setLevel(Level.DEBUG);
        try {
            this.context = GrpcTestSupport.context(GrpcTestSupport.registry(GrpcTestSupport.metadataTarget()), this.store);
            GrpcStepExecutor executor = new GrpcStepExecutor(target -> this.channel, reference -> "localhost:50051", new DefaultGrpcCallInvoker());
            ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS)
                    .method(METHOD)
                    .request("{\"service\":\"\"}")
                    .withinSeconds(5)
                    .assertPath("$.status", "SERVING")
                    .build();

            StepResult result = executor.execute(step, this.context);

            assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains(METHOD).contains("deadline=");
            });
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains(METHOD).contains("status").contains("OK");
            });
            // Metadata only: the request field ("service") and the response value ("SERVING") never appear.
            assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("service"));
            assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("SERVING"));
        } finally {
            executorLogger.detachAppender(appender);
            executorLogger.setLevel(previousLevel);
        }
    }
}
