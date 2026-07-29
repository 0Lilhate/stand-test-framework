package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.validation.ForbiddenOperation;

/**
 * The kit's scanner, checked from the build that owns the vocabulary it borrows.
 *
 * <p>The scanner lives in the bundle because a hook must answer in milliseconds, work while the
 * build is red, and run at a consumer where this module is a jar rather than a project. None of that
 * makes it exempt from proof. What it borrows — the guardrail codes — belongs to
 * {@link ForbiddenOperation} here, so the two are compared here: a rule id in {@code detectors.json}
 * that no longer names an enum constant is a second vocabulary starting to form, and the point of
 * moving the detectors out of Java was to keep the regexes, not to fork the dictionary.
 *
 * <p>The second half runs the scanner as a process against the golden corpus. A table of regexes
 * that nobody executes is a table that rots: the corpus is six fixtures written to violate specific
 * findings, and the assertion is not "it found something" but "it found exactly these and stayed
 * silent on the clean ones". A scanner that fires on the sanctioned examples is one people switch
 * off, and then the accurate findings stop working too.
 *
 * <p>Skipped rather than failed when {@code node} is absent: the SDK builds on machines that have no
 * reason to carry a JavaScript runtime, and a Java build that fails for lack of one would be a
 * dependency this repository has decided not to take. The absence is reported, not hidden.
 */
class GuardrailScannerParityTest {

    private static final String SCANNER = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String DETECTORS = "docs/ai-agent/.claude/hooks/detectors.json";

