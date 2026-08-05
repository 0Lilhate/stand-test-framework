package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        assertThat(cases).as("README: «27 кейсов»").hasSize(27);
        assertThat(requirements).as("README: «Требований к покрытию — 104»").isEqualTo(104);
        assertThat(runs).as("README: «31 прогон» — сумма repeats, а не число кейсов").isEqualTo(31);
        assertThat(readme).contains("27 кейсов", "104", "31 прогон");
    }

    /**
     * The version-2 rules refuse what they say they refuse — checked by feeding them the violation.
     *
     * <p>A schema rule is worth exactly what its negative case proves. `schemaVersion` accepting two
     * values and an `if/then` tying the new sections to the second one would look identical, in every
     * green build, to a schema that accepts anything: all twenty-seven real cases are written correctly,
     * so none of them exercises the refusal. Each assertion below takes a real case, breaks one thing,
     * and requires the schema to notice.
     *
     * <p>The production one is not symmetry. `ui.environment` is an enum of `dev|ift` precisely so that
     * a corpus case cannot name a production stand, and a corpus case is the likeliest first source of a
     * run against production.
     */
    @Test
    @DisplayName("the version-2 rules refuse the documents they exist to refuse")
    void versionTwoRules_areNotVacuous() {
        JsonNode versionOne = readYaml(ROOT.resolve("dataset/cases/pos-showcase-format-200/case.yml"));
        JsonNode versionTwo = readYaml(ROOT.resolve("dataset/cases/ui-pos-support-request-registered/case.yml"));

        ObjectNode smuggledSection = versionOne.deepCopy();
        ((ObjectNode) smuggledSection.path("expected")).putObject("gates").putObject("compile").put("expected", "PASS");
        assertThat(SCHEMA.validate(smuggledSection))
                .as("a version-2 section inside a version-1 document is the drift this rule exists for")
                .isNotEmpty();

        ObjectNode smuggledUi = versionOne.deepCopy();
        smuggledUi.putObject("ui").put("application", "client-portal").put("environment", "ift").put("discoveryEvidence", "none");
        assertThat(SCHEMA.validate(smuggledUi)).as("the ui block is version 2 as much as the expected sections are").isNotEmpty();

        ObjectNode production = versionTwo.deepCopy();
        ((ObjectNode) production.path("ui")).put("environment", "prod");
        assertThat(SCHEMA.validate(production))
                .as("a production stand must not be expressible — a corpus case is the likeliest first source of a run against one")
                .isNotEmpty();

        ObjectNode unknownConstruct = versionTwo.deepCopy();
        ((ObjectNode) unknownConstruct.path("expected")).putArray("forbiddenConstructs").add("telepathy");
        assertThat(SCHEMA.validate(unknownConstruct)).as("a construct nobody defined cannot be forbidden by anybody").isNotEmpty();

        ObjectNode unknownArtifact = versionTwo.deepCopy();
        ((ObjectNode) unknownArtifact.path("expected").path("artifacts")).putArray("required").add("screenshot-diff");
        assertThat(SCHEMA.validate(unknownArtifact)).as("an artifact kind outside the list is a deliverable no stage produces").isNotEmpty();

        assertThat(SCHEMA.validate(versionOne)).as("and the unbroken originals still pass, or the four above prove nothing").isEmpty();
        assertThat(SCHEMA.validate(versionTwo)).isEmpty();
    }

    /** Cases of the UI branch — the ones the version-2 sections exist for. */
    private static List<Path> uiCaseFiles() {
        return caseFiles().stream().filter(file -> readYaml(file).path("category").asText().startsWith("ui-")).toList();
    }

    /**
     * The additive promise, checked rather than asserted in prose.
     *
     * <p>Format version 2 was added because eight of the twelve UI categories did not exist in a closed
     * enum, so a case of those categories could not be written at all. The backlog's acceptance criterion
     * for that work said the schema must not change, and its reason was compatibility — which survives
     * only while the change stays additive. So the check is the one that reason implies: the fifteen
     * documents written against version 1 still declare version 1 and still carry nothing from version 2.
     *
     * <p>A version-1 document that quietly grew a version-2 section would be the exact failure this
     * guards: the schema's `if/then` refuses it, and this makes sure no case tries.
     */
    @Test
    @DisplayName("version-1 cases stayed version 1 — the extension is additive, and that is checked")
    void versionOneCases_carryNoVersionTwoSection() {
        Set<String> versionTwoSections = Set.of("artifacts", "questions", "mandatoryChecks", "forbiddenConstructs", "gates");
        List<String> problems = new ArrayList<>();
        int versionOne = 0;
        for (Path file : caseFiles()) {
            JsonNode document = readYaml(file);
            if (document.path("schemaVersion").asInt() != 1) {
                continue;
            }
            versionOne++;
            if (document.has("ui")) {
                problems.add(file.getParent().getFileName() + ": блок ui при schemaVersion 1");
            }
            versionTwoSections.stream()
                    .filter(section -> document.path("expected").has(section))
                    .forEach(section -> problems.add(file.getParent().getFileName() + ": expected." + section + " при schemaVersion 1"));
        }

        assertThat(versionOne).as("the protocol corpus is the compatibility evidence; if it stopped being version 1 there would be nothing left to be compatible with").isEqualTo(15);
        assertThat(problems).isEmpty();
    }

    /**
     * Every UI case fixes all seven facets — the ones a UI generation is judged on.
     *
     * <p>They are optional in the schema because a protocol case has no use for them, which means nothing
     * but this file stops a UI case from shipping with three of the seven filled in. The facets are not
     * decoration: `artifacts.forbidden` is how "the correct outcome produced no test" is told apart from
     * "a plausible test was produced", and `gates` is how compiling is kept separate from being ready.
     */
    @Test
    @DisplayName("every UI case fixes all seven facets, including the three gate verdicts")
    void uiCases_declareEveryFacet() {
        List<String> problems = new ArrayList<>();
        for (Path file : uiCaseFiles()) {
            JsonNode document = readYaml(file);
            String name = file.getParent().getFileName().toString();
            if (!document.has("ui")) {
                problems.add(name + ": UI-категория без блока ui");
            }
            for (String facet : List.of("artifacts", "questions", "mandatoryChecks", "forbiddenConstructs", "gates")) {
                if (!document.path("expected").has(facet)) {
                    problems.add(name + ": нет expected." + facet);
                }
            }
            for (String gate : List.of("compile", "safety", "quality")) {
                if (!document.path("expected").path("gates").has(gate)) {
                    problems.add(name + ": нет expected.gates." + gate);
                }
            }
            JsonNode track = document.path("expected").path("plan").path("track");
            if (!track.isMissingNode() && !"JAVA_DSL".equals(track.asText())) {
                problems.add(name + ": трек " + track.asText() + " — у ui.* нет декларативного формата");
            }
        }

        assertThat(uiCaseFiles()).as("twelve categories were asked for; a missing one is an untested failure mode").hasSize(12);
        assertThat(problems).isEmpty();
    }

    /**
     * The seeded artifacts a case names exist — the same filesystem question `kbOverlay` already answers.
     *
     * <p>Nothing checked this before, and the UI branch made it load-bearing: six cases hand the agent a
     * discovery report, and that report IS the DOM for the run. A case pointing at a report that is not
     * there would not fail loudly — it would look like a case whose screen has no elements, and every
     * locator the run then wrote would be, correctly, unsourced.
     */
    @Test
    @DisplayName("the seeded artifacts a case hands the agent exist, and live inside the case")
    void seededArtifacts_exist() {
        List<String> problems = new ArrayList<>();
        int seeded = 0;
        for (Path file : caseFiles()) {
            for (JsonNode entry : readYaml(file).path("input").path("seededArtifacts")) {
                seeded++;
                String relative = entry.asText();
                if (!Files.exists(file.getParent().resolve(relative))) {
                    problems.add(file.getParent().getFileName() + ": " + relative + " не существует");
                } else if (!relative.startsWith("seeded/")) {
                    problems.add(file.getParent().getFileName() + ": " + relative + " вне seeded/");
                }
            }
        }

        assertThat(seeded).as("an empty sweep would pass this vacuously").isEqualTo(8);
        assertThat(problems).isEmpty();
    }

    /**
     * The other direction: a case that SHIPS a seeded artifact says so.
     *
     * <p>This is the check that was missing, and its absence had already cost something small — both
     * protocol cases carried a `seeded/` directory the README describes and the `case.yml` never
     * mentioned, so the artifact reached the agent by the runner copying the directory rather than by
     * the case declaring it. Undeclared input is input nobody can account for when a result surprises.
     */
    @Test
    @DisplayName("a case that ships a seeded/ directory declares what is in it")
    void seededDirectories_areDeclared() {
        List<String> undeclared = new ArrayList<>();
        for (Path file : caseFiles()) {
            Path directory = file.getParent().resolve("seeded");
            if (!Files.isDirectory(directory)) {
                continue;
            }
            Set<String> declared = new TreeSet<>();
            readYaml(file).path("input").path("seededArtifacts").forEach(entry -> declared.add(entry.asText()));
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(Files::isRegularFile)
                        .map(path -> "seeded/" + directory.relativize(path))
                        .filter(relative -> !declared.contains(relative))
                        .forEach(relative -> undeclared.add(file.getParent().getFileName() + ": " + relative));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + directory, e);
            }
        }

        assertThat(undeclared).as("an artifact handed to the agent and named nowhere is an input the result cannot be explained by").isEmpty();
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }
}
