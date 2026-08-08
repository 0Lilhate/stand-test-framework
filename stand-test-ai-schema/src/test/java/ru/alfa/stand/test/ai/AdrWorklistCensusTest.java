package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the ADR worklist against the ADR documents it summarises.
 *
 * <p>{@code planning/11-adr-worklist.md} is not commentary: every {@code ADR}-typed backlog card names it
 * in its {@code definition_of_ready} ("варианты описаны в 11-adr-worklist.md"), so it is the document an
 * architect opens in order to decide. That makes one particular drift expensive — an ADR that has been
 * accepted while its worklist row still says {@code PROPOSED} tells the reader that a decision they
 * already made is still owed. That is not hypothetical: it is what this test was written for. On
 * 2026-08-08 row {@code D-01} announced ADR-UI-005 as {@code PROPOSED}, "решение не принято", and the
 * closing line named it among the two decisions blocking the start of wave 1 — while the document itself
 * had read {@code Accepted · Принят: 2026-08-04} for four days and the whole artefact slice it supposedly
 * blocked had shipped.
 *
 * <p>The device is the one the kit's other censuses already use ({@link UiHumanGateCensusTest},
 * {@link UiMachineGateCensusTest}, {@link UiReadinessCensusTest}): the claim is read out of the document
 * and compared with the thing it describes, rather than restated in the test. What is pinned here is the
 * <em>status</em> of each decision — the part a reader acts on. Prose, options and recommendations are
 * deliberately not pinned: they are judgement, and a test that froze them would only be a second copy.
 */
class AdrWorklistCensusTest {

    /** The worklist, relative to the repository root. */
    private static final String WORKLIST = "docs/ui-test-generation/planning/11-adr-worklist.md";

    /** The directory holding the ADR documents the worklist summarises. */
    private static final String ADR_DIR = "docs/ui-test-generation/adr";

    /** A detail section heading: {@code ## D-03 · ADR-UI-004 — ...}. */
    private static final Pattern SECTION = Pattern.compile("^## (D-\\d+) · (ADR-UI-\\d+)", Pattern.MULTILINE);

    /** The status row inside a detail section. */
    private static final Pattern STATUS_ROW = Pattern.compile("^\\|\\s*\\*\\*Статус\\*\\*\\s*\\|(.*)$", Pattern.MULTILINE);

    /** A row of the closing summary table: {@code | **D-01** | ADR-UI-005 вложения | `PROPOSED` | ...}. */
    private static final Pattern SUMMARY_ROW = Pattern.compile("^\\|\\s*\\*\\*(D-\\d+)\\*\\*\\s*\\|([^|]*)\\|([^|]*)\\|", Pattern.MULTILINE);

    /**
     * The vocabulary of statuses the worklist's own header defines, longest-first so that
     * {@code ACCEPT_POST_HOC} is never read as {@code ACCEPTED}.
     */
    private static final Pattern STATUS_TOKEN = Pattern.compile("ACCEPT_POST_HOC|ACCEPTED|PROPOSED|REQUIRED");

    /** The {@code **Статус:**} line of an ADR document. */
    private static final Pattern ADR_STATUS = Pattern.compile("\\*\\*Статус:\\*\\*\\s*([^·\\n]+)");

    /** Any ADR reference, wherever it is named. */
    private static final Pattern ADR_REF = Pattern.compile("ADR-UI-\\d+");

    /** The paragraph listing the decisions that must not be reopened. */
    private static final String SETTLED_MARKER = "**Не требуют нового решения**";

    private static String worklist() {
        return KitCensus.read(KitCensus.repositoryRoot().resolve(WORKLIST));
    }

    /** Every ADR document on disk, mapped from its {@code ADR-UI-xxx} identifier to its declared status. */
    private static Map<String, String> adrStatuses() {
        Path dir = KitCensus.repositoryRoot().resolve(ADR_DIR);
        Map<String, String> statuses = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(f -> f.getFileName().toString().startsWith("ADR-UI-")).sorted().toList();
            for (Path file : sorted) {
                Matcher id = ADR_REF.matcher(file.getFileName().toString());
                if (!id.find()) {
                    continue;
                }
                Matcher status = ADR_STATUS.matcher(KitCensus.read(file));
                assertThat(status.find()).as("%s declares a **Статус:** line", file.getFileName()).isTrue();
                statuses.put(id.group(), status.group(1).trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + dir, e);
        }
        return statuses;
    }

    /** The detail sections of the worklist: {@code D-id} to the ADR it decides and the status it reports. */
    private static Map<String, Section> sections() {
        String text = worklist();
        Map<String, Section> sections = new LinkedHashMap<>();
        List<int[]> bounds = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        Matcher heading = SECTION.matcher(text);
        while (heading.find()) {
            bounds.add(new int[] {heading.end(), text.length()});
            heads.add(new String[] {heading.group(1), heading.group(2)});
            if (bounds.size() > 1) {
                bounds.get(bounds.size() - 2)[1] = heading.start();
            }
        }
        for (int i = 0; i < heads.size(); i++) {
            String body = text.substring(bounds.get(i)[0], bounds.get(i)[1]);
            Matcher row = STATUS_ROW.matcher(body);
            assertThat(row.find()).as("section %s carries a **Статус** row", heads.get(i)[0]).isTrue();
            sections.put(heads.get(i)[0], new Section(heads.get(i)[1], statusToken(heads.get(i)[0], row.group(1))));
        }
        return sections;
    }

