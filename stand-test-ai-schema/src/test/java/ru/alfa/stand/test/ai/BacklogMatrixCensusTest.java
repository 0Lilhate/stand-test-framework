package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Holds the dependency matrix against the backlog it is generated from.
 *
 * <p>{@code planning/22-task-dependency-matrix.md} is the second of the three documents the
 * {@code /next-ui-task} command reads in order to choose the next task ("зависимости и критический
 * путь"). That makes one particular drift expensive: a closed gate still shown as {@code BLOCKED}
 * tells the reader to wait for an answer that has already been given. That is not hypothetical — it is
 * what this test was written for. On 2026-08-10 the status column disagreed with the backlog in
 * <em>54 rows out of 81</em>, and one-sidedly: eight closed external gates read {@code BLOCKED}, four
 * accepted decisions read {@code READY}, nine shipped slices read {@code IN_PROGRESS}, and six cards
 * had no row at all.
 *
 * <p>The remedy had even been named and had not worked. The header correction of 2026-08-08 said the
 * table "правится перегенерацией… правка — задача `F002`" — and {@code F002} closed {@code DONE} on
 * 2026-08-09 without doing it. A remedy assigned to a card that then closes leaves no owner behind,
 * which is why this is pinned by a test rather than by another promise.
 *
 * <p>The device is the one the kit's other censuses already use ({@link AdrWorklistCensusTest},
 * {@link UiReadinessCensusTest}): the claim is read out of the document and compared with the thing it
 * describes, rather than restated here. What is pinned is what the table <em>derives</em> — the row
 * set, the status, {@code Depends on}, {@code Blocks} and both gate columns. The {@code Critical path}
 * and {@code Lane} columns are deliberately not pinned: no such field exists in the backlog, they are
 * judgement about the plan, and a test that froze them would be a second copy rather than a check.
 */
class BacklogMatrixCensusTest {

    /** The matrix, relative to the repository root. */
    private static final String MATRIX = "docs/ui-test-generation/planning/22-task-dependency-matrix.md";

    /** The backlog the matrix is generated from. */
    private static final String BACKLOG = "docs/ui-test-generation/planning/21-task-backlog.yaml";

    /** Container types: they hold no row of their own — §4 is about working elements. */
    private static final Set<String> CONTAINERS = Set.of("EPIC", "FEATURE", "INITIATIVE");

    /** The statuses that close a card, and so strike through every reference to it. */
    private static final Set<String> CLOSED = Set.of("DONE", "REJECTED");

    /** The four-letter type abbreviations §4 spells, mapped from the backlog's own vocabulary. */
    private static final Map<String, String> TYPE_ABBREVIATIONS = Map.of(
            "STORY", "STOR", "TASK", "TASK", "SPIKE", "SPIK",
            "ADR", "ADR", "EXTERNAL", "EXTE", "VALIDATION", "VALI");

