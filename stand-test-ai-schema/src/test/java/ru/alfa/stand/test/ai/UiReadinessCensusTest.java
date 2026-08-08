package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The third reader of the gate census — {@code docs/agent-evaluation/ui-wave-1-readiness.md}, the
 * document handed outwards to answer "is the UI branch ready for a pilot".
 *
 * <p>{@link UiHumanGateCensusTest} and {@link UiMachineGateCensusTest} hold the kit's own rules and
 * checklist against {@code detectors.json}. Neither looked at this file, and that gap was not
 * theoretical: by 2026-08-07 the readiness report still said "detectors: 18, UI-specific: 0" and still
 * listed U1, U2, U5, U7, U9, U16 and U17 as eye-only, months after UITG-S020/S021/F006 gave each of
 * them a detector. Every one of those errors understated the kit — a readiness document that
 * undersells is read as conservatively honest, which is exactly why nobody checked it.
 *
 * <p>So the numbers are read back out of the document and compared with the files they describe, and
 * the human-gate census must be quoted from the guard rule VERBATIM rather than retold. A retelling is
 * what drifts: it stays grammatical while it goes false, and no test can tell the difference between a
 * paraphrase and a mistake. The parse itself lives in {@link KitCensus}, shared with the other two
 * censuses — a private copy here would be the fourth place to go stale, which is the failure being
 * guarded against.
 */
class UiReadinessCensusTest {

    /** The readiness report's own statement of how many detectors the kit declares. */
    private static final Pattern TOTAL_DETECTORS = Pattern.compile("\\*\\*(\\d+)\\*\\* всего, из них применимых к java-артефакту — \\*\\*(\\d+)\\*\\*");

    /** Its statement of how many of them serve a UI gate. */
    private static final Pattern UI_GATE_DETECTORS = Pattern.compile("Детекторов, закрывающих гейты `U1\\.\\.U20` \\| \\*\\*(\\d+)\\*\\*");

    /** The sentence the guard rule owns; the readiness report must carry it letter for letter. */
    private static final String HUMAN_GATE_SENTENCE = "The rest stay human: U4, U8, U10, U11a/b, U12, U14, U15, U18, U19";

    /** The report's row listing what a machine does catch, and the row listing what it does not. */
    private static final String MACHINE_ROW = "Что ловит машина в UI-артефакте";

    private static final String EYE_ONLY_ROW = "Что не ловит никто, кроме глаз";

    private static String readinessReport() {
        return KitCensus.read(KitCensus.repositoryRoot().resolve("docs/agent-evaluation/ui-wave-1-readiness.md"));
    }

    /** One row of the report's axis-1 table, from its leading phrase to the end of the line. */
    private static String row(String leadingPhrase) {
        String report = readinessReport();
        int start = report.indexOf(leadingPhrase);
        assertThat(start).as("the readiness report must keep its '" + leadingPhrase + "' row").isNotNegative();
        return report.substring(start, report.indexOf('\n', start));
    }

    @Test
    @DisplayName("the readiness report's detector counts are the counts detectors.json actually declares")
    void detectorCountsInTheReport_matchTheDetectorTable() {
        Matcher stated = TOTAL_DETECTORS.matcher(readinessReport());
        assertThat(stated.find())
                .as("the readiness report must state its detector counts in the pinned spelling — a report whose numbers "
                        + "this test cannot find is a report nothing checks, which is the state that produced 'UI-specific: 0'")
                .isTrue();

        assertThat(Integer.parseInt(stated.group(1)))
                .as("the readiness report names a detector total a pilot decision is taken on. detectors.json declares "
                        + KitCensus.ruleIds(".claude").size() + "; re-measure the report rather than the other way round")
                .isEqualTo(KitCensus.ruleIds(".claude").size());
        assertThat(Integer.parseInt(stated.group(2)))
                .as("java-applicable detectors are those whose appliesTo is 'java' or 'any'")
                .isEqualTo(KitCensus.javaApplicableCount(".claude"));
    }

