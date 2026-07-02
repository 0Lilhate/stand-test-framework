package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.core.variable.VariableStore;

class GrpcStepExecutorTest {

    private final VariableStore store = new VariableStore();
    private final FakeGrpcChannelFactory channelFactory = new FakeGrpcChannelFactory();
    private StepExecutionContext context;

    private GrpcStepExecutor executor(FakeGrpcCallInvoker invoker, GrpcTargetDefinitionFixture fixture) {
        this.context = GrpcTestSupport.context(fixture.registry(), this.store);
        return new GrpcStepExecutor(this.channelFactory, reference -> "localhost:50051", invoker);
    }

    @AfterEach
    void closeScope() {
        if (this.context != null) {
            this.context.resourceScope().closeAll();
        }
    }

    @Test
    @DisplayName("supports only grpc.* step types")
    void supportsOnlyGrpc() {
        GrpcStepExecutor executor = new GrpcStepExecutor();
        assertThat(executor.supports("grpc.unary")).isTrue();
        assertThat(executor.supports("rest.get")).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    @DisplayName("a unary call asserts and captures from the response and records diagnostics + attachments")
    void happyPath() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{\"status\":\"OK\",\"chargeId\":\"c-1\"}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS)
                .method("billing.BillingService/Charge")
                .request("{\"amount\":10}")
                .withinSeconds(5)
                .assertPath("$.status", "OK")
                .capture("chargeId", "$.chargeId")
                .build();

        StepResult result = executor.execute(step, this.context);

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(result.diagnostics())
                .containsEntry("grpc.operation", "unary")
                .containsEntry("grpc.target", GrpcTestSupport.TARGET_ALIAS)
                .containsEntry("grpc.method", "billing.BillingService/Charge")
                .containsEntry("grpc.deadlineMillis", 5_000L);
        assertThat(result.attachments()).extracting("name").contains("grpc-request", "grpc-response");
        assertThat(this.store.get("chargeId")).contains("c-1");
        assertThat(invoker.deadlineMillis()).isEqualTo(5_000L);
        assertThat(invoker.methodFullName()).isEqualTo("billing.BillingService/Charge");
    }

    @Test
    @DisplayName("an unknown target alias is a configuration error")
    void unknownTarget() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = GrpcStep.unary("does-not-exist").method("p.S/M").withinSeconds(1).build();
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    @DisplayName("an unknown environment is a configuration error")
    void unknownEnvironment() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        this.context = GrpcTestSupport.context(GrpcTestSupport.emptyRegistry(), this.store);
        GrpcStepExecutor executor = new GrpcStepExecutor(this.channelFactory, reference -> "localhost:1", invoker);
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS).method("p.S/M").withinSeconds(1).build();
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("not whitelisted");
    }

    @Test
    @DisplayName("the SDK-owned correlation id is injected into gRPC metadata")
    void injectsCorrelationMetadata() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS).method("p.S/M").injectCorrelationId().withinSeconds(1).build();

        executor.execute(step, this.context);

        String expected = this.context.scenarioContext().correlationId().value();
        assertThat(invoker.metadataValue(GrpcTestSupport.CORRELATION_KEY)).isEqualTo(expected);
    }

    @Test
    @DisplayName("correlation injection without a METADATA carrier is a configuration error")
    void correlationRequiresMetadataCarrier() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withoutCorrelation());
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS).method("p.S/M").injectCorrelationId().withinSeconds(1).build();
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("METADATA");
    }

    @Test
    @DisplayName("custom metadata (with ${} substitution) is passed on the wire and masked in diagnostics")
    void customMetadataAndMasking() {
        this.store.put("tenant", "acme");
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        // Build the wire map directly so a secret-bearing key (which the builder rejects) still exercises
        // the executor's defensive masking, alongside a normal ${}-substituted entry.
        ScenarioStep step = new GenericStep("s", "grpc.unary", "d", Map.of(
                GrpcStepParameters.TARGET, GrpcTestSupport.TARGET_ALIAS,
                GrpcStepParameters.METHOD_FULL_NAME, "p.S/M",
                GrpcStepParameters.DEADLINE_MILLIS, 1_000L,
                GrpcStepParameters.METADATA, Map.of("x-tenant", "${tenant}", "authorization", "Bearer secret", "x-api-key", "k-123"),
                GrpcStepParameters.INJECT_CORRELATION_ID, false,
                GrpcStepParameters.ASSERTIONS, List.of(),
                GrpcStepParameters.CAPTURES, List.of()));

        StepResult result = executor.execute(step, this.context);

        assertThat(invoker.metadataValue("x-tenant")).isEqualTo("acme");
        assertThat(invoker.metadataValue("authorization")).isEqualTo("Bearer secret");
        assertThat(invoker.metadataValue("x-api-key")).isEqualTo("k-123");
        Object masked = result.diagnostics().get("grpc.metadata");
        assertThat(masked).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> maskedMetadata = (Map<String, String>) masked;
        assertThat(maskedMetadata)
                .containsEntry("x-tenant", "acme")
                .containsEntry("authorization", "***")
                .containsEntry("x-api-key", "***");
    }

    @Test
    @DisplayName("an invalid gRPC metadata key name is a configuration error, not a raw IllegalArgumentException")
    void invalidMetadataKeyMapsToStandTestException() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = new GenericStep("s", "grpc.unary", "d", Map.of(
                GrpcStepParameters.TARGET, GrpcTestSupport.TARGET_ALIAS,
                GrpcStepParameters.METHOD_FULL_NAME, "p.S/M",
                GrpcStepParameters.DEADLINE_MILLIS, 1_000L,
                GrpcStepParameters.METADATA, Map.of("bad key", "v"),
                GrpcStepParameters.INJECT_CORRELATION_ID, false,
                GrpcStepParameters.ASSERTIONS, List.of(),
                GrpcStepParameters.CAPTURES, List.of()));
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("metadata key");
    }

    @Test
    @DisplayName("a failed JSONPath assertion is a StandTestAssertionError")
    void assertionFailure() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{\"status\":\"FAIL\"}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS).method("p.S/M").withinSeconds(1).assertPath("$.status", "OK").build();
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("$.status");
    }

    @Test
    @DisplayName("a gRPC status failure (deadline exceeded) becomes a StandTestException that keeps the code")
    void statusFailureMapsToStandTestException() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.throwing(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS).method("p.S/M").withinSeconds(1).build();
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("DEADLINE_EXCEEDED");
    }

    @Test
    @DisplayName("prepare pre-creates the channel and execute reuses it (one channel per target alias)")
    void prepareCreatesReusableChannel() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = GrpcStep.unary(GrpcTestSupport.TARGET_ALIAS).method("p.S/M").withinSeconds(1).build();

        executor.prepare(step, this.context);
        executor.execute(step, this.context);
        executor.execute(step, this.context);

        assertThat(this.channelFactory.creations()).isEqualTo(1);
        assertThat(this.channelFactory.lastTarget().target()).isEqualTo("localhost:50051");
    }

    @Test
    @DisplayName("a step that is not a GenericStep is a configuration error")
    void rejectsNonGenericStep() {
        FakeGrpcCallInvoker invoker = FakeGrpcCallInvoker.returning("{}");
        GrpcStepExecutor executor = executor(invoker, GrpcTargetDefinitionFixture.withCorrelation());
        ScenarioStep step = new ScenarioStep() {
            @Override
            public String id() {
                return "s";
            }

            @Override
            public String type() {
                return "grpc.unary";
            }

            @Override
            public String description() {
                return "d";
            }
        };
        assertThatThrownBy(() -> executor.execute(step, this.context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("GenericStep");
    }
}
