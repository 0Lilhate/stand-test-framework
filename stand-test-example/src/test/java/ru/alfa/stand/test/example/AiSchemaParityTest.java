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

    private static String readDocument() {
        try (InputStream in = AiSchemaParityTest.class.getResourceAsStream(DOCUMENT)) {
            if (in == null) {
                throw new IllegalStateException("Missing parity document: " + DOCUMENT);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read " + DOCUMENT, e);
        }
    }

    @Test
    @DisplayName("the canonical document passes the ai-schema JSON Schema")
    void document_passesSchema() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(AiSchemaResources.scenarioSchemaJson());
        JsonNode node = new ObjectMapper().readTree(readDocument());
        Set<ValidationMessage> messages = schema.validate(node);
        assertThat(messages).as("schema validation messages").isEmpty();
    }

    @Test
    @DisplayName("the same document parses into a validator-clean Scenario with the expected wire keys")
    void document_parsesToRunnableScenario() {
        Scenario scenario = new AiScenarioParser().parse(readDocument());

        assertThatCode(() -> new DefaultScenarioValidator().validate(scenario).throwIfInvalid())
                .doesNotThrowAnyException();
        assertThat(scenario.environment()).isEqualTo("ift");
        assertThat(scenario.steps()).hasSize(3);

        Map<String, Object> rest = ((GenericStep) scenario.steps().get(0)).parameters();
        assertThat(rest).containsEntry("method", "POST").containsEntry("expectedStatus", 200)
                .containsEntry("injectCorrelationId", true).containsEntry("bodyResource", "fixtures/create-request.json");

        Map<String, Object> kafka = ((GenericStep) scenario.steps().get(1)).parameters();
        assertThat(kafka).containsEntry("correlationIdFromContext", true).containsEntry("timeoutMillis", 30000L);

        Map<String, Object> db = ((GenericStep) scenario.steps().get(2)).parameters();
        assertThat(db).containsEntry("expectedValue", "DONE").containsEntry("timeoutMillis", 10000L);
        assertThat((String) db.get("sql")).startsWith("SELECT");
    }
}
