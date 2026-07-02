package ru.alfa.stand.test.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class GrpcStepTest {

    @Test
    @DisplayName("a valid unary step builds a GenericStep with the grpc.unary type and wire keys")
    void buildsValidUnaryStep() {
        ScenarioStep step = GrpcStep.unary("billing-grpc")
                .method("billing.BillingService/Charge")
                .requestFromResource("fixtures/charge.json")
                .metadata("x-tenant", "acme")
                .injectCorrelationId()
                .withinSeconds(5)
                .assertPath("$.status", "OK")
                .capture("chargeId", "$.chargeId")
                .build();

        assertThat(step.type()).isEqualTo("grpc.unary");
        assertThat(step).isInstanceOf(GenericStep.class);
        Map<String, Object> parameters = ((GenericStep) step).parameters();
        assertThat(parameters)
                .containsEntry(GrpcStepParameters.TARGET, "billing-grpc")
                .containsEntry(GrpcStepParameters.METHOD_FULL_NAME, "billing.BillingService/Charge")
                .containsEntry(GrpcStepParameters.DEADLINE_MILLIS, 5_000L)
                .containsEntry("requestResource", "fixtures/charge.json")
                .containsEntry(GrpcStepParameters.INJECT_CORRELATION_ID, true);
        assertThat(parameters.get(GrpcStepParameters.METADATA)).isEqualTo(Map.of("x-tenant", "acme"));
        assertThat(GrpcStepParameters.assertions(parameters)).containsExactly(new GrpcAssertion("$.status", "OK"));
        assertThat(GrpcStepParameters.captures(parameters)).containsExactly(new GrpcCapture("chargeId", "$.chargeId"));
    }

    @Test
    @DisplayName("an explicit id overrides the derived id")
    void honoursExplicitId() {
        ScenarioStep step = GrpcStep.unary("t").method("p.S/M").id("charge").withinSeconds(1).build();
        assertThat(step.id()).isEqualTo("charge");
    }

    @Test
    @DisplayName("a missing method is rejected at build time")
    void rejectsMissingMethod() {
        assertThatThrownBy(() -> GrpcStep.unary("t").withinSeconds(1).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("method");
    }

    @Test
    @DisplayName("a missing target is rejected up front")
    void rejectsBlankTarget() {
        assertThatThrownBy(() -> GrpcStep.unary(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
    }

    @Test
    @DisplayName("a missing deadline is rejected at build time (no unbounded calls)")
    void rejectsMissingDeadline() {
        assertThatThrownBy(() -> GrpcStep.unary("t").method("p.S/M").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    @DisplayName("a non-positive deadline is rejected")
    void rejectsNonPositiveDeadline() {
        assertThatThrownBy(() -> GrpcStep.unary("t").method("p.S/M").deadline(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("setting both request and requestFromResource is rejected")
    void rejectsRequestAndResource() {
        assertThatThrownBy(() -> GrpcStep.unary("t").method("p.S/M").withinSeconds(1).request("{}").requestFromResource("r.json").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("a secret-bearing metadata key is rejected inline")
    void rejectsSecretMetadataKey() {
        assertThatThrownBy(() -> GrpcStep.unary("t").metadata("Authorization", "Bearer x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("a wrapped api-key metadata name (x-api-key) is rejected as secret-bearing")
    void rejectsWrappedApiKeyMetadataKey() {
        assertThatThrownBy(() -> GrpcStep.unary("t").metadata("x-api-key", "k-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("an invalid gRPC metadata key name is rejected at build time")
    void rejectsInvalidMetadataKeyName() {
        assertThatThrownBy(() -> GrpcStep.unary("t").metadata("bad key", "v"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid gRPC metadata name");
    }

    @Test
    @DisplayName("optional request, metadata, assertions and captures default to absent/empty")
    void minimalStepHasEmptyCollections() {
        ScenarioStep step = GrpcStep.unary("t").method("p.S/M").withinSeconds(1).build();
        Map<String, Object> parameters = ((GenericStep) step).parameters();
        assertThat(parameters).doesNotContainKeys(GrpcStepParameters.REQUEST, GrpcStepParameters.REQUEST_RESOURCE);
        assertThat(parameters.get(GrpcStepParameters.METADATA)).isEqualTo(Map.of());
        assertThat(parameters.get(GrpcStepParameters.ASSERTIONS)).isEqualTo(List.of());
        assertThat(parameters.get(GrpcStepParameters.CAPTURES)).isEqualTo(List.of());
    }
}
