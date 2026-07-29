package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resolves the {@code outputSchema} a prompt declares to a file, and checks that the file is a schema.
 *
 * <p>It lives beside the other bundle tests because all three referenced schemas now live in this
 * module. It was hosted elsewhere while one of them sat in an agent module and an SDK test resolving
 * a path into it would have been the wrong direction; that module is gone and the split it forced
 * with it.
 *
 * <p>The division of labour with {@code PromptFrontmatterRequiredKeysTest} survives and is worth
 * keeping: that one owns the SHAPE of the value (a safe relative path, declared by the prompts that
 * must declare one), this one owns whether the value points at anything. Without the second half,
 * {@code outputSchema} is a promise — a reader of the prompt is told the output has a machine-checkable
 * contract, and the path that was supposed to carry it resolves to nothing.
 */
class PromptOutputSchemaResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

    /** Three prompts declare a schema today, in each of the two bundle copies. */
    private static final int EXPECTED_REFERENCES = 6;

    private static Path locateRepositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /** The {@code outputSchema} value of every prompt that declares one, keyed by the prompt's path. */
    private static TreeMap<String, String> declaredReferences() {
        Path bundleRoot = REPOSITORY_ROOT.resolve(Paths.get("docs", "ai-agent"));
        TreeMap<String, String> declared = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(bundleRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> {
                        String reference = frontmatterValue(read(path), "outputSchema");
                        if (!reference.isEmpty()) {
                            declared.put(bundleRoot.relativize(path).toString().replace('\\', '/'), reference);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + bundleRoot, e);
        }
        return declared;
    }

    /** Reads one scalar key out of a leading frontmatter block, empty when absent or unterminated. */
    private static String frontmatterValue(String document, String key) {
        List<String> lines = document.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            return "";
        }
        String found = "";
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("---".equals(line.strip())) {
                return found;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && key.equals(line.substring(0, colon).strip())) {
                found = line.substring(colon + 1).strip();
            }
        }
        return "";
    }

    @Test
    @DisplayName("every declared outputSchema resolves to a file that is a valid JSON Schema 2020-12")
    void declaredSchemas_existAndAreValid() {
        JsonSchema metaSchema = FACTORY.getSchema(SchemaLocation.of(SpecVersion.VersionFlag.V202012.getId()));
        TreeMap<String, String> declared = declaredReferences();
        List<String> problems = new ArrayList<>();

        declared.forEach((prompt, reference) -> {
            Path resolved = REPOSITORY_ROOT.resolve(reference);
            if (!Files.isRegularFile(resolved)) {
                problems.add(prompt + " → " + reference + ": no such file");
                return;
            }
            JsonNode schema;
            try {
                schema = MAPPER.readTree(read(resolved));
            } catch (IOException e) {
                problems.add(prompt + " → " + reference + ": not valid JSON (" + e.getMessage() + ")");
                return;
            }
            Set<ValidationMessage> violations = metaSchema.validate(schema);
            if (!violations.isEmpty()) {
                problems.add(prompt + " → " + reference + ": " + violations.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("; ")));
            }
        });

        assertThat(problems).as("a prompt promising a schema that does not resolve turns the structured-output contract into a runtime surprise").isEmpty();
        assertThat(declared).as("three prompts declare a schema in each of the two copies; a smaller count means the bundle resolved wrongly and this test passed by finding nothing").hasSize(EXPECTED_REFERENCES);
    }

    @Test
    @DisplayName("both bundle copies point the same prompt at the same schema")
    void bothCopies_referenceTheSameSchema() {
        TreeMap<String, String> declared = declaredReferences();
        List<String> mismatched = new ArrayList<>();

        declared.forEach((prompt, reference) -> {
            if (!prompt.startsWith(".claude/")) {
                return;
            }
            String twin = prompt.replaceFirst("^\\.claude/", ".opencode/");
            String other = declared.get(twin);
            if (other == null) {
                mismatched.add(prompt + ": the .opencode copy declares no outputSchema");
            } else if (!reference.equals(other)) {
                mismatched.add(prompt + ": .claude → " + reference + ", .opencode → " + other);
            }
        });

        assertThat(mismatched).as("the schema is a property of the prompt, not of the copy it was installed from — a per-copy reference would make the registry answer differently depending on which bundle a consumer took").isEmpty();
    }
}
