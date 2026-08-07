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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The forward half of the UI gate census — the read side of UITG-F006's definition of done: "every
 * machine-expressible gate U1..U20 has a detector".
 *
 * <p>{@link UiHumanGateCensusTest} pins the other direction: the gates the documents call human have
 * no detector. That direction alone cannot close F006, because it is satisfied by a kit with no
 * detectors at all — every gate would be human and the census would be consistent. What it never
 * asked is whether the gates a machine CAN check actually have one, and the two that did not were
 * invisible for exactly that reason: U16 had no detector, and U20 had one (finding 17) that the
 * documents did not know about.
 *
 * <p>Neither side of the comparison is written here. The expressible set is read from the spike
 * report {@code 30-ui-gate-expressibility-spike.md} — the document that decided expressibility, and
 * the only authority on it — and the covered set is read from the kit's own "Machine coverage" table.
 * A hand-copied list in this file would be a third place to go stale, which is the failure the census
 * exists to prevent. So a gate promoted to expressible in the spike fails this test until the kit
 * gains a detector for it, and a detector named in the checklist that no {@code detectors.json}
 * declares fails it too.
 */
class UiMachineGateCensusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A row of the spike's summary table: {@code | U2 | … | BLOCK | ✔ | … |}. */
    private static final Pattern SPIKE_ROW = Pattern.compile("^\\|\\s*(U\\d+[ab]?)\\s*\\|.*$", Pattern.MULTILINE);

    /** A gate identifier wherever it is named in a table cell. */
    private static final Pattern GATE = Pattern.compile("\\bU(\\d+[ab]?)\\b");

    /** A detector id: the kit spells every one as a backticked SCREAMING_SNAKE token. */
    private static final Pattern DETECTOR_ID = Pattern.compile("`([A-Z][A-Z0-9_]{4,})`");

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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /**
     * The gates the spike marked fully expressible ({@code ✔}) in its summary table.
     *
     * <p>The mark is read from the row rather than the row's position: the table has a column order
     * the document may revise, and a fixed index would silently read the wrong column. {@code ≈} and
     * {@code ✘} rows are deliberately not collected — a partially expressible gate is not a promise
     * the kit broke by having no detector, and F006 never claimed it.
     */
    private static Set<String> fullyExpressibleGates() {
        String spike = read(repositoryRoot().resolve("docs/ui-test-generation/planning/30-ui-gate-expressibility-spike.md"));
        Set<String> gates = new TreeSet<>();
        Matcher rows = SPIKE_ROW.matcher(spike);
        while (rows.find()) {
            String row = rows.group(0);
            // The mark lives in its own cell; a '✔' anywhere else in the row would be prose about a
            // different gate, so the cell boundaries are part of the match.
            if (row.contains("| ✔ |")) {
                gates.add(rows.group(1));
            }
        }
        return gates;
    }

    /** The "Machine coverage" table of one bundle copy, as the raw rows that claim coverage. */
    private static Set<String> coverageRows(String bundle) {
        String checklist = read(repositoryRoot()
                .resolve("docs/ai-agent/" + bundle + "/skills/stand-test-ui-safety-review/ui-safety-checklist.md"));
        Set<String> rows = new LinkedHashSet<>();
        for (String line : checklist.split("\\R")) {
            String row = line.trim();
            if (!row.startsWith("|") || !row.contains("U")) {
                continue;
            }
            // A row claims coverage when its verdict column answers yes or partly. The eye-only row
            // answers "**no detector in this kit**" and is what must NOT be collected.
            if (row.contains("| yes") || row.contains("| partly")) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Set<String> coveredGates(String bundle) {
        Set<String> gates = new TreeSet<>();
        for (String row : coverageRows(bundle)) {
            // Only the gate column names gates; the verdict column names detectors, and a detector id
            // never matches the gate shape.
            String gateColumn = row.split("\\|")[1];
            Matcher matcher = GATE.matcher(gateColumn);
            while (matcher.find()) {
                gates.add("U" + matcher.group(1));
            }
        }
        return gates;
    }

    private static Set<String> namedDetectors(String bundle) {
        Set<String> ids = new TreeSet<>();
        for (String row : coverageRows(bundle)) {
            Matcher matcher = DETECTOR_ID.matcher(row);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    private static Set<String> declaredRuleIds(String bundle) {
        Path table = repositoryRoot().resolve("docs/ai-agent/" + bundle + "/hooks/detectors.json");
        JsonNode root;
        try {
            root = MAPPER.readTree(read(table));
        } catch (IOException e) {
            throw new UncheckedIOException("could not parse " + table, e);
        }
        Set<String> ids = new LinkedHashSet<>();
        root.path("detectors").forEach(detector -> ids.add(detector.path("ruleId").asText()));
        return ids;
    }

    @Test
    @DisplayName("every gate the spike calls fully expressible is claimed machine-covered by the kit — UITG-F006's definition of done")
    void everyExpressibleGate_hasADetector() {
        Set<String> expressible = fullyExpressibleGates();
        for (String bundle : new String[] {".claude", ".opencode"}) {
            assertThat(coveredGates(bundle))
                    .as(bundle + ": F006 is done when every machine-expressible gate has a detector. A gate listed here is one "
                            + "30-ui-gate-expressibility-spike.md marked '✔' while the kit's Machine coverage table still leaves it "
                            + "to the eye — either build the detector, or downgrade the gate in the spike with the measurement that "
                            + "justifies it. Do not simply add the row: the table is a claim about detectors.json")
                    .containsAll(expressible);
        }
    }

    @Test
    @DisplayName("every detector the coverage table names is really declared in both detector copies")
    void everyNamedDetector_isDeclared() {
        for (String bundle : new String[] {".claude", ".opencode"}) {
            Set<String> declared = declaredRuleIds(bundle);
            for (String named : namedDetectors(bundle)) {
                assertThat(declared)
                        .as(bundle + ": the coverage table names '" + named + "', so a reader takes that gate for machine-checked. "
                                + "A named detector that detectors.json does not declare is the 'declared but never applied' shape — "
                                + "the claim reads as a check that ran")
                        .contains(named);
            }
        }
    }

    @Test
    @DisplayName("the two censuses partition the gates: none is both machine-covered and eye-only")
    void theTwoCensuses_doNotOverlap() {
        for (String bundle : new String[] {".claude", ".opencode"}) {
            String checklist = read(repositoryRoot()
                    .resolve("docs/ai-agent/" + bundle + "/skills/stand-test-ui-safety-review/ui-safety-checklist.md"));
            String eyeOnlyRow = "";
            for (String line : checklist.split("\\R")) {
                if (line.contains("no detector in this kit")) {
                    eyeOnlyRow = line;
                }
            }
            assertThat(eyeOnlyRow).as(bundle + ": the eye-only row is what the honest boundary is made of").isNotEmpty();

            Set<String> eyeOnly = new TreeSet<>();
            Matcher matcher = GATE.matcher(eyeOnlyRow.split("\\|")[1]);
            while (matcher.find()) {
                eyeOnly.add("U" + matcher.group(1));
            }
            // U4 is the one gate that is legitimately split — its secret half is caught by the
            // disclosure detectors, its semantic half is not — and the table says so in both rows.
            eyeOnly.remove("U4");

            assertThat(eyeOnly)
                    .as(bundle + ": a gate in both rows tells the reviewer two different things about whether it was checked")
                    .doesNotContainAnyElementsOf(coveredGates(bundle));
        }
    }

    @Test
    @DisplayName("both sides of the census were actually parsed — neither list is vacuously empty")
    void neitherSideOfTheCensus_isVacuous() {
        assertThat(fullyExpressibleGates())
                .as("a spike table this parse cannot read would make every assertion above pass on an empty set — the vacuous green")
                .hasSizeGreaterThanOrEqualTo(9)
                .contains("U2", "U16", "U20");
        for (String bundle : new String[] {".claude", ".opencode"}) {
            assertThat(coveredGates(bundle)).as(bundle + ": the coverage table must parse to a non-empty gate set")
                    .hasSizeGreaterThanOrEqualTo(9);
            assertThat(namedDetectors(bundle)).as(bundle + ": the coverage table must parse to a non-empty detector set")
                    .contains("UI_DISCOVERY_PARITY", "UI_GENERATION_REPORT_INCOMPLETE", "SHARED_MUTABLE_TEST_STATE");
        }
    }
}
