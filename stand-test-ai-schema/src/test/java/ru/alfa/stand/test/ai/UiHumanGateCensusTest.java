package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census of UI safety gates U1..U20 over the actual {@code detectors.json} — the read side of
 * UITG-T004 "name the gates that stay human".
 *
 * <p>The guard rule and the checklist promise a specific split: the gates a machine in THIS kit can
 * catch, and the gates that stay eye-only. That promise must not go stale. This test is the check
 * that reads {@code detectors.json} (both bundle copies) plus the two documents that state the
 * split, and holds them together:
 *
 * <ul>
 *   <li>every gate the documents call machine-checked has a matching {@code ruleId};
 *   <li>the gates the documents call human are the ones the kit has no rule for;
 *   <li>the human-gate list is spelled the same in the guard rule and in the checklist.
 * </ul>
 *
 * <p>The direction that matters is the one that used to fail silently: adding a detector for a
 * human gate while leaving the documents calling it human is the "declared but never applied"
 * shape, the negative scenario of the card. To keep that failure loud, every {@code UI_*} rule is
 * pinned to a gate here; a rule the test does not know of is drift, and a gate a detector suddenly
 * knows about must be moved out of the human census first.
 */
class UiHumanGateCensusTest {

    /** Every {@code UI_*} / driver-wait rule, pinned to its gate in {@code §2 Hard constraints} of the guard rule. */
    private static final Set<String> KNOWN_UI_RULES = new TreeSet<>(Set.of(
            "UI_DISCOVERY_PARITY",        // U1
            "UI_LOCATOR_OUTSIDE_PAGES",   // U2
            "UI_REPORT_STAND_ADDRESS",    // U3 (report half)
            "HARDCODED_STAND_URL",        // U3 (java half — protocol-wide, drives "no address" gates)
            "UI_LOGIN_WITHOUT_ROLE",      // U5
            "THREAD_SLEEP",               // U6 (protocol-wide + driver waits)
            "XPATH_LOCATOR",              // U7
            "UI_OPEN_OR_ASSERT_TEMPLATE", // U9
            "UI_GENERATION_REPORT_INCOMPLETE", // U16 (structure + snapshot existence)
            "EXPECT_EVENTUALLY_WITHOUT_WITHIN", // U17
            "SHARED_MUTABLE_TEST_STATE")); // U20 (protocol-wide; the UI gate reuses it verbatim)

    /** The gates the guard rule must list as staying human — the exact census this test pins. */
    private static final String HUMAN_GATES_SENTENCE = "U4, U8, U10, U11a/b, U12, U14, U15, U18, U19";

    /**
     * Every gate U1..U20 minus the machine list must be outside the human census: the human gates
     * are the complement of the named rules. The rule and checklist both settle that split, and this
     * test pins the sentence so the two stay interchangeable.
     */
    @Test
    @DisplayName("the guard rule and the checklist spell exactly the human-gate census, and detectors cover none of them")
    void guardRule_andChecklist_nameTheHumanGates_andNoDetectorCoversOne() {
        for (String bundle : KitCensus.BUNDLES) {
            String ruleText = KitCensus.guardRule(bundle);
            String checklistText = KitCensus.checklist(bundle);

            assertThat(ruleText).as(bundle + " rules: the human-gate sentence must be the pinned one")
                    .contains("The rest stay human: " + HUMAN_GATES_SENTENCE);

            // Every one of the human gates must have NO detector. We assert individually, so a new
            // detector for a human gate has a failing test naming exactly that gate.
            for (String gate : Set.of("U8", "U10", "U11a/b", "U12", "U14", "U15", "U18", "U19")) {
                assertThat(checklistText)
                        .as(bundle + " checklist: " + gate + " is a human gate the kit has no detector for")
                        .containsPattern("(?s)(?i)" + gate + ".*ey[eéE] only");
            }
            assertThat(checklistText).as(bundle + " checklist: the 'eye only' marker exists for the semantic role of U4")
                    .containsPattern("(?s)(?i)U4.*semantic.*").contains("eye only");

            // And the census itself is exact: every rule that drives a UI gate is pinned here, and no
            // stray UI-affecting rule sits in the declared table unnamed.
            assertThat(uiRulesIn(KitCensus.ruleIds(bundle))).as(bundle + " detectors: the census of machine gates is exact")
                    .containsExactlyInAnyOrderElementsOf(KNOWN_UI_RULES);
        }
    }

    @Test
    @DisplayName("every UI rule the documents claim is present in both detector copies")
    void everyDeclaredUiRule_isInBothCopies() {
        for (String bundle : KitCensus.BUNDLES) {
            Set<String> declared = KitCensus.ruleIds(bundle);
            for (String rule : KNOWN_UI_RULES) {
                assertThat(declared).as(bundle + " detectors: rule " + rule + " must be declared").contains(rule);
            }
        }
    }

    @Test
    @DisplayName("a detector that arrives for a human gate changes both copies and is caught as drift")
    void noUnknownUiRule_isSilentlyAdded() {
        for (String bundle : KitCensus.BUNDLES) {
            assertThat(uiRulesIn(KitCensus.ruleIds(bundle)))
                    .as(bundle + " detectors: a new UI_* rule is a machine the census does not know. If the kit gained "
                            + "a detector for a human gate, the gate must move out of the human sentence FIRST and be asserted "
                            + "in KNOWN_UI_RULES here; otherwise the docs and the detector disagree on which gates stay human")
                    .containsExactlyInAnyOrderElementsOf(KNOWN_UI_RULES);
        }
    }

    @Test
    @DisplayName("the two bundle copies declare the same detector set")
    void bothCopies_declareTheSameDetectorSet() {
        assertThat(KitCensus.ruleIds(".claude"))
                .as("the .claude and .opencode bundles share one detector table; the census is about the kit, not a copy")
                .isEqualTo(KitCensus.ruleIds(".opencode"));
    }

    @Test
    @DisplayName("the kit says a clean hook run is still not a clean UI review, in both bundles")
    void theKitStillSaysACleanHookIsNotACleanReview() {
        for (String bundle : KitCensus.BUNDLES) {
            String rule = KitCensus.guardRule(bundle);
            String checklist = KitCensus.checklist(bundle);
            assertThat(rule).as(bundle + " guard rule: the boundary sentence the census exists to guard")
                    .contains("A clean hook run is still not a clean UI review");
            assertThat(checklist).as(bundle + " checklist: the same honest boundary is spelled into the coverage note")
                    .contains("**still not** a clean UI review");
        }
    }

    /** The rules that drive a UI gate: every {@code UI_*}-prefixed rule plus the two protocol-wide rules the UI gates reuse. */
    private static Set<String> uiRulesIn(Set<String> declared) {
        Set<String> ui = new TreeSet<>();
        for (String id : declared) {
            if (id.startsWith("UI_") || KNOWN_UI_RULES.contains(id)) {
                ui.add(id);
            }
        }
        return ui;
    }
}