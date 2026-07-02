package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScenarioSchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema SCHEMA = loadSchema();

    private static JsonSchema loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(readResource(AiSchemaResources.SCHEMA_RESOURCE));
    }

    private static String readResource(String path) {
        try (InputStream in = ScenarioSchemaValidationTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + path, e);
        }
    }

    private static Set<ValidationMessage> validateExample(String examplePath) {
        try (InputStream in = ScenarioSchemaValidationTest.class.getResourceAsStream(examplePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing example: " + examplePath);
            }
            JsonNode node = MAPPER.readTree(in);
            return SCHEMA.validate(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read example: " + examplePath, e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest-kafka-db-flow.json", "kafka-response-flow.json"})
    @DisplayName("valid examples pass schema validation with no messages")
    void validExamples_pass(String file) {
        Set<ValidationMessage> messages = validateExample("/examples/valid/" + file);
        assertThat(messages).as("valid example %s should produce no validation messages", file).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "arbitrary-url.json, url",
        "inline-secret.json, Authorization",
        "missing-timeout.json, timeout",
        "destructive-sql.json, query",
        "unknown-step-type.json, type"
    })
    @DisplayName("invalid examples are rejected for the intended reason, not just any reason")
    void invalidExamples_failForReason(String file, String expectedToken) {
        Set<ValidationMessage> messages = validateExample("/examples/invalid/" + file);
        assertThat(messages).as("invalid example %s should be rejected", file).isNotEmpty();
        String joined = messages.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n"));
        assertThat(joined).as("invalid example %s should fail because of '%s'", file, expectedToken).contains(expectedToken);
    }
}
