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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pins the SEPARATE, permissive KB-ingestion candidate schemas shipped in
 * {@code docs/ai-agent/knowledge-base/schema/} (source-document, extraction-candidate, business-flow,
 * business-rule, test-scenario-candidate, unresolved-item, conflict-item). These schemas are NEVER the
 * strict {@code stand-test-knowledge-base.schema.json}: they intentionally carry provenance, confidence
 * and partial/annotated extractions. This test guarantees each is a loadable 2020-12 JSON Schema, that
 * the worked example fixtures validate, and that a candidate missing its provenance, carrying an unknown
 * field, or smuggling an inline secret / URL is rejected for the intended reason.
 */
class KbCandidateSchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path KB_DIR = locateKnowledgeBaseDir();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final String FORBIDDEN_VALUE_SHAPES = "(://|jdbc:)|[Bb]earer\\s[A-Za-z0-9]|[Bb]asic\\s[A-Za-z0-9]|AKIA[0-9A-Z]{12}|eyJ[A-Za-z0-9]";

    private static Path locateKnowledgeBaseDir() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent", "knowledge-base"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("docs/ai-agent/knowledge-base not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static JsonSchema schema(String schemaFile) {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(KB_DIR.resolve(Paths.get("schema", schemaFile))));
            return FACTORY.getSchema(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read schema " + schemaFile, e);
        }
    }

    private static JsonNode readYamlResource(String resourcePath) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = KbCandidateSchemaValidationTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + resourcePath);
            }
            Object loaded = yaml.load(in);
            return MAPPER.valueToTree(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, e);
        }
    }

    private static String joinMessages(Set<ValidationMessage> messages) {
        return messages.stream().map(ValidationMessage::toString).collect(Collectors.joining("\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source-document", "extraction-candidate", "business-flow", "business-rule", "test-scenario-candidate", "unresolved-item", "conflict-item", "glossary", "review-decisions", "promotion-log"})
    @DisplayName("every candidate schema is a loadable 2020-12 JSON Schema that rejects an empty document")
    void candidateSchemas_loadAndRejectEmpty(String name) {
        JsonSchema loaded = schema(name + ".schema.json");
        Set<ValidationMessage> messages = loaded.validate(MAPPER.createObjectNode());
        assertThat(messages).as("an empty document matches no %s file shape", name).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "valid/source-document.yml, source-document.schema.json",
        "valid/extraction-candidates.yml, extraction-candidate.schema.json",
        "valid/business-flows.yml, business-flow.schema.json",
        "valid/business-rules.yml, business-rule.schema.json",
        "valid/test-scenarios.yml, test-scenario-candidate.schema.json",
        "valid/unresolved.yml, unresolved-item.schema.json",
        "valid/conflicts.yml, conflict-item.schema.json"
    })
    @DisplayName("every worked candidate example validates against its schema")
    void validExamples_pass(String fixture, String schemaFile) {
        Set<ValidationMessage> messages = schema(schemaFile).validate(readYamlResource("/kb-candidates/" + fixture));
        assertThat(messages).as("example %s should pass %s: %s", fixture, schemaFile, joinMessages(messages)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "candidate--missing-source.yml, source",
        "candidate--unknown-field.yml, madeUpField",
        "candidate--inline-secret.yml, quote",
        "candidate--url-in-quote.yml, quote"
    })
    @DisplayName("invalid candidates are rejected for the intended reason, not just any reason")
    void invalidCandidates_failForReason(String fixture, String expectedToken) {
        Set<ValidationMessage> messages = schema("extraction-candidate.schema.json").validate(readYamlResource("/kb-candidates/invalid/" + fixture));
        assertThat(messages).as("invalid candidate %s should be rejected", fixture).isNotEmpty();
        assertThat(joinMessages(messages)).as("invalid candidate %s should fail because of '%s'", fixture, expectedToken).contains(expectedToken);
    }

    @Test
    @DisplayName("no string in any valid candidate example carries a URL, JDBC or credential shape")
    void validExamples_carryNoSecretShapedStrings() {
        List<String> offenders = new ArrayList<>();
        for (String relativePath : List.of("valid/source-document.yml", "valid/extraction-candidates.yml", "valid/business-flows.yml", "valid/business-rules.yml", "valid/test-scenarios.yml", "valid/unresolved.yml", "valid/conflicts.yml")) {
            collectForbiddenStrings(readYamlResource("/kb-candidates/" + relativePath), relativePath, offenders);
        }
        assertThat(offenders).as("candidate examples must hold redacted, sterile text only").isEmpty();
    }

    private static JsonNode readYamlFile(Path file) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try {
            return MAPPER.valueToTree(yaml.load(Files.readString(file)));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read candidate file: " + file, e);
        }
    }

    /**
     * Maps a committed candidate file to its candidate schema by its top-level content key (robust to
     * per-document filename variation), or {@code null} when no candidate schema covers its shape.
     */
    private static String schemaForCandidateFile(JsonNode node) {
        // Distinctive keys first: review-decisions carries a `businessRules` OBJECT (not an array), so it
        // must be matched by its review metadata before the business-rule branch below.
        if (node.has("reviewedOn") || node.has("reviewedBy")) {
            return "review-decisions.schema.json";
        }
        if (node.has("promotions")) {
            return "promotion-log.schema.json";
        }
        if (node.has("abbreviations") || node.has("terms") || node.has("aliases")) {
            return "glossary.schema.json";
        }
        if (node.has("document")) {
            return "source-document.schema.json";
        }
        if (node.has("candidates")) {
            return "extraction-candidate.schema.json";
        }
        if (node.has("businessFlows")) {
            return "business-flow.schema.json";
        }
        if (node.has("businessRules")) {
            return "business-rule.schema.json";
        }
        if (node.has("testScenarios")) {
            return "test-scenario-candidate.schema.json";
        }
        if (node.has("unresolved")) {
            return "unresolved-item.schema.json";
        }
        if (node.has("conflicts")) {
            return "conflict-item.schema.json";
        }
        return null;
    }

    @Test
    @DisplayName("every committed candidate file under knowledge-base/candidates/** validates against its schema and is sterile")
    void committedCandidates_validateAndAreSterile() throws IOException {
        Path candidatesDir = KB_DIR.resolve("candidates");
        if (!Files.isDirectory(candidatesDir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(candidatesDir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    // The verbatim source tree is gitignored and not part of the schema contract.
                    .filter(path -> !path.toString().replace('\\', '/').contains("/_source/"))
                    .sorted()
                    .toList();
        }
        assertThat(files).as("the committed candidate tree must be non-empty (the staged pilot must be present and covered)").isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (Path file : files) {
            JsonNode node = readYamlFile(file);
            String schemaFile = schemaForCandidateFile(node);
            if (schemaFile == null) {
                problems.add(file + ": no candidate schema matches its top-level shape " + node.properties().stream().map(java.util.Map.Entry::getKey).toList());
                continue;
            }
            Set<ValidationMessage> messages = schema(schemaFile).validate(node);
            if (!messages.isEmpty()) {
                problems.add(file + " (" + schemaFile + "): " + joinMessages(messages));
            }
            List<String> offenders = new ArrayList<>();
            collectForbiddenStrings(node, file.toString(), offenders);
            offenders.forEach(offender -> problems.add("URL/JDBC/secret shape in " + offender));
        }
        assertThat(problems).as("committed candidates must validate against their candidate schema and carry no URL/JDBC/secret-shaped strings").isEmpty();
    }

    private static void collectForbiddenStrings(JsonNode node, String location, List<String> offenders) {
        if (node.isTextual() && node.asText().matches(".*(" + FORBIDDEN_VALUE_SHAPES + ").*")) {
            offenders.add(location + ": " + node.asText());
        }
        if (node.isObject()) {
            node.properties().forEach(field -> collectForbiddenStrings(field.getValue(), location + "/" + field.getKey(), offenders));
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectForbiddenStrings(item, location + "[]", offenders);
            }
        }
    }
}