    @Test
    @DisplayName("the number of detectors the report credits to UI gates is exactly the number the coverage table names")
    void uiGateDetectorCountInTheReport_matchesTheCoverageTable() {
        Matcher stated = UI_GATE_DETECTORS.matcher(readinessReport());
        assertThat(stated.find()).as("the readiness report must state how many detectors serve U1..U20").isTrue();
        assertThat(Integer.parseInt(stated.group(1)))
                .as("the kit's Machine coverage table names " + KitCensus.namedDetectors(".claude")
                        + ", so the report must credit the kit with EXACTLY that many. Understating is the failure this test "
                        + "exists for — it reads as caution and is simply wrong; overstating claims a check that never runs")
                .isEqualTo(KitCensus.namedDetectors(".claude").size());
    }

    @Test
    @DisplayName("the report quotes the human-gate census verbatim from the guard rule, in both bundle copies' spelling")
    void theReport_quotesTheHumanGateCensusVerbatim() {
        for (String bundle : KitCensus.BUNDLES) {
            assertThat(KitCensus.guardRule(bundle))
                    .as(bundle + ": the guard rule owns the sentence this test transports")
                    .contains(HUMAN_GATE_SENTENCE);
        }
        assertThat(readinessReport())
                .as("the readiness report must carry the guard rule's human-gate sentence letter for letter. Retelling it "
                        + "in the report's own words is how it went false last time: the retelling stayed readable while "
                        + "seven gates quietly gained detectors")
                .contains(HUMAN_GATE_SENTENCE);
    }

    @Test
    @DisplayName("the report does not call eye-only a gate the kit has a detector for")
    void theReport_doesNotCallAMachineCoveredGateEyeOnly() {
        Set<String> named = new TreeSet<>(KitCensus.gatesIn(row(EYE_ONLY_ROW)));
        // U4 is legitimately split: its secret half is caught by the disclosure detectors, its semantic
        // half is not, and both censuses say so. The same exemption UiMachineGateCensusTest makes.
        named.remove("U4");

        Set<String> covered = new TreeSet<>(KitCensus.coveredGates(".claude"));
        covered.remove("U4");

        assertThat(named)
                .as("a gate in this row tells a pilot lead nobody checked it. These were listed here while detectors "
                        + "existed: U1 (UI_DISCOVERY_PARITY), U2, U5, U7, U9, U16, U17")
                .doesNotContainAnyElementsOf(covered);
    }

    @Test
    @DisplayName("the report's list of what a machine does catch is the coverage table's gate set, not a shorter retelling")
    void theReport_listsEveryMachineCoveredGate() {
        assertThat(KitCensus.gatesIn(row(MACHINE_ROW)))
                .as("this row is prose, so it can quietly lose a gate — and it did: U13 (secrets/PII) was machine-covered "
                        + "and unlisted in the very edit that fixed the same understatement elsewhere. It must name exactly "
                        + "the gates the kit's Machine coverage table claims")
                .isEqualTo(KitCensus.coveredGates(".claude"));
    }

    @Test
    @DisplayName("neither side was vacuously parsed — the numbers and the census really came out of the files")
    void neitherSide_isVacuous() {
        assertThat(KitCensus.ruleIds(".claude")).as("a detectors.json this parse cannot read makes every count above meaningless")
                .hasSizeGreaterThanOrEqualTo(20);
        assertThat(KitCensus.javaApplicableCount(".claude"))
                .as("appliesTo must really be present — an absent field would count zero")
                .isGreaterThanOrEqualTo(10);
        assertThat(KitCensus.namedDetectors(".claude"))
                .as("the coverage table must parse to a non-empty detector set, else the UI count compares against zero")
                .contains("UI_DISCOVERY_PARITY", "UI_GENERATION_REPORT_INCOMPLETE");
        assertThat(KitCensus.coveredGates(".claude"))
                .as("the machine census must parse to a non-empty gate set, else the row assertions pass on nothing")
                .contains("U1", "U13", "U16", "U20");
    }
}