    /** A row of §4: nine columns, the first a backticked task id. */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`(UITG-[A-Z0-9]+)`\\s*\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|",
            Pattern.MULTILINE);

    /** A reference inside a cell, remembering whether it is struck through. */
    private static final Pattern REFERENCE = Pattern.compile("(~~)?(UITG-[A-Z0-9]+)(~~)?");

    /** The backlog, as the cards it declares, keyed by id and in file order. */
    private static Map<String, Map<String, Object>> backlog() {
        Path file = KitCensus.repositoryRoot().resolve(BACKLOG);
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) yaml.load(KitCensus.read(file));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) document.get("tasks");
        assertThat(tasks).as("%s declares a `tasks` list", BACKLOG).isNotNull();

        Map<String, Map<String, Object>> cards = new LinkedHashMap<>();
        for (Map<String, Object> task : tasks) {
            cards.put(String.valueOf(task.get("id")), task);
        }
        return cards;
    }

    /** One column of a card, as the set of ids it names — empty when the field is absent or empty. */
    private static Set<String> field(Map<String, Object> card, String name) {
        Object value = card.get(name);
        if (!(value instanceof List<?> items)) {
            return Set.of();
        }
        Set<String> ids = new TreeSet<>();
        items.forEach(item -> ids.add(String.valueOf(item)));
        return ids;
    }

    /**
     * What {@code Blocks} must hold for every card: the union of its own {@code blocks} field and the
     * reverse of every incoming edge — {@code depends_on}, {@code external_dependencies} and
     * {@code adr_dependencies}. No exceptions: one to remember would have to be remembered by the
     * reader too.
     */
    private static Map<String, Set<String>> blocks(Map<String, Map<String, Object>> cards) {
        Map<String, Set<String>> blocked = new LinkedHashMap<>();
        cards.forEach((id, card) -> blocked.put(id, new TreeSet<>(field(card, "blocks"))));
        cards.forEach((id, card) -> {
            for (String incoming : new String[] {"depends_on", "external_dependencies", "adr_dependencies"}) {
                for (String target : field(card, incoming)) {
                    blocked.computeIfAbsent(target, unused -> new TreeSet<>()).add(id);
                }
            }
        });
        return blocked;
    }

    /** The rows of §4, in document order. */
    private static List<Row> rows() {
        String text = KitCensus.read(KitCensus.repositoryRoot().resolve(MATRIX));
        int start = text.indexOf("## 4. Матрица");
        assertThat(start).as("%s carries its §4 table", MATRIX).isNotNegative();
        int end = text.indexOf("## 5.", start);
        assertThat(end).as("§4 of %s is bounded by §5 — an unbounded read is a census of the wrong thing", MATRIX).isPositive();

        List<Row> rows = new ArrayList<>();
        Matcher row = ROW.matcher(text.substring(start, end));
        while (row.find()) {
            rows.add(new Row(row.group(1), row.group(2).trim(), row.group(3).trim(),
                    row.group(4), row.group(5), row.group(8), row.group(9)));
        }
        return rows;
    }

    /** The ids a cell names, ignoring whether they are struck through. */
    private static Set<String> named(String cell) {
        Set<String> ids = new TreeSet<>();
        Matcher reference = REFERENCE.matcher(cell);
        while (reference.find()) {
            ids.add(reference.group(2));
        }
        return ids;
    }

    /** The ids a cell strikes through. */
    private static Set<String> struck(String cell) {
        Set<String> ids = new TreeSet<>();
        Matcher reference = REFERENCE.matcher(cell);
        while (reference.find()) {
            if (reference.group(1) != null && reference.group(3) != null) {
                ids.add(reference.group(2));
            }
        }
        return ids;
    }

    @Test
    @DisplayName("the parse is not vacuous: §4 resolves to rows and the backlog to cards")
    void theParseIsNotVacuous() {
        List<Row> rows = rows();
        Map<String, Map<String, Object>> cards = backlog();

        assertThat(rows).as("rows of §4").hasSizeGreaterThanOrEqualTo(80);
        assertThat(cards).as("cards of the backlog").hasSizeGreaterThanOrEqualTo(100);
        assertThat(rows.stream().filter(r -> !r.dependsOn().isBlank()).count())
                .as("rows naming a dependency — a parse that found none would agree with anything")
                .isGreaterThan(30);
    }

    @Test
    @DisplayName("every working card has exactly one row, and every row names a card that exists")
    void everyWorkingCardHasExactlyOneRow() {
        Map<String, Map<String, Object>> cards = backlog();
        List<Row> rows = rows();

        Set<String> listed = new LinkedHashSet<>();
        rows.forEach(row -> assertThat(listed.add(row.id())).as("§4 lists %s more than once", row.id()).isTrue());

        cards.forEach((id, card) -> {
            String type = String.valueOf(card.get("type"));
            if (CONTAINERS.contains(type)) {
                assertThat(listed).as("%s is a %s — containers hold no row of their own in §4", id, type).doesNotContain(id);
                return;
            }
            assertThat(listed)
                    .as("%s is a working card without a row in §4 — a task the matrix cannot show is a task the next executor does not see", id)
                    .contains(id);
        });
        assertThat(cards.keySet()).as("§4 names only cards the backlog declares").containsAll(listed);
    }

    @Test
    @DisplayName("the type column spells the backlog's own type")
    void theTypeColumnRepeatsTheBacklog() {
        Map<String, Map<String, Object>> cards = backlog();

        rows().forEach(row -> {
            String type = String.valueOf(cards.get(row.id()).get("type"));
            assertThat(row.type())
                    .as("%s is a %s in the backlog", row.id(), type)
                    .isEqualTo(TYPE_ABBREVIATIONS.get(type));
        });
    }

    @Test
    @DisplayName("the status column repeats the backlog, in every row")
    void theStatusColumnRepeatsTheBacklog() {
        Map<String, Map<String, Object>> cards = backlog();

        rows().forEach(row -> assertThat(row.status())
                .as("§4 reports %s as %s while the backlog reads %s — a closed gate shown as blocked tells the reader to wait for an answer already given",
                        row.id(), row.status(), cards.get(row.id()).get("status"))
                .isEqualTo(String.valueOf(cards.get(row.id()).get("status"))));
    }

    @Test
    @DisplayName("the Depends on and both gate columns repeat the backlog")
    void theDependencyColumnsRepeatTheBacklog() {
        Map<String, Map<String, Object>> cards = backlog();

        rows().forEach(row -> {
            Map<String, Object> card = cards.get(row.id());
            assertThat(named(row.dependsOn())).as("Depends on of %s", row.id()).isEqualTo(field(card, "depends_on"));
            assertThat(named(row.externalGate())).as("External gate of %s", row.id()).isEqualTo(field(card, "external_dependencies"));
            assertThat(named(row.adrGate())).as("ADR gate of %s", row.id()).isEqualTo(field(card, "adr_dependencies"));
        });
    }

    @Test
    @DisplayName("Blocks is the union of the blocks field and every reverse edge")
    void theBlocksColumnIsTheUnionOfBlocksAndReverseEdges() {
        Map<String, Map<String, Object>> cards = backlog();
        Map<String, Set<String>> expected = blocks(cards);

        rows().forEach(row -> assertThat(named(row.blocks()))
                .as("Blocks of %s — the union of its `blocks` field and the reverse of depends_on / external_dependencies / adr_dependencies", row.id())
                .isEqualTo(expected.getOrDefault(row.id(), Set.of())));
    }

    @Test
    @DisplayName("a reference is struck through exactly when the card it names is closed")
    void struckThroughMeansClosedAndNothingElse() {
        Map<String, Map<String, Object>> cards = backlog();

        rows().forEach(row -> {
            for (String cell : new String[] {row.dependsOn(), row.blocks(), row.externalGate(), row.adrGate()}) {
                Set<String> struck = struck(cell);
                for (String id : named(cell)) {
                    boolean closed = CLOSED.contains(String.valueOf(cards.get(id).get("status")));
                    assertThat(struck.contains(id))
                            .as("row %s names %s, which reads %s — striking through means closed and nothing else, otherwise the reader cannot tell what still holds the card",
                                    row.id(), id, cards.get(id).get("status"))
                            .isEqualTo(closed);
                }
            }
        });
    }

    /** One row of §4: the columns derived from the backlog. Judgement columns are not read at all. */
    private record Row(String id, String type, String status, String dependsOn, String blocks, String externalGate, String adrGate) {
    }
}
