package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.scenario.AiScenarioParser;

/**
 * Parity proof for the AI-format engine: a scenario document in the steps/type format is accepted by
 * {@code AiScenarioParser}, becomes a valid core {@link Scenario} with the expected wire keys, and a
 * document violating the guardrails is rejected before it can run. Structural only — no live stand is
 * contacted.
 *
 * <p>This test used to close a three-link loop schema-accepts → parser-accepts → runnable model against
 * the JSON Schema shipped by the former {@code stand-test-ai-schema} module. That module was removed
 * deliberately, so the first link is gone: the parser is now the only gate the AI format has before the
 * runtime validator, and a malformed document is caught at parse time rather than by a pre-flight schema
 * pass.
 */
class AiDocumentParityTest {

    private static final String DOCUMENT = "/ai/canonical-flow.json";
    private static final String GRPC_DOCUMENT = "/ai/grpc-flow.json";
    private static final String INVALID_DOCUMENT = "/ai/invalid-flow.json";

    private static String readDocument(String path) {
        try (InputStream in = AiDocumentParityTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing parity document: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    @Test
    @DisplayName("the canonical document parses into a validator-clean Scenario with the expected wire keys")
    void document_parsesToRunnableScenario() {
        Scenario scenario = new AiScenarioParser().parse(readDocument(DOCUMENT));

        assertThatCode(() -> new DefaultScenarioValidator().validate(scenario).throwIfInvalid())
                .doesNotThrowAnyException();
        assertThat(scenario.environment()).isEqualTo("ift");
        assertThat(scenario.steps()).hasSize(4);

        Map<String, Object> rest = ((GenericStep) scenario.steps().get(0)).parameters();
        assertThat(rest).containsEntry("method", "POST").containsEntry("expectedStatus", 200)
                .containsEntry("injectCorrelationId", true).containsEntry("bodyResource", "fixtures/create-request.json");
        assertThat(rest.get("assertions")).isEqualTo(java.util.List.of(
                Map.of("jsonPath", "$.status", "expectedValue", "ACCEPTED"),
                Map.of("jsonPath", "$.requestId", "expectedValue", "r-[0-9]+", "matcher", "MATCHES"),
                Map.of("jsonPath", "$.error", "expectedValue", false, "matcher", "EXISTS")));

        Map<String, Object> poll = ((GenericStep) scenario.steps().get(1)).parameters();
        assertThat(scenario.steps().get(1).type()).isEqualTo("rest.expectEventually");
        assertThat(poll).containsEntry("method", "GET").containsEntry("timeoutMillis", 20000L).containsEntry("expectedStatus", 200);
        assertThat(poll.get("assertions")).isEqualTo(java.util.List.of(Map.of("jsonPath", "$.status", "expectedValue", "DONE")));

        Map<String, Object> kafka = ((GenericStep) scenario.steps().get(2)).parameters();
        assertThat(kafka).containsEntry("correlationIdFromContext", true).containsEntry("timeoutMillis", 30000L);

        Map<String, Object> db = ((GenericStep) scenario.steps().get(3)).parameters();
        assertThat(db).containsEntry("expectedValue", "DONE").containsEntry("timeoutMillis", 10000L);
        assertThat((String) db.get("sql")).startsWith("SELECT");
    }

    @Test
    @DisplayName("a document violating the guardrails is rejected by the parser, not deferred to the run")
    void invalidDocument_isRejectedAtParseTime() {
        // The document's first step is a destructive `db.delete` — a step type the AI format does not
        // have. The parser fails closed on it, so the two later violations (a URL where a logical alias
        // belongs, an unbounded timeout) never get the chance to reach a stand.
        assertThatThrownBy(() -> new AiScenarioParser().parse(readDocument(INVALID_DOCUMENT)))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("db.delete");
    }

    @Test
    @DisplayName("the grpc.unary document parses into a validator-clean Scenario with the expected wire keys")
    void grpcDocument_parsesToRunnableScenario() {
        Scenario scenario = new AiScenarioParser().parse(readDocument(GRPC_DOCUMENT));

        assertThatCode(() -> new DefaultScenarioValidator().validate(scenario).throwIfInvalid())
                .doesNotThrowAnyException();
        assertThat(scenario.steps()).hasSize(1);

        Map<String, Object> grpc = ((GenericStep) scenario.steps().get(0)).parameters();
        assertThat(grpc)
                .containsEntry("target", "billing-grpc")
                .containsEntry("methodFullName", "billing.BillingService/Charge")
                .containsEntry("deadlineMillis", 5000L)
                .containsEntry("injectCorrelationId", true)
                .containsEntry("requestResource", "fixtures/charge-request.json");
        assertThat(grpc.get("assertions")).isEqualTo(java.util.List.of(Map.of("jsonPath", "$.status", "expectedValue", "OK")));
        assertThat(grpc.get("captures")).isEqualTo(java.util.List.of(Map.of("variableName", "chargeId", "jsonPath", "$.chargeId")));
    }
}
