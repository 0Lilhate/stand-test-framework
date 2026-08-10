package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every number the kit's own prose states about {@code detectors.json} is the number that file declares.
 *
 * <p>{@link UiReadinessCensusTest} already holds ONE document — the wave-1 readiness report — against the
 * detector table. Nothing held the kit's own documents, and they drifted exactly as the readiness report
 * had: {@code usage-guide.md} claimed "25 находок, из них семь UI-специфичных" against 26 and eight, the
 * training walkthrough quoted a scan output from a kit that had 18 detectors and none UI-specific, the
 * safety-review skill described the scan's own summary line with a stale denominator, and the pipeline
 * rationale stated per-artefact-kind counts from the same era. Four documents, one cause: a number
 * written by hand into prose that no test reads.
 *
 * <p>The drift is worse than an ordinary stale sentence, because every one of these numbers is an
 * argument about MACHINE COVERAGE — how much of the review a hook already did. Understating it tells a
 * reviewer to redo work the machine does; overstating it tells them to skip work nothing does. The
 * walkthrough had reached the second: it told a newcomer that not one finding is UI-specific, at a
 * version carrying eight.
 *
 * <p>Neither side of any comparison is written here. The totals come from {@code detectors.json}, the
 * protocol/UI split from the protocol safety-review skill's own findings table (the numbering
 * {@code detectors.json} says it follows), and the per-kind counts from {@code appliesTo}/{@code notOn}.
 * A hand-copied expectation would be a fifth place to go stale.
 */
class KitDetectorClaimCensusTest {

    /** The bundle whose detector table is the reference; {@link BundleParityTest} holds the copies equal. */
    private static final String BUNDLE = ".claude";

    /** The scan's own summary line. The numerator varies by artefact; the DENOMINATOR is always the table. */
    private static final Pattern SCAN_SUMMARY = Pattern.compile("проверено находок: (?:\\d+|N) из (\\d+)");

    /**
     * A stated table size, Russian ("**26 находок") and English ("26 safety findings").
     *
     * <p>The whitespace is {@code \s+} in every pattern here rather than a literal space: these
     * documents wrap at 100 columns, so a count and the noun it counts are routinely split across a
     * line. A pattern that missed those would go quiet exactly where the sentence is longest.
     */
    private static final Pattern TOTAL_RU = Pattern.compile("(\\d+)\\s+находок");

    private static final Pattern TOTAL_EN = Pattern.compile("(\\d+)\\s+safety findings");

    /**
     * A stated UI-specific count: the pinned spelling, Russian and English.
     *
     * <p>The Russian half is anchored on "из них" rather than on the adjacency alone, because
     * "UI-специфичн-" also appears in ordinary prose ("не <b>является</b> UI-специфичной") where the
     * preceding word is a verb, not a count. The cost of the anchor is that a document rewritten past
     * it leaves this census — which is what the non-vacuity test is for.
     */
    private static final Pattern UI_RU = Pattern.compile("из них\\s+(\\S+)\\s+UI-специфичн");

    private static final Pattern UI_EN = Pattern.compile("(\\d+)\\s+UI-specific");

    /** The rationale's per-kind sentence, written in digits so that it can be read mechanically. */
    private static final Pattern KIND_SPLIT =
            Pattern.compile("у java-файла предмет проверки — (\\d+) из (\\d+), у документа — (\\d+), у прозы — (\\d+)");

    /** The safety-review skill's two prose enumerations: which findings run over `.md`, and which do not. */
    private static final Pattern PROSE_APPLICABLE = Pattern.compile("run over prose: ([\\d, and]+)\\.");

    private static final Pattern PROSE_EXCLUDED = Pattern.compile("findings ([\\d, and]+) are about DELIVERY");

