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
import java.util.Set;
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
 * that nobody executes is a table that rots: the corpus is eight fixtures written to violate specific
 * findings, and the assertion is not "it found something" but "it found exactly these and stayed
 * silent on the clean ones". A scanner that fires on the sanctioned examples is one people switch
 * off, and then the accurate findings stop working too. That is not hypothetical: finding 3 carried no
 * fixture at all, and was refusing three of the four DB shapes the kit's own crib prescribes — the
 * pair {@code destructive-sql-test} / {@code sanctioned-db-writes} is what a fixture would have said.
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
            // The java half of two findings that read documents only until now. The fixture carries its
            // clean values in the same file deliberately: an id and a sum are both long runs of digits,
            // so the forms are anchored on the POSITION of the value, and only a fixture holding both
            // can show that the anchor works. The fourth FIXED_TEST_DATA_ID is the declared cost of
            // that anchor, enumerated in the fixture's header rather than tuned away.
            "fixed-ids-and-instance-state-test.java.txt", Map.of("FIXED_TEST_DATA_ID", 4L, "SHARED_MUTABLE_TEST_STATE", 2L),
            "destructive-sql-test.java.txt", Map.of("DESTRUCTIVE_SQL_WITHOUT_ALLOW", 4L),
            "sanctioned-db-writes.java.txt", Map.of(),
            "clean-declarative-test.java.txt", Map.of(),
            // UI half of the safety gate (UITG-S020): headers and "Genealogy" spell out the counts a
            // detector could quietly lose if a regex half-regressed (the same discipline as the SQL sleep
            // fixture above).
            "ui-orphaned-wait.java.txt", Map.of(
                    "THREAD_SLEEP", 1L,
                    "XPATH_LOCATOR", 1L,
                    "UI_LOCATOR_OUTSIDE_PAGES", 1L,
                    "EXPECT_EVENTUALLY_WITHOUT_WITHIN", 1L,
                    "UI_LOGIN_WITHOUT_ROLE", 1L,
                    "UI_OPEN_OR_ASSERT_TEMPLATE", 2L),
            "clean-ui-page.java.txt", Map.of());

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
        // raw client or a shared static field, because neither ever enters the SDK pipeline. The list
        // is READ from the table rather than written here: it is one half of a census whose other half
        // (`withoutDetector`) lives there, and a census with its two halves in two files is one whose
        // second half is found six months later.
        TreeSet<String> kitOwn = kitOwnFindings();

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
        assertThat(kitOwn)
                .as("the kit-own list must really have been read; an empty one would make the assertion above pass on nothing")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every guardrail code either has a detector or records, in the table, why it cannot have one")
    void everyGuardrailCode_hasADetectorOrARecordedReason() {
        TreeSet<String> covered = codesNamedByTheTable();
        JsonNode reasons = detectorTable().path("guardrailVocabulary").path("withoutDetector");

        List<String> unexplained = new ArrayList<>();
        for (ForbiddenOperation operation : ForbiddenOperation.values()) {
            String code = operation.code();
            if (!covered.contains(code) && !reasons.has(code)) {
                unexplained.add(code);
            }
        }

        assertThat(unexplained)
                .as("this is the direction nothing checked, and it is the direction a gap arrives from: a constant added to "
                        + "ForbiddenOperation used to break nothing at all, so nine of sixteen codes sat without a static check "
                        + "and never said so. Either write the detector, or record in guardrailVocabulary.withoutDetector which "
                        + "layer catches it instead")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing in the without-detector list is stale, invented, or excused without naming the layer that catches it")
    void theWithoutDetectorList_staysHonest() {
        TreeSet<String> codes = new TreeSet<>();
        for (ForbiddenOperation operation : ForbiddenOperation.values()) {
            codes.add(operation.code());
        }
        TreeSet<String> covered = codesNamedByTheTable();
        JsonNode reasons = detectorTable().path("guardrailVocabulary").path("withoutDetector");

        assertThat(reasons.isObject()).as("the table must carry the reverse half of the census at all").isTrue();
        assertThat(reasons.size()).as("an empty list would make the census above vacuous").isPositive();

        reasons.fieldNames().forEachRemaining(code -> {
            assertThat(codes)
                    .as("'%s' is excused from having a detector and is not a ForbiddenOperation constant at all — an entry for a "
                            + "code that does not exist reads as coverage of something", code)
                    .contains(code);
            assertThat(covered)
                    .as("'%s' now HAS a detector, so its entry is a standing claim that it does not. A stale excuse is worse "
                            + "than none: it is the one thing a reader trusts without checking", code)
                    .doesNotContain(code);
            assertThat(reasons.path(code).asText())
                    .as("'%s' must say which layer catches it instead — an excuse that names no substitute is a gap with better "
                            + "wording", code)
                    .hasSizeGreaterThan(40);
        });
    }

    /** The kit-own findings the table declares: rule ids deliberately outside the SDK's vocabulary. */
    private static TreeSet<String> kitOwnFindings() {
        TreeSet<String> declared = new TreeSet<>();
        detectorTable().path("guardrailVocabulary").path("kitOwnFindings").forEach(id -> declared.add(id.asText()));
        return declared;
    }

    /**
     * Every guardrail code the detector table names, by whichever route it names it.
     *
     * <p>A rule id is the obvious route; the keys of a detector's {@code imports.groups} are the other
     * one, and they are not a technicality. Finding 8 is one detector over four transports, and it
     * spells two of them with the enum's own codes — {@code RAW_KAFKA_CLIENT} and
     * {@code RAW_JDBC_CLIENT} are checked, reported and fixed under those names. Deriving coverage
     * from the table keeps them out of the excuse list, which must hold only real judgements.
     */
    private static TreeSet<String> codesNamedByTheTable() {
        TreeSet<String> named = new TreeSet<>();
        detectorTable().path("detectors").forEach(detector -> {
            named.add(detector.path("ruleId").asText());
            detector.path("imports").path("groups").fieldNames().forEachRemaining(named::add);
        });
        return named;
    }

    @Test
    @DisplayName("the table declares all twenty-six findings and says which one needs more than one artifact")
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

        assertThat(numbers).as("the numbering is the safety review's own, and a gap in it is a finding nobody carried over")
                .hasSize(26);
        assertThat(numbers.first()).isEqualTo(1);
        assertThat(numbers.last()).isEqualTo(26);
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

    /**
     * Coverage is a fact about the ARTIFACT, and the answer used to be a fact about the table.
     *
     * <p>Seventeen ran and one did not — printed under every scan of every kind. On a Java file it was
     * wrong by six: the timeout walk, the script-key check, the fixed-id heuristic, the Kafka
     * discriminator and the dependency check have no Java branch at all, and the transport detector's
     * two branches are Java imports and parsed documents. Overstating coverage is the failure this
     * whole reporting discipline exists to prevent, and the summary line was committing it.
     *
     * <p>The numbers below are therefore asserted against the table rather than written down: a
     * detector added or re-scoped changes them, and hard-coding either count would put this test back
     * where the line was.
     */
    @Test
    @DisplayName("the scanner reports coverage of the artifact in front of it, not of the table")
    void scanner_reportsItsOwnCoverage() {
        String json = scan("clean-declarative-test.java.txt");
        try {
            JsonNode answer = MAPPER.readTree(json);
            Set<String> expectedRun = new TreeSet<>();
            Set<String> expectedNotRun = new TreeSet<>();
            for (JsonNode detector : detectorTable().path("detectors")) {
                boolean subject = appliesToKind(detector, "java");
                boolean needsBothVersions = "previousVersion".equals(detector.path("requires").asText(null));
                (subject && !needsBothVersions ? expectedRun : expectedNotRun).add(detector.path("ruleId").asText());
            }

            assertThat(ids(answer.path("gatesRun")))
                    .as("a clean report from part of the set is not a clean report from all of it")
                    .isEqualTo(expectedRun);
            assertThat(ids(answer.path("gatesNotRun"))).isEqualTo(expectedNotRun);
            assertThat(expectedNotRun)
                    .as("a java artifact is not the subject of every finding, and if it were this test would be vacuous")
                    .isNotEmpty();

            JsonNode reasons = answer.path("gatesNotRunReasons");
            assertThat(reasons.path("FAILURE_CONCEALMENT").asText())
                    .as("the reason has to travel with the id: 'the caller supplied one version' and 'this kind is not "
                            + "its subject' are different answers, and merging them says less than the scan knows")
                    .isEqualTo("previousVersion");
            assertThat(reasons.path("UNSANCTIONED_DEPENDENCY").asText()).isEqualTo("kind");
        } catch (IOException e) {
            throw new IllegalStateException("the scanner did not answer with JSON:\n" + json, e);
        }
    }

    /** Whether the table says this kind of artifact is the detector's subject. Mirrors `appliesToKind`. */
    private static boolean appliesToKind(JsonNode detector, String kind) {
        for (JsonNode skipped : detector.path("notOn")) {
            if (kind.equals(skipped.asText())) {
                return false;
            }
        }
        String appliesTo = detector.path("appliesTo").asText("");
        return "any".equals(appliesTo) || kind.equals(appliesTo);
    }

    private static Set<String> ids(JsonNode array) {
        Set<String> found = new TreeSet<>();
        array.forEach(item -> found.add(item.asText()));
        return found;
    }
}
