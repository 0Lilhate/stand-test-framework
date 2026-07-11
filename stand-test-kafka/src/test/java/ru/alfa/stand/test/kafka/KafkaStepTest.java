package ru.alfa.stand.test.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class KafkaStepTest {

    private static Map<String, Object> parameters(ScenarioStep step) {
        return ((GenericStep) step).parameters();
    }

    @Test
    @DisplayName("send builds a kafka.send step with topic, key, headers, body and inject flag")
    void sendBuildsStep() {
        ScenarioStep step = KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS)
                .body("{\"a\":1}")
                .key("k-1")
                .header("trace", "abc")
                .injectCorrelationId()
                .build();

        assertThat(step.type()).isEqualTo("kafka.send");
        assertThat(step.id()).isEqualTo("SEND " + KafkaTestSupport.REQUEST_ALIAS);
        Map<String, Object> parameters = parameters(step);
        assertThat(parameters).containsEntry(KafkaStepParameters.TOPIC, KafkaTestSupport.REQUEST_ALIAS)
                .containsEntry(KafkaStepParameters.BODY, "{\"a\":1}")
                .containsEntry(KafkaStepParameters.KEY, "k-1")
                .containsEntry(KafkaStepParameters.INJECT_CORRELATION_ID, true);
        assertThat(parameters.get(KafkaStepParameters.HEADERS)).isEqualTo(Map.of("trace", "abc"));
    }

    @Test
    @DisplayName("expect builds a kafka.expect step with selection, timeout, assertions and captures")
    void expectBuildsStep() {
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS)
                .correlationIdFromContext()
                .withinSeconds(15)
                .pollTimeout(Duration.ofMillis(250))
                .key("k-2")
                .assertPath("$.status", "SUCCESS")
                .capture("entityId", "$.entityId")
                .build();

        assertThat(step.type()).isEqualTo("kafka.expect");
        Map<String, Object> parameters = parameters(step);
        assertThat(parameters).containsEntry(KafkaStepParameters.CORRELATION_FROM_CONTEXT, true)
                .containsEntry(KafkaStepParameters.TIMEOUT_MILLIS, 15_000L)
                .containsEntry(KafkaStepParameters.POLL_TIMEOUT_MILLIS, 250L)
                .containsEntry(KafkaStepParameters.KEY, "k-2");
        assertThat(KafkaStepParameters.assertions(parameters)).containsExactly(new KafkaAssertion("$.status", "SUCCESS"));
        assertThat(KafkaStepParameters.captures(parameters)).containsExactly(new KafkaCapture("entityId", "$.entityId"));
    }

    @Test
    @DisplayName("an explicit id overrides the derived one")
    void explicitId() {
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().id("wait-for-response").build();
        assertThat(step.id()).isEqualTo("wait-for-response");
    }

    @Test
    @DisplayName("an expect without a per-run discriminator is rejected at build time (parallel-safety, plan §15)")
    void expectWithoutDiscriminatorRejected() {
        assertThatThrownBy(() -> KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).assertPath("$.status", "SUCCESS").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-run discriminator");
    }

    @Test
    @DisplayName("a per-run-derived key (with a ${...} placeholder) alone satisfies the discriminator requirement")
    void expectWithPerRunKeyBuilds() {
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).key("${testRunId}").build();
        assertThat(step.type()).isEqualTo("kafka.expect");
    }

    @Test
    @DisplayName("an expect whose sole discriminator is a CONSTANT key is rejected (not per-run-unique, plan §15)")
    void expectWithConstantKeyRejected() {
        assertThatThrownBy(() -> KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).key("constant").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("per-run-derived");
    }

    @Test
    @DisplayName("a constant key is allowed when correlationIdFromContext already isolates per run")
    void expectWithConstantKeyAllowedAlongsideCorrelation() {
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().key("constant").build();
        assertThat(step.type()).isEqualTo("kafka.expect");
    }

    @Test
    @DisplayName("setting both body and bodyFromResource is rejected")
    void bodyAndResourceRejected() {
        assertThatThrownBy(() -> KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").bodyFromResource("fixtures/event.json").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("a kafka.send without a body is rejected at build time")
    void sendWithoutBodyRejected() {
        assertThatThrownBy(() -> KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires body");
    }

    @Test
    @DisplayName("expect-only options on a send step are rejected")
    void expectOptionsOnSendRejected() {
        assertThatThrownBy(() -> KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").assertPath("$.x", 1).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka.expect");
        assertThatThrownBy(() -> KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").correlationIdFromContext().build())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KafkaStep.send(KafkaTestSupport.REQUEST_ALIAS).body("{}").withinSeconds(5).build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("send-only options on an expect step are rejected")
    void sendOptionsOnExpectRejected() {
        assertThatThrownBy(() -> KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).body("{}").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka.send");
        assertThatThrownBy(() -> KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).injectCorrelationId().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("blank topic, non-positive timeout and poll timeout are rejected")
    void invalidArguments() {
        assertThatThrownBy(() -> KafkaStep.send(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).within(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).pollTimeout(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("expect without assertions/captures still builds with empty lists")
    void expectWithoutAssertions() {
        ScenarioStep step = KafkaStep.expect(KafkaTestSupport.RESPONSE_ALIAS).correlationIdFromContext().build();
        Map<String, Object> parameters = parameters(step);
        assertThat((List<?>) parameters.get(KafkaStepParameters.ASSERTIONS)).isEmpty();
        assertThat((List<?>) parameters.get(KafkaStepParameters.CAPTURES)).isEmpty();
    }
}