    private static final String CORPUS = "docs/ai-agent/.claude/hooks/corpus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * What each fixture must be found guilty of, and HOW MANY TIMES.
     *
     * <p>Transcribed from the fixtures' own headers, which enumerate the violations they plant. The
     * counts are not decoration: with a set of rule ids, removing one of the two markers of finding 9
     * still produced VALIDATOR_BYPASS from the other, and a detector that had lost half its coverage
     * passed. A probe found that, which is the argument for probing rather than reasoning.
     *
     * <p>An empty map is the stronger assertion of the pair: it says the scanner stays quiet on an
     * artifact that does everything correctly.
     */
    private static final Map<String, Map<String, Long>> EXPECTED = Map.of(
            "raw-transport-test.java.txt", Map.of("HARDCODED_STAND_URL", 1L, "DIRECT_TRANSPORT_CLIENT", 3L),
            "validator-bypass-test.java.txt", Map.of("VALIDATOR_BYPASS", 2L),
            "shared-state-test.java.txt", Map.of("HARDCODED_CORRELATION_ID", 1L, "SDK_EXCEPTION_SWALLOWED", 1L, "SHARED_MUTABLE_TEST_STATE", 2L),
            "clean-declarative-test.java.txt", Map.of());

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static JsonNode detectorTable() {
        Path file = repositoryRoot().resolve(DETECTORS);
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** Runs the scanner, or reports why it could not. */
    private static String scan(String fixture, String... extra) {
        Path root = repositoryRoot();
        List<String> command = new ArrayList<>(List.of("node", SCANNER, "scan", CORPUS + "/" + fixture, "--json"));
        command.addAll(List.of(extra));
        try {
            Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the scanner did not finish in 60s");
            return output;
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine, so the scanner could not be executed: " + e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static TreeSet<String> ruleIdsIn(String json) {
        return new TreeSet<>(countsIn(json).keySet());
    }

    /** How many findings the scanner reported per rule id. */
    private static Map<String, Long> countsIn(String json) {
        Map<String, Long> counts = new TreeMap<>();
        try {
            MAPPER.readTree(json).path("findings")
                    .forEach(item -> counts.merge(item.path("ruleId").asText(), 1L, Long::sum));
        } catch (IOException e) {
            throw new IllegalStateException("the scanner did not answer with JSON:\n" + json, e);
        }
        return counts;
    }

    @Test
    @DisplayName("every rule id that claims an SDK guardrail names a real ForbiddenOperation constant")
    void ruleIds_matchTheEnum() {
        TreeSet<String> known = new TreeSet<>();
        for (ForbiddenOperation operation : ForbiddenOperation.values()) {
            known.add(operation.code());
        }
        // The findings the SDK has no runtime guardrail for carry names of the kit's own — that is
        // the point of several of them, and the analysis says so: nothing in the runtime can name a
        // raw client or a shared static field, because neither ever enters the SDK pipeline.
        TreeSet<String> kitOwn = new TreeSet<>(List.of(
                "SCRIPT_IN_DECLARATIVE_DOCUMENT", "DIRECT_TRANSPORT_CLIENT", "VALIDATOR_BYPASS",
                "HARDCODED_CORRELATION_ID", "SDK_EXCEPTION_SWALLOWED", "MASKED_SECRET", "PII_IN_FIXTURE",
                "UNSANCTIONED_DEPENDENCY", "KAFKA_EXPECT_WITHOUT_DISCRIMINATOR", "SHARED_MUTABLE_TEST_STATE",
                "FAILURE_CONCEALMENT"));

        List<String> unknown = new ArrayList<>();
        detectorTable().path("detectors").forEach(detector -> {
            String ruleId = detector.path("ruleId").asText();
            if (!known.contains(ruleId) && !kitOwn.contains(ruleId)) {
                unknown.add(ruleId);
            }
        });

        assertThat(unknown)
                .as("a rule id that names neither a ForbiddenOperation nor a declared kit-own finding is a second vocabulary of guardrail codes starting to form")
                .isEmpty();
    }

    @Test
    @DisplayName("the table declares all eighteen findings and says which one needs more than one artifact")
    void table_isComplete() {
        JsonNode detectors = detectorTable().path("detectors");
        TreeSet<Integer> numbers = new TreeSet<>();
        List<String> unimplemented = new ArrayList<>();
        List<String> needTwoVersions = new ArrayList<>();
        detectors.forEach(detector -> {
            numbers.add(detector.path("finding").asInt());
            if (!detector.path("implemented").asBoolean(true)) {
                unimplemented.add(detector.path("ruleId").asText());
            }
            if ("previousVersion".equals(detector.path("requires").asText(null))) {
                needTwoVersions.add(detector.path("ruleId").asText());
            }
        });

        assertThat(numbers).as("the numbering is the safety review's own, and a gap in it is a finding nobody carried over").hasSize(18);
        assertThat(numbers.first()).isEqualTo(1);
        assertThat(numbers.last()).isEqualTo(18);
        assertThat(unimplemented).as("every finding in the table is implemented; a declared-but-absent check is worse than an absent one").isEmpty();
        assertThat(needTwoVersions)
                .as("finding 18 asks what a CHANGE stopped checking, which one version of a file cannot answer — so a plain scan reports it as not run rather than counting it among the passes")
                .containsExactly("FAILURE_CONCEALMENT");
    }

    @Test
    @DisplayName("the scanner classifies the golden corpus exactly — it finds the planted violations and stays silent on the clean fixture")
    void scanner_classifiesTheCorpus() {
        EXPECTED.forEach((fixture, expected) ->
                assertThat(countsIn(scan(fixture)))
                        .as("%s: the fixture's own header enumerates the violations it plants, and how many of each", fixture)
                        .containsExactlyInAnyOrderEntriesOf(expected));
    }

    @Test
    @DisplayName("a build file is judged by the name it will have, not by the name the fixture is stored under")
    void scanner_judgesBuildFilesByTheirRealName() {
        assertThat(ruleIdsIn(scan("unsanctioned-dependencies.gradle.kts.txt", "--as", "build.gradle.kts")))
                .containsExactly("UNSANCTIONED_DEPENDENCY");
        assertThat(ruleIdsIn(scan("sanctioned-dependencies.gradle.kts.txt", "--as", "build.gradle.kts")))
                .as("the three additions the kit sanctions must not be reported; a gate that fires on the allowed set is one nobody keeps on")
                .isEmpty();
    }

    @Test
    @DisplayName("the scanner reports which findings ran and which did not, in every answer")
    void scanner_reportsItsOwnCoverage() {
        String json = scan("clean-declarative-test.java.txt");
        try {
            JsonNode answer = MAPPER.readTree(json);
            assertThat(answer.path("gatesRun")).as("a clean report from part of the set is not a clean report from all of it").hasSize(17);
            assertThat(answer.path("gatesNotRun")).hasSize(1);
        } catch (IOException e) {
            throw new IllegalStateException("the scanner did not answer with JSON:\n" + json, e);
        }
    }
}
