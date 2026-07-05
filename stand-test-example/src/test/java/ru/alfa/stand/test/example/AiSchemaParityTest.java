package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.ai.AiSchemaResources;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.scenario.AiScenarioParser;

/**
 * Parity proof for the AI-format guardrail: a scenario document that passes the JSON Schema shipped by
 * {@code stand-test-ai-schema} is also accepted by {@code AiScenarioParser} and becomes a valid core
 * {@link Scenario}. This is the single test that closes the loop schema-accepts → parser-accepts →
 * runnable model, so the ai-schema and the runtime engine cannot drift apart silently. Structural only —
 * no live stand is contacted.
 */
class AiSchemaParityTest {

    private static final String DOCUMENT = "/ai/canonical-flow.json";
    private static final String GRPC_DOCUMENT = "/ai/grpc-flow.json";
    private static final String INVALID_DOCUMENT = "/ai/invalid-flow.json";

    private static String readDocument(String path) {
        try (InputStream in = AiSchemaParityTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing parity document: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static Set<ValidationMessage> validateAgainstSchema(String path) {
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(AiSchemaResources.scenarioSchemaJson());
            JsonNode node = new ObjectMapper().readTree(readDocument(path));
            return schema.validate(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate " + path, e);
        }
    }

    @Test
    @DisplayName("the canonical document passes the ai-schema JSON Schema")
    void document_passesSchema() {
        assertThat(validateAgainstSchema(DOCUMENT)).as("schema validation messages").isEmpty();
    }

    @Test
    @DisplayName("the same document parses into a validator-clean Scenario with the expected wire keys")
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
    @DisplayName("a document violating the guardrails is rejected by the ai-schema JSON Schema")
    void invalidDocument_failsSchema() {
        Set<ValidationMessage> messages = validateAgainstSchema(INVALID_DOCUMENT);

        // Three independent guardrails must each produce a message anchored at its own step: the unknown
        // (destructive) step type, the URL where a logical alias is required, and the timeout whose unit
        // the duration format does not allow (only ms|s|m).
        assertThat(messages).as("schema validation messages").isNotEmpty();
        String rendered = messages.toString();
        assertThat(rendered).contains("$.steps[0].type");
        assertThat(rendered).contains("$.steps[1].service");
        assertThat(rendered).contains("$.steps[2].timeout");
    }

    @Test
    @DisplayName("the grpc.unary document passes the ai-schema JSON Schema")
    void grpcDocument_passesSchema() {
        assertThat(validateAgainstSchema(GRPC_DOCUMENT)).as("schema validation messages").isEmpty();
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