    /**
     * Russian number words the kit actually uses for these counts.
     *
     * <p>Spelling, not knowledge: the map turns a word into the digit it already is, and nothing here
     * decides what the right count would be. A word outside the map fails the test with the word quoted,
     * which is the correct outcome — an unreadable claim is not a claim that passes.
     */
    private static int russianNumeral(String word) {
        List<String> words = List.of("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь",
                "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
                "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать", "двадцать");
        int index = words.indexOf(word.toLowerCase(java.util.Locale.ROOT));
        assertThat(index).as("'%s' is not a number word this census can read — write the count as a digit", word).isNotNegative();
        return index;
    }

    /** Every markdown document of the kit, both bundle copies and the guides beside them. */
    private static List<Path> kitDocuments() {
        Path kit = KitCensus.repositoryRoot().resolve("docs/ai-agent");
        try (Stream<Path> tree = Files.walk(kit)) {
            return tree.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + kit, e);
        }
    }

    /**
     * A document with every run of whitespace collapsed to one space.
     *
     * <p>These documents wrap at 100 columns and are rewrapped whenever a sentence is edited, so a
     * claim regularly straddles a line break — and a pattern written against the wrapped form would go
     * silent on the next reflow rather than fail. Collapsing first makes a claim's text independent of
     * where it happens to wrap today.
     */
    private static String normalized(Path document) {
        return KitCensus.read(document).replaceAll("\\s+", " ");
    }

    private static int totalDetectors() {
        return KitCensus.ruleIds(BUNDLE).size();
    }

    /**
     * The findings the protocol safety-review skill numbers — rows of its table, read from the document
     * that owns the numbering.
     *
     * <p>{@code detectors.json} says outright that its numbering comes from that skill, so the skill is
     * the authority on which half of the table is protocol. Deriving the split from the finding numbers
     * alone ("everything above 18") would encode today's ordering as a rule.
     */
    private static Set<Integer> protocolFindings() {
        Path skill = KitCensus.repositoryRoot()
                .resolve("docs/ai-agent/" + BUNDLE + "/skills/stand-test-safety-review/SKILL.md");
        Pattern row = Pattern.compile("^\\|\\s*(\\d+)\\s*\\|", Pattern.MULTILINE);
        Set<Integer> findings = new TreeSet<>();
        Matcher rows = row.matcher(KitCensus.read(skill));
        while (rows.find()) {
            findings.add(Integer.parseInt(rows.group(1)));
        }
        return findings;
    }

    /** The detectors that exist for the UI branch: the ones the protocol skill's table does not number. */
    private static Set<String> uiSpecificDetectors() {
        Set<Integer> protocolFindings = protocolFindings();
        Set<String> uiSpecific = new TreeSet<>();
        for (JsonNode detector : KitCensus.detectors(BUNDLE)) {
            if (!protocolFindings.contains(detector.path("finding").asInt())) {
                uiSpecific.add(detector.path("ruleId").asText());
            }
        }
        return uiSpecific;
    }

    /**
     * How many detectors a given artefact kind is the subject of — the scanner's own rule, mirrored.
     *
     * <p>{@code appliesTo} names the kind, {@code any} means every kind except those {@code notOn}
     * excludes. Nothing else enters: whether a detector then RAN also depends on the previous version
     * being available, and that is a property of the invocation rather than of the kind.
     */
    private static int applicableTo(String kind) {
        int count = 0;
        for (JsonNode detector : KitCensus.detectors(BUNDLE)) {
            String appliesTo = detector.path("appliesTo").asText();
            boolean excluded = false;
            for (JsonNode not : detector.path("notOn")) {
                excluded = excluded || kind.equals(not.asText());
            }
            if (kind.equals(appliesTo) || ("any".equals(appliesTo) && !excluded)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The findings a given artefact kind is the subject of, by number rather than by count.
     *
     * <p>{@link #applicableTo} answers "how many"; this answers "which", and the prose enumerations of
     * the safety-review skill are claims of the second kind. A count alone would have passed the
     * sentence that named findings 2, 13 and 14 and silently omitted 4, 18, 24 and 26.
     */
    private static Set<Integer> findingsApplicableTo(String kind) {
        Set<Integer> findings = new TreeSet<>();
        for (JsonNode detector : KitCensus.detectors(BUNDLE)) {
            String appliesTo = detector.path("appliesTo").asText();
            boolean excluded = false;
            for (JsonNode not : detector.path("notOn")) {
                excluded = excluded || kind.equals(not.asText());
            }
            if (kind.equals(appliesTo) || ("any".equals(appliesTo) && !excluded)) {
                findings.add(detector.path("finding").asInt());
            }
        }
        return findings;
    }

    /** The findings whose {@code notOn} takes them off a kind they would otherwise apply to. */
    private static Set<Integer> findingsExcludedFrom(String kind) {
        Set<Integer> findings = new TreeSet<>();
        for (JsonNode detector : KitCensus.detectors(BUNDLE)) {
            for (JsonNode not : detector.path("notOn")) {
                if (kind.equals(not.asText())) {
                    findings.add(detector.path("finding").asInt());
                }
            }
        }
        return findings;
    }

    /** The numbers of an English enumeration — "2, 4, 13, 14, 18, 24 and 26". */
    private static Set<Integer> enumerated(String list) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher digits = Pattern.compile("\\d+").matcher(list);
        while (digits.find()) {
            numbers.add(Integer.parseInt(digits.group()));
        }
        return numbers;
    }

    private static List<Claim> claims(Pattern pattern, boolean numeric) {
        List<Claim> claims = new ArrayList<>();
        for (Path document : kitDocuments()) {
            Matcher matcher = pattern.matcher(normalized(document));
            while (matcher.find()) {
                String stated = matcher.group(1);
                claims.add(new Claim(document, matcher.group(), numeric ? Integer.parseInt(stated) : russianNumeral(stated)));
            }
        }
        return claims;
    }

    @Test
    @DisplayName("every stated table size is the number of detectors detectors.json declares")
    void statedTableSizes_matchTheDetectorTable() {
        List<Claim> stated = new ArrayList<>(claims(TOTAL_RU, true));
        stated.addAll(claims(TOTAL_EN, true));
        stated.addAll(claims(SCAN_SUMMARY, true));

        assertThat(stated)
                .as("no document states the table size in a spelling this census can read — a corpus with nothing to "
                        + "check is how the count went stale in four documents at once")
                .isNotEmpty();
        assertThat(stated)
                .as("detectors.json declares %d findings. Re-measure the documents, never the table: every one of these "
                        + "numbers is a claim about how much of the review the machine already did", totalDetectors())
                .allSatisfy(claim -> assertThat(claim.value()).as("%s", claim).isEqualTo(totalDetectors()));
    }

    @Test
    @DisplayName("every stated UI-specific count is the number of detectors the protocol table does not number")
    void statedUiSpecificCounts_matchTheUiHalfOfTheTable() {
        List<Claim> stated = new ArrayList<>(claims(UI_RU, false));
        stated.addAll(claims(UI_EN, true));

        assertThat(stated).as("the kit must state somewhere how many of its detectors exist for the UI branch").isNotEmpty();
        assertThat(stated)
                .as("the detectors the protocol safety-review table does not number are %s — %d of them", uiSpecificDetectors(),
                        uiSpecificDetectors().size())
                .allSatisfy(claim -> assertThat(claim.value()).as("%s", claim).isEqualTo(uiSpecificDetectors().size()));
    }

    @Test
    @DisplayName("the per-kind counts of the pipeline rationale are the counts appliesTo/notOn produce")
    void statedPerKindCounts_matchTheDetectorTable() {
        List<Path> stating = new ArrayList<>();
        for (Path document : kitDocuments()) {
            Matcher matcher = KIND_SPLIT.matcher(normalized(document));
            while (matcher.find()) {
                stating.add(document);
                String where = KitCensus.repositoryRoot().relativize(document).toString();
                assertThat(Integer.parseInt(matcher.group(1))).as("%s: detectors a java artefact is the subject of", where).isEqualTo(applicableTo("java"));
                assertThat(Integer.parseInt(matcher.group(2))).as("%s: the table size", where).isEqualTo(totalDetectors());
                assertThat(Integer.parseInt(matcher.group(3))).as("%s: detectors a declarative document is the subject of", where).isEqualTo(applicableTo("document"));
                assertThat(Integer.parseInt(matcher.group(4))).as("%s: detectors prose is the subject of", where).isEqualTo(applicableTo("prose"));
            }
        }

        assertThat(stating)
                .as("the rationale's argument is that the subject of a scan depends on the artefact kind, and it makes that "
                        + "argument with three numbers. A sentence rewritten past this pattern takes them out of every check")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the safety-review skill names exactly the findings that do and do not run over prose")
    void statedProseEnumerations_matchTheDetectorTable() {
        List<Path> stating = new ArrayList<>();
        for (Path document : kitDocuments()) {
            String text = normalized(document);
            Matcher applicable = PROSE_APPLICABLE.matcher(text);
            String where = KitCensus.repositoryRoot().relativize(document).toString();
            while (applicable.find()) {
                stating.add(document);
                assertThat(enumerated(applicable.group(1)))
                        .as("%s: the reviewer reads this list to know what the hook already did over a `.md`. It named "
                                + "2, 13 and 14 while 4 (a production stand in a design), 18, and the two report "
                                + "detectors 24/26 also ran — an understatement costs the reviewer the work it hides", where)
                        .isEqualTo(findingsApplicableTo("prose"));
            }
            Matcher excluded = PROSE_EXCLUDED.matcher(text);
            while (excluded.find()) {
                assertThat(enumerated(excluded.group(1)))
                        .as("%s: the DELIVERY findings are exactly those whose notOn takes them off prose", where)
                        .isEqualTo(findingsExcludedFrom("prose"));
            }
        }

        assertThat(stating)
                .as("the safety-review skill must keep saying WHICH findings run over prose, in the pinned spelling — a "
                        + "sentence rewritten past this pattern leaves the census with nothing to check")
                .isNotEmpty();
    }

    @Test
    @DisplayName("neither side was vacuously parsed — the census really read the table and the corpus")
    void neitherSide_isVacuous() {
        assertThat(kitDocuments()).as("the kit's markdown corpus must be found, else every claim above is checked over nothing").hasSizeGreaterThan(20);
        assertThat(totalDetectors()).as("a detectors.json this parse cannot read makes every count meaningless").isGreaterThanOrEqualTo(20);
        assertThat(protocolFindings())
                .as("the protocol skill's findings table must parse, else EVERY detector reads as UI-specific")
                .hasSizeGreaterThanOrEqualTo(18);
        assertThat(uiSpecificDetectors())
                .as("the UI half must parse to a non-empty set naming the detectors the UI branch added")
                .contains("UI_DISCOVERY_PARITY", "UI_GENERATION_REPORT_INCOMPLETE");
        assertThat(uiSpecificDetectors())
                .as("every detector this census calls UI-specific must also be one the UI coverage table credits — two "
                        + "documents disagreeing about which detectors serve the UI branch is the drift one census cannot see")
                .isSubsetOf(KitCensus.namedDetectors(BUNDLE));
        assertThat(applicableTo("prose")).as("notOn must really be read — ignoring it would make every kind's count the table size").isLessThan(totalDetectors());
    }

    /** One number a document states, kept with where it was written so a failure names the file. */
    private record Claim(Path document, String stated, int value) {

        @Override
        public String toString() {
            return KitCensus.repositoryRoot().relativize(document) + ": '" + stated + "'";
        }
    }
}
