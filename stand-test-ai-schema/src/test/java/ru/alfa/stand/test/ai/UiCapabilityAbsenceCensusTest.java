package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The census nobody had: the kit may not tell an agent that the SDK lacks something the SDK has.
 *
 * <p>Every other pin in this module holds a POSITIVE claim — that a named call exists
 * ({@code AuthoringCribApiCoverageTest}), that a named detector is declared, that a quoted list is
 * quoted verbatim. A negative claim had none, and it rotted exactly as an unpinned claim does: on
 * 2026-08-07 {@code stand-test-ui} gained the failure-artefact lane, and the kit went on saying
 * "screenshots, traces and report attachments — absent; the registry parses {@code trace:} but
 * nothing consumes it yet" in five places for three days. Two of them were TEMPLATES, so the false
 * sentence was copied into the delivered generation report as "скриншот при падении — not covered,
 * SDK" while a failing step was attaching five artefacts to the Allure report. The kit understated
 * the delivery, and the understatement travelled to the reader who decides on a red run.
 *
 * <p><strong>Why this is not simply a list of forbidden phrases.</strong> A phrase list would be a
 * second copy of the correction — it would pass the day it is written and catch nothing afterwards,
 * because tomorrow's false claim will be spelled differently. So neither side of the comparison is
 * written here:
 *
 * <ul>
 *   <li>what the SDK <em>has</em> is probed against {@code stand-test-ui}'s own source — the marker
 *       that the capability exists, e.g. the executor attaching {@code ui-screenshot};</li>
 *   <li>what the kit <em>claims absent</em> is read out of the kit's own absence structures — the
 *       "Not in this version" paragraph, the "Absent from this version" table, the Russian "Чего SDK
 *       не умеет" paragraph, the {@code absent by design} comment of the Page Object template, and
 *       the report rows whose cause cell says SDK.</li>
 * </ul>
 *
 * <p>A new false claim, in a spelling nobody anticipated, is therefore caught as long as it is
 * written where absences are declared — which is where the authoring stages read them.
 *
 * <p><strong>The qualifier, and why it is not a loophole.</strong> "A screenshot ON DEMAND is absent"
 * is TRUE while "screenshots are absent" is false: the artefacts exist on failure only, and no step
 * orders one. The register therefore carries, per capability, the qualifiers that make an absence
 * true ({@code on demand}, {@code assertion}, {@code DOM}). {@link #theQualifiersAreLive()} keeps
 * them honest — a qualifier that rescues nothing today is an amnesty waiting to be used, and a
 * qualifier that rescues everything would mean the rule stopped deciding anything.
 *
 * <p><strong>What this test deliberately does not read.</strong> Only the CLAIM half of each item —
 * the text before the first em dash or parenthesis — is matched, because the half after it is the
 * explanation, and an explanation legitimately names a neighbouring capability ("the mask is a
 * screenshot option"). A false claim hidden inside an explanation is therefore not caught here; it is
 * the price of not producing findings that are wrong, which this repository has already paid for
 * twice. Prose that merely defines vocabulary — {@code stand-test-ui-quality-review/SKILL.md}'s
 * outcome table — is out of scope for the same reason: it is not a claim about a capability.
 */
class UiCapabilityAbsenceCensusTest {

    /** Where the probes look. Declared as a test input in {@code build.gradle.kts}, or they never re-run. */
    private static final String UI_MAIN = "stand-test-ui/src/main/java/ru/alfa/stand/test/ui/";

    /**
     * The capabilities worth probing, both polarities on purpose.
     *
     * <p>The absent ones are not decoration, and the two ways a probe breaks are not the same. A
     * probe whose FILE moved throws while reading it — loud, and no census is needed for that. A probe
     * whose MARKER went stale (a renamed constant, a reworded attachment name) is the silent one: it
     * reads "absent", the main rule below stops firing, and the kit is free to go stale again exactly
     * as it did. {@link #theRegisterProbesBothWays()} is what notices the silent case, by requiring
     * that something is still found.
     */
    private static final List<Capability> REGISTER = List.of(
            new Capability(
                    "failure screenshot",
                    List.of("screenshot", "скриншот"),
                    UI_MAIN + "UiStepExecutor.java",
                    "Attachment.ofFile(\"ui-screenshot\"",
                    true,
                    List.of("on demand", "по требованию"),
                    "a failing ui.* step attaches ui-screenshot (asSensitive() zones masked before the grab); only a screenshot ON DEMAND is absent"),
            new Capability(
                    "failure trace",
                    List.of("trace", "трейс"),
                    UI_MAIN + "UiStepExecutor.java",
                    "Attachment.ofFile(\"ui-trace\"",
                    true,
                    List.of("on demand", "по требованию"),
                    "a failing step attaches ui-trace where the registry declares trace: on-failure; only a trace ON DEMAND is absent"),
            new Capability(
                    "console attachment",
                    List.of("console", "консол"),
                    UI_MAIN + "UiStepExecutor.java",
                    "Attachment.of(\"ui-console\"",
                    true,
                    List.of("assert", "проверк", "interception"),
                    "a failing step attaches ui-console; what is absent is an ASSERTION about the console, not the attachment"),
            new Capability(
                    "network attachment",
                    List.of("network", "сетев"),
                    UI_MAIN + "UiStepExecutor.java",
                    "Attachment.of(\"ui-network\"",
                    true,
                    List.of("assert", "проверк", "interception", "перехват"),
                    "a failing step attaches ui-network; what is absent is INTERCEPTION and assertions about requests, not the attachment"),
            new Capability(
                    "masking of sensitive zones in the failure screenshot",
                    List.of("mask", "маск", "закраш"),
                    UI_MAIN + "UiStepExecutor.java",
                    "maskSensitive(",
                    true,
                    List.of("dom"),
                    "asSensitive() zones are painted over before the screenshot is taken; only masking in the DOM is absent"),
            new Capability(
                    "XPath locators",
                    List.of("xpath"),
                    UI_MAIN + "LocatorStrategy.java",
                    "XPATH",
                    false,
                    List.of(),
                    "there is no XPath strategy — the kit is right to call it absent"),
            new Capability(
                    "file upload step",
                    List.of("upload", "загрузка файл"),
                    UI_MAIN + "UiStep.java",
                    "upload(",
                    false,
                    List.of(),
                    "there is no upload step — the kit is right to call it absent"));

    // ---- reading the kit's absence structures -------------------------------------------------

    private static Path bundleFile(String bundle, String relative) {
        return KitCensus.repositoryRoot().resolve("docs/ai-agent/" + bundle + "/" + relative);
    }

    /**
     * The paragraph that follows a marker, up to the blank line that ends it.
     *
     * <p>Anchored on the marker rather than on a line number, so an edit above it does not silently
     * move the census onto the wrong text — it makes the marker missing, and a missing marker is a
     * failure of {@link #everyAbsenceStructureWasActuallyParsed()} rather than a quiet pass.
     */
    private static String paragraphAfter(Path file, String marker) {
        String text = KitCensus.read(file);
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int from = start + marker.length();
        int end = text.indexOf("\n\n", from);
        return (end < 0) ? text.substring(from) : text.substring(from, end);
    }

    /** Items of a list written inline in a paragraph, split on the separator that document uses. */
    private static List<AbsenceItem> paragraphItems(String source, Path file, String marker, String separator) {
        List<AbsenceItem> items = new ArrayList<>();
        for (String piece : paragraphAfter(file, marker).split(separator)) {
            String trimmed = piece.replace('\n', ' ').trim();
            if (!trimmed.isBlank()) {
                items.add(new AbsenceItem(source, trimmed));
            }
        }
        return items;
    }

    /**
     * The first column of the "Absent from this version" table — the WANTED capability.
     *
     * <p>The Status column beside it is the explanation and is not read, for the reason the class
     * javadoc gives. The header is verified rather than the index trusted: a reordered table must
     * fail loudly instead of quietly censusing the wrong column.
     */
    private static List<AbsenceItem> absentTableItems(String bundle) {
        Path file = bundleFile(bundle, "skills/stand-test-ui-java-authoring/ui-sdk-surface-checklist.md");
        String table = paragraphAfter(file, "## Absent from this version — do not write it\n");
        List<AbsenceItem> items = new ArrayList<>();
        boolean headerSeen = false;
        for (String line : table.split("\n")) {
            String row = line.trim();
            if (!row.startsWith("|")) {
                continue;
            }
            List<String> cells = cellsOf(row);
            if (!headerSeen) {
                assertThat(cells.get(0).toLowerCase(Locale.ROOT))
                        .as(bundle + ": the absence table's first column is expected to be the WANTED capability; if it was reordered, "
                                + "this census would start reading the explanation column and stop deciding anything")
                        .isEqualTo("wanted");
                headerSeen = true;
                continue;
            }
            if (row.startsWith("|---") || row.startsWith("| ---")) {
                continue;
            }
            items.add(new AbsenceItem(bundle + "/…/ui-sdk-surface-checklist.md", cells.get(0)));
        }
        return items;
    }

    /**
     * Rows of a template whose cause is the SDK — the rows that become rows of a delivered report.
     *
     * <p>What is read is everything to the LEFT of the cause cell: that is the expectation being
     * called uncoverable. What stands to the right explains the cause and is not a claim, the same
     * split the class javadoc applies to prose. The column INDEX is never assumed — the two templates
     * put the cause in different positions, and hard-coding either would have read the wrong cell of
     * the other.
     */
    private static List<AbsenceItem> sdkCauseRows(String bundle, String relative) {
        Path file = bundleFile(bundle, relative);
        List<AbsenceItem> items = new ArrayList<>();
        for (String line : KitCensus.read(file).split("\n")) {
            String row = line.trim();
            if (!row.startsWith("|")) {
                continue;
            }
            List<String> cells = cellsOf(row);
            int cause = -1;
            for (int i = 0; i < cells.size(); i++) {
                String cell = cells.get(i);
                if (cell.contains("**SDK**") || cell.toLowerCase(Locale.ROOT).contains("not covered — sdk")) {
                    cause = i;
                    break;
                }
            }
            if (cause > 0) {
                items.add(new AbsenceItem(bundle + "/" + relative, String.join(" ", cells.subList(0, cause))));
            }
        }
        return items;
    }

    /**
     * The {@code absent by design:} line of the Page Object template, plus its continuation.
     *
     * <p>Bounded at the first comment line that ends the sentence: the label after it
     * ({@code automatic, not authored:}) says the OPPOSITE, and an extraction that overshot into it
     * would report the truth as a violation.
     */
    private static List<AbsenceItem> pageObjectTemplateItems(String bundle) {
        Path file = bundleFile(bundle, "skills/stand-test-ui-page-object-design/page-object-template.java");
        StringBuilder block = new StringBuilder();
        boolean collecting = false;
        for (String line : KitCensus.read(file).split("\n")) {
            String comment = line.trim();
            if (!collecting && comment.contains("absent by design:")) {
                collecting = true;
                comment = comment.substring(comment.indexOf("absent by design:") + "absent by design:".length());
            } else if (collecting) {
                comment = comment.startsWith("//") ? comment.substring(2) : comment;
            } else {
                continue;
            }
            block.append(' ').append(comment.trim());
            if (comment.trim().endsWith(".")) {
                break;
            }
        }
        List<AbsenceItem> items = new ArrayList<>();
        for (String piece : block.toString().split(";")) {
            String trimmed = piece.trim();
            if (!trimmed.isBlank()) {
                items.add(new AbsenceItem(bundle + "/…/page-object-template.java", trimmed));
            }
        }
        return items;
    }

    private static List<String> cellsOf(String row) {
        List<String> cells = new ArrayList<>();
        for (String cell : row.split("\\|")) {
            if (!cell.isBlank()) {
                cells.add(cell.trim());
            }
        }
        return cells;
    }

    /** Every absence the kit declares, from all five structures of one bundle copy. */
    private static List<AbsenceItem> absenceItems(String bundle) {
        List<AbsenceItem> items = new ArrayList<>();
        items.addAll(paragraphItems(
                bundle + "/rules/stand-test-ui-guardrails.md",
                bundleFile(bundle, "rules/stand-test-ui-guardrails.md"),
                "**Not in this version — do not write it:**",
                ";"));
        items.addAll(absentTableItems(bundle));
        items.addAll(paragraphItems(
                bundle + "/…/ui-case-template.md",
                bundleFile(bundle, "skills/stand-test-ui-case-intake/ui-case-template.md"),
                "Чего SDK **не умеет** в этой версии",
                ","));
        items.addAll(pageObjectTemplateItems(bundle));
        items.addAll(sdkCauseRows(bundle, "skills/stand-test-ui-generation-report/ui-generation-report-template.md"));
        items.addAll(sdkCauseRows(bundle, "skills/stand-test-ui-quality-review/ui-quality-checklist.md"));
        return items;
    }

    // ---- the rule ------------------------------------------------------------------------------

    /**
     * The claim half of an item: what stands before the explanation.
     *
     * <p>Both documents introduce an explanation the same two ways — an em dash or a parenthesis —
     * and an explanation names neighbouring capabilities on purpose ("the mask is a screenshot
     * option"). Matching the whole item would turn those sentences into findings.
     */
    private static String claimOf(String item) {
        int cut = item.length();
        for (String opener : List.of("—", "(")) {
            int at = item.indexOf(opener);
            if (at >= 0) {
                cut = Math.min(cut, at);
            }
        }
        return item.substring(0, cut).toLowerCase(Locale.ROOT);
    }

    private static boolean carriesQualifier(String claim, Capability capability) {
        return capability.qualifiers().stream().anyMatch(claim::contains);
    }

    private static boolean names(String claim, Capability capability) {
        return capability.keywords().stream().anyMatch(claim::contains);
    }

    @Test
    @DisplayName("no absence the kit declares names a capability stand-test-ui actually has")
    void noAbsenceClaimNamesACapabilityTheSdkHas() {
        List<String> violations = new ArrayList<>();
        for (String bundle : KitCensus.BUNDLES) {
            for (AbsenceItem item : absenceItems(bundle)) {
                String claim = claimOf(item.text());
                for (Capability capability : REGISTER) {
                    if (names(claim, capability) && !carriesQualifier(claim, capability) && capability.presentInSdk()) {
                        violations.add(item.source() + ": \"" + item.text() + "\" calls '" + capability.name()
                                + "' absent, but " + capability.probeFile() + " contains " + capability.marker()
                                + ". Truth: " + capability.truth());
                    }
                }
            }
        }
        assertThat(violations)
                .as("The kit may understate nothing. An entry here is a sentence telling an authoring stage that the SDK cannot do "
                        + "something it does — and two of these structures are TEMPLATES, so the sentence is copied into the delivered "
                        + "generation report as a gap that does not exist. Fix the document, or narrow the claim with the qualifier that "
                        + "makes it true (a screenshot ON DEMAND really is absent). Do not add a qualifier that is not true")
                .isEmpty();
    }

    @Test
    @DisplayName("every absence structure was actually parsed — a renamed heading must not pass as an empty census")
    void everyAbsenceStructureWasActuallyParsed() {
        for (String bundle : KitCensus.BUNDLES) {
            assertThat(paragraphItems(bundle, bundleFile(bundle, "rules/stand-test-ui-guardrails.md"), "**Not in this version — do not write it:**", ";"))
                    .as(bundle + ": the guard rule's 'Not in this version' paragraph parsed empty — the marker was renamed, and this "
                            + "census would silently stop reading the document it exists for")
                    .hasSizeGreaterThan(3);
            assertThat(absentTableItems(bundle))
                    .as(bundle + ": the surface checklist's absence table parsed empty")
                    .hasSizeGreaterThan(3);
            assertThat(paragraphItems(bundle, bundleFile(bundle, "skills/stand-test-ui-case-intake/ui-case-template.md"), "Чего SDK **не умеет** в этой версии", ","))
                    .as(bundle + ": the case template's Russian absence paragraph parsed empty")
                    .hasSizeGreaterThan(3);
            assertThat(pageObjectTemplateItems(bundle))
                    .as(bundle + ": the Page Object template's 'absent by design' comment parsed empty")
                    .hasSizeGreaterThan(1);
            assertThat(pageObjectTemplateItems(bundle))
                    .as(bundle + ": the 'absent by design' extraction ran past its sentence into the line that says the opposite — "
                            + "bounded extraction is what keeps this census from reporting the truth as a violation")
                    .noneMatch(item -> item.text().contains("automatic, not authored"));
            assertThat(sdkCauseRows(bundle, "skills/stand-test-ui-generation-report/ui-generation-report-template.md"))
                    .as(bundle + ": no row of the generation-report template carries the SDK cause any more — the template teaches the "
                            + "vocabulary by example, and an example that disappeared is a census reading nothing")
                    .isNotEmpty();
            assertThat(sdkCauseRows(bundle, "skills/stand-test-ui-quality-review/ui-quality-checklist.md"))
                    .as(bundle + ": no row of the quality checklist carries the SDK outcome any more")
                    .isNotEmpty();
        }
    }

    /**
     * The half that fires on the event which actually caused the defect.
     *
     * <p>The main rule catches somebody WRITING a false claim. It does not catch the SDK making a
     * true claim false, and that is what happened: nothing in the kit changed on 2026-08-07 — the
     * adapter gained the artefact lane, every sentence about it stayed as it was, and no test could
     * have gone red because none of them looked at the adapter. Recording the probe's answer turns
     * that event into a failing test on the commit that causes it, which is the only moment when
     * re-reading the kit's absence lists is cheap.
     *
     * <p>The recorded answer is therefore not a second source of truth to be trusted — it is a
     * tripwire. Flipping it is the correct fix, but only together with the documents: the message says
     * so, because a register updated alone would restore exactly the silence it exists to break.
     */
    @Test
    @DisplayName("the SDK still answers what the register recorded — a capability that appeared or vanished must be noticed here")
    void theRegisterAgreesWithTheSdk() {
        for (Capability capability : REGISTER) {
            assertThat(capability.presentInSdk())
                    .as("stand-test-ui changed under the kit: '" + capability.name() + "' was recorded as "
                            + (capability.expectedInSdk() ? "PRESENT" : "ABSENT") + " and probes the other way now ("
                            + capability.probeFile() + " ↔ " + capability.marker() + "). Do not flip this flag on its own — that is the "
                            + "silence this test exists to break. Re-read the kit's absence lists first: the guard rule, the surface "
                            + "checklist, the case template, the Page Object template and the two report templates. Then flip it, in the "
                            + "same change. If the marker merely went stale, repoint it — a stale marker reads ABSENT and quietly "
                            + "switches the main rule off")
                    .isEqualTo(capability.expectedInSdk());
        }
    }

    @Test
    @DisplayName("the register probes both ways: some capabilities are found in the SDK and some are genuinely absent")
    void theRegisterProbesBothWays() {
        for (Capability capability : REGISTER) {
            Path probe = KitCensus.repositoryRoot().resolve(capability.probeFile());
            assertThat(Files.isRegularFile(probe))
                    .as("the probe for '" + capability.name() + "' reads " + capability.probeFile() + ", which is not there — the file "
                            + "moved and the register did not follow. This one is loud on its own (reading it throws); the assertion "
                            + "exists so the failure names the register entry to repoint instead of a stack trace deep in a helper")
                    .isTrue();
        }
        assertThat(REGISTER.stream().filter(Capability::presentInSdk).map(Capability::name))
                .as("no registered capability was found in stand-test-ui at all. Either every marker went stale at once, or the probe "
                        + "mechanism is broken — in both cases the main rule below can no longer fire")
                .isNotEmpty();
        assertThat(REGISTER.stream().filter(capability -> !capability.presentInSdk()).map(Capability::name))
                .as("every registered capability probes PRESENT, so the register has lost its negative control: nothing proves a probe "
                        + "can return ABSENT rather than always saying yes")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the qualifiers are live: each one really rescues a claim today, and none rescues them all")
    void theQualifiersAreLive() {
        List<String> rescued = new ArrayList<>();
        for (String bundle : KitCensus.BUNDLES) {
            for (AbsenceItem item : absenceItems(bundle)) {
                String claim = claimOf(item.text());
                for (Capability capability : REGISTER) {
                    if (names(claim, capability) && carriesQualifier(claim, capability) && capability.presentInSdk()) {
                        rescued.add(capability.name() + " ← " + item.text());
                    }
                }
            }
        }
        assertThat(rescued)
                .as("no claim is rescued by a qualifier any more. The qualifier list is what lets the kit say the TRUE half — 'a "
                        + "screenshot ON DEMAND is absent' — and a list that rescues nothing is an unused amnesty: it will be reached "
                        + "for the next time a false claim needs to pass, and nothing will have proven it still narrows anything")
                .isNotEmpty();
    }

    // ---- the register's two value types, last because checkstyle puts inner types after members ----

    /**
     * A capability the kit's absence lists talk about, and the evidence that decides whether it exists.
     *
     * @param name how this census names it in a failure message
     * @param keywords how an absence item would spell it, in either language of the bundle
     * @param probeFile the SDK source that answers "does this exist", repository-relative
     * @param marker the text in that file whose presence means the capability is real
     * @param expectedInSdk what the probe answered when this entry was written — see
     *     {@link #theRegisterAgreesWithTheSdk()} for why the answer is recorded rather than trusted
     * @param qualifiers the words that narrow an absence into a TRUE one
     * @param truth what the kit should say instead, quoted into the failure message
     */
    private record Capability(String name, List<String> keywords, String probeFile, String marker, boolean expectedInSdk, List<String> qualifiers, String truth) {

        boolean presentInSdk() {
            return KitCensus.read(KitCensus.repositoryRoot().resolve(probeFile)).contains(marker);
        }
    }

    /** One absence item, kept with its origin so a failure names the file to fix. */
    private record AbsenceItem(String source, String text) {
    }
}
