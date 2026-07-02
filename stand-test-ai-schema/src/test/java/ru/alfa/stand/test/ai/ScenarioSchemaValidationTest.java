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
import org.junit.jupiter.api.Test;
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

    private static Set<ValidationMessage> validateJson(String json) {
        try {
            return SCHEMA.validate(MAPPER.readTree(json));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse JSON", e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rest-kafka-db-flow.json", "kafka-response-flow.json", "rest-get-flow.json", "grpc-unary-draft.json", "rest-query-and-assert-flow.json"})
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
        "unknown-step-type.json, type",
        "kafka-send-missing-payload.json, payload",
        "direct-broker.json, bootstrapServers",
        "jdbc-url.json, datasource",
        "script-assertion.json, script",
        "unbounded-timeout.json, timeout",
        "invalid-variable-capture.json, capture"
    })
    @DisplayName("invalid examples are rejected for the intended reason, not just any reason")
    void invalidExamples_failForReason(String file, String expectedToken) {
        Set<ValidationMessage> messages = validateExample("/examples/invalid/" + file);
        assertThat(messages).as("invalid example %s should be rejected", file).isNotEmpty();
        String joined = messages.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n"));
        assertThat(joined).as("invalid example %s should fail because of '%s'", file, expectedToken).contains(expectedToken);
    }

    @Test
    @DisplayName("a capture value that is not a JSONPath is rejected (G6)")
    void nonJsonPathCapture_rejected() {
        String doc = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.get\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"capture\":{\"x\":\"not a jsonpath\"}}]}";
        assertThat(validateJson(doc)).as("capture value without a leading $ should be rejected").isNotEmpty();
    }

    @Test
    @DisplayName("a step id that breaks the identifier pattern is rejected (consistency)")
    void malformedStepId_rejected() {
        String doc = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"bad id\",\"type\":\"rest.get\","
                + "\"service\":\"svc\",\"path\":\"/a\"}]}";
        assertThat(validateJson(doc)).as("a step id with a space should be rejected").isNotEmpty();
    }

    @Test
    @DisplayName("a rest.get carrying a body is rejected (G7)")
    void restGetWithBody_rejected() {
        String doc = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.get\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"body\":{\"fixture\":\"fixtures/x.json\"}}]}";
        assertThat(validateJson(doc)).as("rest.get with a body should be rejected").isNotEmpty();
    }

    @Test
    @DisplayName("F1: rest.post may carry response-body assertions; a script matcher is rejected")
    void restAssert() {
        String withAssert = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.post\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"expect\":{\"status\":200},"
                + "\"assert\":[{\"path\":\"$.status\",\"equals\":\"OK\"}]}]}";
        assertThat(validateJson(withAssert)).as("rest.post with a declarative assert should pass").isEmpty();

        String scriptAssert = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.post\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"expect\":{\"status\":200},"
                + "\"assert\":[{\"path\":\"$.status\",\"script\":\"x\"}]}]}";
        assertThat(validateJson(scriptAssert)).as("rest assert with a script matcher should be rejected").isNotEmpty();
    }

    @Test
    @DisplayName("F2: rest.get may carry a string->string query map; a non-string value is rejected")
    void restQuery() {
        String withQuery = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.get\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"query\":{\"status\":\"NEW\"}}]}";
        assertThat(validateJson(withQuery)).as("rest.get with a string query map should pass").isEmpty();

        String numericQuery = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.get\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"query\":{\"page\":1}}]}";
        assertThat(validateJson(numericQuery)).as("a non-string query value should be rejected").isNotEmpty();
    }

    @Test
    @DisplayName("F3: kafka.send without a payload is rejected; with a payload it passes")
    void kafkaSendPayloadRequired() {
        String noPayload = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"kafka.send\","
                + "\"topic\":\"t\"}]}";
        assertThat(validateJson(noPayload)).as("kafka.send without payload should be rejected").isNotEmpty();

        String withPayload = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"kafka.send\","
                + "\"topic\":\"t\",\"payload\":{\"fixture\":\"fixtures/c.json\"}}]}";
        assertThat(validateJson(withPayload)).as("kafka.send with a fixture payload should pass").isEmpty();
    }

    @Test
    @DisplayName("an invalid step reports only its own type's errors, not every step type (T3)")
    void invalidStep_reportsFocusedErrors() {
        String doc = "{\"id\":\"f\",\"environment\":\"ift\",\"steps\":[{\"id\":\"s\",\"type\":\"rest.post\","
                + "\"service\":\"svc\",\"path\":\"/a\",\"expect\":{\"status\":200},\"bogus\":1}]}";
        Set<ValidationMessage> messages = validateJson(doc);
        String joined = messages.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n"));

        assertThat(messages).isNotEmpty();
        assertThat(joined).contains("bogus");
        assertThat(joined)
                .doesNotContain("kafka.send")
                .doesNotContain("kafka.expect")
                .doesNotContain("db.expectEventually")
                .doesNotContain("grpc.unary");
    }
}
