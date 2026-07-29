package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Requires every shipped prompt — skill, command, rule and workflow — to declare the frontmatter keys
 * its category depends on.
 *
 * <p>{@code version} is the key the migration introduced. Without it a generated test cannot be tied
 * to the instruction that produced it: analysis A-07 found {@code grep '^version:'} over the whole kit
 * returning nothing, which makes a quality regression in a prompt undetectable and an A/B comparison
 * impossible. ADR-0006 answers with a version the author increments deliberately — on a change of
 * meaning or of the response contract, not on a typo fix — alongside a content hash that catches the
 * edits where the discipline slips.
 *
 * <p>Coverage has to be total rather than convenient. {@code promptSetVersion} claims "these runs were
 * produced by this set of prompts"; a single unversioned file makes the claim false while leaving it
 * looking true, and the files that were unversioned longest were the two auto-loaded RULES that define
 * the mandatory stage order.
 *
 * <p>The existing keys are checked alongside it, because this test is the mechanical half of risk
 * R-26: an edit to a frontmatter block is exactly how a prompt silently stops being loaded. The other
 * half stays human — no machine here can confirm that Claude Code or opencode still picks the file up.
 */
class PromptFrontmatterRequiredKeysTest {

    /** Both shipped copies of the same bundle. A key added to one only is drift, not a migration. */
    private static final List<String> BUNDLES = List.of(".claude", ".opencode");

    /**
     * The frontmatter keys each category of prompt must declare. They differ because the categories
     * arrived with different shapes and this test describes what IS, not what would be tidy: a skill
     * is addressed by {@code name}, a command by its filename, and a rule is injected verbatim into
     * every context — giving rules a {@code description} would put machine metadata into instruction
     * text nobody asked to read.
     *
     * <p>{@code version} is the one key common to all four: {@code promptSetVersion} covers the whole
     * set or it means nothing.
     */
    private static final Map<String, List<String>> REQUIRED_KEYS = Map.of(
            "skills", List.of("name", "description", "version"),
            "commands", List.of("description", "version"),
            "rules", List.of("version"),
            "workflows", List.of("version"),
            "agents", List.of("name", "description", "version"));

    /**
     * Categories that exist in the Claude copy alone, because they are Claude Code mechanisms.
     *
     * <p>Named rather than inferred from what happens to be on disk: a category that vanished from a
     * copy would otherwise be indistinguishable from one that never belonged there, and the whole
     * point of walking both copies is to notice a disappearance.
     */
    private static final Set<String> CLAUDE_ONLY_CATEGORIES = Set.of("agents");

    /** A version is a positive integer with no leading zero: {@code 1}, not {@code 0}, {@code v1} or {@code 1.0}. */
    private static final Pattern VERSION = Pattern.compile("^[1-9][0-9]*$");

    /**
     * The prompts whose response the orchestrator parses, and therefore the only ones carrying
     * {@code outputSchema}. Pinned as a set rather than described in prose so that giving a fourth
     * prompt a schema — or taking one away — is a deliberate edit here and not a quiet drift.
     *
     * <p>Absent on purpose: every prompt whose output a human reads ({@code case-analysis},
     * {@code test-review}, {@code environment-mapping}, {@code safety-review}, the KB pipeline) and
     * {@code java-dsl-authoring}, whose output is Java source checked by a compiler — there is no JSON
     * schema for a test class. The fourth LLM slot of the MVP, {@code repair}, has no prompt in the
     * bundle at all: it is written in TASK-074.
     */
    private static final List<String> PROMPTS_WITH_OUTPUT_SCHEMA = List.of(
            "skills/stand-test-scenario-design/SKILL.md",
            "skills/stand-test-yaml-authoring/SKILL.md",
            "skills/stand-test-debugging/SKILL.md");

