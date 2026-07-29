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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The evaluation corpus against the contract it says it satisfies.
 *
 * <p>{@code dataset/README.md} states that all fifteen cases validate against
 * {@code contracts/evaluation-case.schema.json}. Until this file nothing performed that validation —
 * a claim backed by nobody, which is the same shape of defect as the double-escaped forbidden
 * patterns fixed just before it: a check that is stated, believed and absent.
 *
 * <p>Beyond the schema this adds the two questions a JSON Schema cannot ask, because both are about
 * the filesystem rather than the document: does the case's {@code input.md} exist, and do the files a
 * {@code kbOverlay} promises to shadow exist. A case whose input is missing cannot be run, and it
 * would look perfectly valid.
 *
 * <p><strong>What this does not establish.</strong> Validity is form, not content. That the corpus
 * measures the right things remains unproven and will stay so until a batch runs over it at a
 * consumer; nothing here should be read as "the evaluation works". What is now true is narrower and
 * worth having: every case file is the shape the contract describes, and the README's numbers are
 * counted rather than remembered.
 */
class EvaluationCaseSchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private static final Path ROOT = locateEvaluationDir();

    /**
     * Loaded directly, with no envelope trick.
     *
     * <p>{@link KnowledgeBaseSchemaValidationTest} inlines the umbrella's {@code $defs} into a
     * synthetic wrapper because the thin KB schemas {@code $ref} each other by URI and networknt goes
     * to the network to resolve one. This schema contains no {@code $ref} at all, so there is nothing
     * to resolve and nothing to work around.
     */
    private static final JsonSchema SCHEMA = FACTORY.getSchema(readJson(ROOT.resolve("contracts/evaluation-case.schema.json")));

    private static Path locateEvaluationDir() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "agent-evaluation"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("docs/agent-evaluation not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static JsonNode readJson(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static JsonNode readYaml(Path file) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.valueToTree(yaml.load(in));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    /** Every case directory of the corpus, sorted — one case, one `case.yml`, one `input.md`. */
    private static List<Path> caseFiles() {
        Path cases = ROOT.resolve("dataset/cases");
        try (Stream<Path> walk = Files.walk(cases, 2)) {
            return walk.filter(path -> path.getFileName().toString().equals("case.yml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + cases, e);
        }
    }

    private static String joinMessages(Set<ValidationMessage> messages) {
        return messages.stream().map(ValidationMessage::toString).collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("the schema is a loadable 2020-12 document that rejects an empty case")
    void schema_isLoadableAndNotVacuous() {
        Set<ValidationMessage> messages = SCHEMA.validate(MAPPER.createObjectNode());

        assertThat(messages)
                .as("a schema that accepts {} would let every assertion below pass without checking anything")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every case validates against the contract the README says it satisfies")
    void everyCase_validates() {
        List<String> problems = new ArrayList<>();
        for (Path file : caseFiles()) {
            Set<ValidationMessage> messages = SCHEMA.validate(readYaml(file));
            if (!messages.isEmpty()) {
                problems.add(file.getParent().getFileName() + ":\n" + joinMessages(messages));
            }
        }

        assertThat(problems).as("each entry names the case and the field, so a failure points at a file rather than at 'the dataset'").isEmpty();
    }

    @Test
    @DisplayName("the corpus covers every category exactly once — that is what makes it a corpus rather than a pile")
    void categories_coverTheEnumExactly() {
        TreeSet<String> declared = new TreeSet<>();
        readJson(ROOT.resolve("contracts/evaluation-case.schema.json"))
                .path("properties").path("category").path("enum")
                .forEach(value -> declared.add(value.asText()));

        List<String> used = new ArrayList<>();
        caseFiles().forEach(file -> used.add(readYaml(file).path("category").asText()));

        assertThat(new TreeSet<>(used))
                .as("a category with no case is an untested failure mode; the README's promise is one case per category")
                .isEqualTo(declared);
        assertThat(used).as("two cases in one category would leave another silently uncovered").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the input a case names exists on disk — a schema cannot look, and a case with no input cannot run")
    void everyCase_hasItsInput() {
        List<String> missing = new ArrayList<>();
        for (Path file : caseFiles()) {
            String name = readYaml(file).path("input").path("file").asText();
            if (!Files.exists(file.getParent().resolve(name))) {
                missing.add(file.getParent().getFileName() + "/" + name);
            }
        }

        assertThat(missing).as("valid and unrunnable is the combination a schema alone cannot rule out").isEmpty();
    }

    @Test
    @DisplayName("the files a kbOverlay promises to shadow exist, and live inside the case")
    void kbOverlayFiles_exist() {
        List<String> problems = new ArrayList<>();
        for (Path file : caseFiles()) {
            JsonNode files = readYaml(file).path("kbOverlay").path("files");
            files.forEach(entry -> {
                String relative = entry.asText();
                if (!Files.exists(file.getParent().resolve(relative))) {
                    problems.add(file.getParent().getFileName() + ": " + relative + " не существует");
                } else if (!relative.startsWith("kb-overlay/")) {
                    problems.add(file.getParent().getFileName() + ": " + relative + " вне kb-overlay/ — подмена базы знаний должна жить внутри кейса");
                }
            });
        }

        assertThat(problems).as("an overlay pointing at a file that is not there shadows nothing, and the case measures nothing").isEmpty();
    }

    @Test
    @DisplayName("the one null failureReason is still there — it is the only value that exercises the schema's oneOf")
    void theOneOfBranch_isExercised() {
        List<String> nulls = new ArrayList<>();
        for (Path file : caseFiles()) {
            JsonNode expected = readYaml(file).path("expected");
            if (expected.has("failureReason") && expected.path("failureReason").isNull()) {
                nulls.add(file.getParent().getFileName().toString());
            }
        }

        assertThat(nulls)
                .as("the schema allows an enum value or null; with no case taking the null branch that half of the rule is written and never tried")
                .containsExactly("gap-missing-expected-value");
    }

    @Test
    @DisplayName("the numbers the README states are counted from the files, not remembered")
    void readmeCounts_areTrue() {
        List<Path> cases = caseFiles();
        int requirements = 0;
        int runs = 0;
        for (Path file : cases) {
            JsonNode document = readYaml(file);
            requirements += document.path("requirements").size();
            runs += document.path("repeats").asInt();
        }

        String readme = readText(ROOT.resolve("dataset/README.md"));
        assertThat(cases).as("README: «15 кейсов»").hasSize(15);
        assertThat(requirements).as("README: «Требований к покрытию — 33»").isEqualTo(33);
        assertThat(runs).as("README: «17 прогонов» — сумма repeats, а не число кейсов").isEqualTo(17);
        assertThat(readme).contains("15 кейсов", "33", "17");
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