    /** The closing summary table: {@code D-id} to the status it reports. */
    private static Map<String, String> summary() {
        Map<String, String> rows = new LinkedHashMap<>();
        Matcher row = SUMMARY_ROW.matcher(worklist());
        while (row.find()) {
            rows.put(row.group(1), statusToken(row.group(1), row.group(3)));
        }
        return rows;
    }

    /** Reduces a status cell to the single token of the worklist's own vocabulary. */
    private static String statusToken(String id, String cell) {
        Matcher token = STATUS_TOKEN.matcher(cell);
        assertThat(token.find())
                .as("the status of %s is spelled with one of ACCEPTED / ACCEPT_POST_HOC / PROPOSED / REQUIRED, was: %s", id, cell.trim())
                .isTrue();
        return token.group();
    }

    /** The ADRs the worklist declares settled and closed to reopening. */
    private static Set<String> settled() {
        String text = worklist();
        int start = text.indexOf(SETTLED_MARKER);
        assertThat(start).as("the worklist carries its «%s» paragraph", SETTLED_MARKER).isNotNegative();
        Set<String> named = new TreeSet<>();
        Matcher ref = ADR_REF.matcher(text.substring(start));
        while (ref.find()) {
            named.add(ref.group());
        }
        return named;
    }

    @Test
    @DisplayName("the parse is not vacuous: detail sections, the summary table and the ADR documents all resolve")
    void theParseIsNotVacuous() {
        Map<String, Section> sections = sections();
        Map<String, String> summary = summary();
        Map<String, String> adrs = adrStatuses();

        assertThat(sections).as("detail sections of the worklist").hasSizeGreaterThanOrEqualTo(8);
        assertThat(adrs).as("ADR documents on disk").hasSizeGreaterThanOrEqualTo(8);
        assertThat(summary.keySet()).as("the summary table lists exactly the decisions the sections detail").isEqualTo(sections.keySet());
        assertThat(settled()).as("the settled paragraph names ADRs").isNotEmpty();
    }

    @Test
    @DisplayName("a row reads ACCEPTED exactly when its ADR document does — in both directions")
    void theWorklistAgreesWithTheAdrOnWhetherTheDecisionIsMade() {
        Map<String, String> adrs = adrStatuses();

        sections().forEach((id, section) -> {
            String declared = adrs.get(section.adr());
            if (declared == null) {
                return;
            }
            boolean documentIsAccepted = declared.startsWith("Accepted");
            boolean rowIsAccepted = "ACCEPTED".equals(section.status());
            if (documentIsAccepted) {
                assertThat(rowIsAccepted)
                        .as("%s summarises %s, whose document reads «%s» — the worklist must not present a made decision as owed", id, section.adr(), declared)
                        .isTrue();
            } else {
                assertThat(rowIsAccepted)
                        .as("%s reports %s as ACCEPTED, but the document still reads «%s» — the worklist must not announce a decision nobody took", id, section.adr(), declared)
                        .isFalse();
            }
        });
    }

    @Test
    @DisplayName("every accepted ADR is named among the decisions that must not be reopened")
    void everyAcceptedAdrIsNamedAsSettled() {
        Set<String> settled = settled();

        adrStatuses().forEach((adr, status) -> {
            if (!status.startsWith("Accepted")) {
                return;
            }
            assertThat(settled)
                    .as("%s reads «%s», so the worklist's settled list must name it — otherwise the list stops being the answer to «what is still open»", adr, status)
                    .contains(adr);
        });
    }

    @Test
    @DisplayName("REQUIRED means the ADR is unwritten, and an ADR that exists is no longer REQUIRED")
    void requiredMeansTheDocumentDoesNotExistYet() {
        Map<String, String> adrs = adrStatuses();

        sections().forEach((id, section) -> {
            boolean exists = adrs.containsKey(section.adr());
            if ("REQUIRED".equals(section.status())) {
                assertThat(exists)
                        .as("%s calls %s REQUIRED (not written), but the document exists — the status is stale", id, section.adr())
                        .isFalse();
            } else {
                assertThat(exists)
                        .as("%s reports %s as %s, which claims a document that is not on disk", id, section.adr(), section.status())
                        .isTrue();
            }
        });
    }

    @Test
    @DisplayName("the summary table repeats the status its own detail section states")
    void theSummaryTableAgreesWithTheDetailSections() {
        Map<String, String> summary = summary();

        sections().forEach((id, section) -> assertThat(summary.get(id))
                .as("the summary row of %s (%s) must repeat the status of its detail section", id, section.adr())
                .isEqualTo(section.status()));
    }

    /** One decision of the worklist: the ADR it settles and the status it reports for it. */
    private record Section(String adr, String status) {
    }
}