    /**
     * A schema reference is a path from the REPOSITORY ROOT — the schemas live in two different
     * modules, and a path relative to the prompt would have to climb out of the bundle to reach
     * either. Absolute paths and {@code ..} segments are refused: the first ties the bundle to one
     * machine, the second lets a reference escape the repository once the bundle is copied elsewhere.
     */
    private static final Pattern SCHEMA_REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]*\\.schema\\.json$");

    /**
     * Thirty-two prompts per copy: 16 skills, 12 commands, 2 rules, 2 workflows. Stated as a floor so
     * a wrongly resolved bundle root cannot pass by finding nothing and comparing an empty set
     * against itself.
     */
    private static final int PROMPTS_PER_BUNDLE = 32;

    private static Path aiAgentRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("docs/ai-agent not found upwards from " + Paths.get("").toAbsolutePath());
    }

    /**
     * Every prompt file of both copies, keyed by its path relative to {@code docs/ai-agent}.
     *
     * <p>Under {@code skills/} only {@code SKILL.md} is a prompt — the colocated templates, examples
     * and checklists are assets the skill points at, and giving them frontmatter would version
     * documents nobody addresses. Everywhere else every {@code .md} is a prompt.
     */
    private static TreeMap<String, Path> promptFiles() {
        Path root = aiAgentRoot();
        TreeMap<String, Path> found = new TreeMap<>();
        for (String bundle : BUNDLES) {
            for (String category : REQUIRED_KEYS.keySet()) {
                if (CLAUDE_ONLY_CATEGORIES.contains(category) && !".claude".equals(bundle)) {
                    continue;
                }
                Path directory = root.resolve(bundle).resolve(category);
                try (Stream<Path> walk = Files.walk(directory)) {
                    walk.filter(Files::isRegularFile)
                            .filter(path -> isPrompt(category, path))
                            .forEach(path -> found.put(root.relativize(path).toString().replace('\\', '/'), path));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to walk " + directory, e);
                }
            }
        }
        return found;
    }

    private static boolean isPrompt(String category, Path path) {
        String name = path.getFileName().toString();
        return "skills".equals(category) ? "SKILL.md".equals(name) : name.endsWith(".md");
    }

    /** The category a prompt belongs to, taken from the path segment after the bundle name. */
    private static String categoryOf(String relativePath) {
        String[] segments = relativePath.split("/");
        return segments.length > 1 ? segments[1] : "";
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * Reads the leading YAML frontmatter block as flat key/value pairs. Deliberately not a YAML
     * parser: the frontmatter of a prompt is a handful of scalar keys, and a real parser would accept
     * shapes the loaders do not.
     *
     * @param document the file content
     * @return the keys in declaration order, empty when the document opens with no frontmatter block
     */
    static Map<String, String> frontmatter(String document) {
        Map<String, String> keys = new LinkedHashMap<>();
        List<String> lines = document.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            return keys;
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("---".equals(line.strip())) {
                return keys;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                keys.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
            }
        }
        // No closing delimiter: not a frontmatter block at all.
        return new LinkedHashMap<>();
    }

    @Test
    @DisplayName("every shipped prompt declares the frontmatter keys its category requires")
    void everyPrompt_declaresTheRequiredKeys() {
        TreeMap<String, Path> prompts = promptFiles();
        List<String> problems = new ArrayList<>();

        prompts.forEach((relative, file) -> {
            Map<String, String> keys = frontmatter(read(file));
            if (keys.isEmpty()) {
                problems.add(relative + ": no frontmatter block — an unversioned prompt leaves promptSetVersion covering less than the whole set");
                return;
            }
            for (String required : REQUIRED_KEYS.getOrDefault(categoryOf(relative), List.of())) {
                if (keys.getOrDefault(required, "").isEmpty()) {
                    problems.add(relative + ": '" + required + "' is missing or empty");
                }
            }
            String version = keys.getOrDefault("version", "");
            if (!version.isEmpty() && !VERSION.matcher(version).matches()) {
                problems.add(relative + ": 'version' must be a positive integer, was '" + version + "'");
            }
        });

        assertThat(problems).as("frontmatter of the shipped prompts").isEmpty();
        assertThat(prompts).as("both copies must contribute %d prompts each (16 skills + 12 commands + 2 rules + 2 workflows), plus the Claude-only subagents; a smaller listing means the bundle root resolved wrongly", PROMPTS_PER_BUNDLE)
                .hasSizeGreaterThanOrEqualTo(BUNDLES.size() * PROMPTS_PER_BUNDLE);
    }

    @Test
    @DisplayName("every frontmatter block is valid YAML — the loaders are lenient, the agent's parser is not")
    void everyFrontmatterBlock_isValidYaml() {
        TreeMap<String, Path> prompts = promptFiles();
        List<String> problems = new ArrayList<>();

        prompts.forEach((relative, file) -> {
            String block = rawFrontmatterBlock(read(file));
            if (block.isEmpty()) {
                return;
            }
            try {
                new Yaml(new SafeConstructor(new LoaderOptions())).load(block);
            } catch (RuntimeException notYaml) {
                problems.add(relative + ": " + notYaml.getMessage().lines().findFirst().orElse(""));
            }
        });

        assertThat(problems).as("Claude Code accepts frontmatter that a YAML parser rejects — an unquoted description containing ': ' is the usual case — so the kit can drift into a shape the agent's PromptFrontmatterParser cannot read. Quote the value").isEmpty();
    }

    /** The frontmatter block verbatim, delimiters excluded, empty when the document has none. */
    private static String rawFrontmatterBlock(String document) {
        List<String> lines = document.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            return "";
        }
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).strip())) {
                return String.join("\n", lines.subList(1, i));
            }
        }
        return "";
    }

    @Test
    @DisplayName("the two copies declare the same version for the same prompt")
    void bothCopies_agreeOnEveryVersion() {
        TreeMap<String, Path> prompts = promptFiles();
        List<String> mismatched = new ArrayList<>();

        prompts.forEach((relative, file) -> {
            // A Claude-only category has no twin BY CONSTRUCTION, and demanding one here would make
            // this test the place that forbids a host mechanism the other host expresses differently.
            if (!relative.startsWith(".claude/") || CLAUDE_ONLY_CATEGORIES.contains(categoryOf(relative))) {
                return;
            }
            Path twin = prompts.get(relative.replaceFirst("^\\.claude/", ".opencode/"));
            if (twin == null) {
                mismatched.add(relative + ": no counterpart in the .opencode copy");
                return;
            }
            String here = frontmatter(read(file)).getOrDefault("version", "");
            String there = frontmatter(read(twin)).getOrDefault("version", "");
            if (!here.equals(there)) {
                mismatched.add(relative + ": .claude says '" + here + "', .opencode says '" + there + "'");
            }
        });

        assertThat(mismatched).as("a version bumped in one copy only is the drift BundleParityTest exists to catch, arriving through the one field meant to make drift visible").isEmpty();
    }

    @Test
    @DisplayName("exactly the prompts the orchestrator parses declare an outputSchema, in both copies")
    void outputSchema_isDeclaredExactlyWhereTheResponseIsParsed() {
        TreeMap<String, Path> prompts = promptFiles();
        List<String> declaring = new ArrayList<>();

        prompts.forEach((relative, file) -> {
            if (!frontmatter(read(file)).getOrDefault("outputSchema", "").isEmpty()) {
                declaring.add(relative);
            }
        });

        List<String> expected = new ArrayList<>();
        for (String bundle : BUNDLES) {
            for (String prompt : PROMPTS_WITH_OUTPUT_SCHEMA) {
                expected.add(bundle + "/" + prompt);
            }
        }
        expected.sort(String::compareTo);
        declaring.sort(String::compareTo);

        assertThat(declaring).as("a prompt gains an outputSchema when code starts parsing its answer, and only then — otherwise the key claims a contract nothing enforces. Update PROMPTS_WITH_OUTPUT_SCHEMA in the same commit that changes this").isEqualTo(expected);
    }

    @Test
    @DisplayName("every declared outputSchema is a repository-root path, with no absolute prefix and no way out of the tree")
    void outputSchema_valuesAreSafeRelativePaths() {
        TreeMap<String, Path> prompts = promptFiles();
        List<String> problems = new ArrayList<>();

        prompts.forEach((relative, file) -> {
            String reference = frontmatter(read(file)).getOrDefault("outputSchema", "");
            if (reference.isEmpty()) {
                return;
            }
            if (reference.contains("..")) {
                problems.add(relative + ": '" + reference + "' climbs out of the repository — once the bundle is copied, it points at whatever happens to be there");
            } else if (!SCHEMA_REFERENCE.matcher(reference).matches()) {
                problems.add(relative + ": '" + reference + "' is not a repository-root path to a *.schema.json file");
            }
        });

        assertThat(problems).as("outputSchema references").isEmpty();
    }

    @Test
    @DisplayName("the schema-reference rule rejects absolute paths and escapes")
    void schemaReferenceRule_rejectsUnsafePaths() {
        for (String rejected : List.of("/etc/passwd.schema.json", "C:\\schema.schema.json", "../outside.schema.json", "schema.json", "", "./x.schema.json")) {
            boolean accepted = !rejected.contains("..") && SCHEMA_REFERENCE.matcher(rejected).matches();
            assertThat(accepted).as("'%s' must not pass as a schema reference", rejected).isFalse();
        }
        assertThat(SCHEMA_REFERENCE.matcher("stand-test-ai-schema/src/main/resources/schema/test-plan.schema.json").matches()).isTrue();
    }

    @Test
    @DisplayName("the version rule rejects the spellings that look like versions but are not")
    void versionRule_rejectsNonPositiveIntegers() {
        for (String rejected : List.of("0", "v1", "1.0", "-1", "01", "one", "")) {
            assertThat(VERSION.matcher(rejected).matches()).as("'%s' must not pass as a version", rejected).isFalse();
        }
        for (String accepted : List.of("1", "2", "17", "100")) {
            assertThat(VERSION.matcher(accepted).matches()).as("'%s' must pass as a version", accepted).isTrue();
        }
    }

    @Test
    @DisplayName("a document whose frontmatter block is unterminated is treated as having none")
    void frontmatter_requiresAClosingDelimiter() {
        assertThat(frontmatter("---\nname: x\ndescription: y\n---\nbody")).containsEntry("name", "x").containsEntry("description", "y");
        assertThat(frontmatter("---\nname: x\nbody without a closing delimiter")).as("an unterminated block must not be read as valid frontmatter").isEmpty();
        assertThat(frontmatter("# Skill\n\nno frontmatter at all")).isEmpty();
    }
}
