package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the acceptance criterion of UITG-F002: no document of the UI line may state that the module
 * {@code stand-test-ui} does not exist (conflicts CONF-03 and CONF-07).
 *
 * <p>Why a test and not a one-off edit. The claim was false in eight places at once and in the most
 * quoted document of the line, and it became false silently — nobody edits an analysis document when
 * a module lands. The same will happen again: the documents are snapshots by nature, the code moves,
 * and the next stale assertion will look exactly like the last one. A test is the only thing that
 * notices.
 *
 * <p>What is asserted is deliberately NOT "the phrase never appears". The phrase legitimately appears
 * in three shapes that must stay: a conflict register quoting the claim it registers
 * ({@code 03-conflicts-and-gaps.md}), an inventory marking a document {@code OUTDATED} because of it,
 * and the original text of a snapshot preserved on purpose. Forbidding the words outright would push
 * those records into paraphrase and destroy the audit trail. So the rule is weaker and truer: every
 * assertion of absence must carry a REBUTTAL near it — the correction block, the SDK team's note, or
 * the plain statement that the module exists.
 *
 * <p>Why this test lives in {@code stand-test-ai-schema}: the module already hosts the
 * repository-level meta-tests that read files outside their own tree ({@code CLAUDE.md},
 * {@code README.md}, the kit bundle, the corpus, {@code .gitlab-ci.yml}). Its {@code test} task
 * declares the two document trees below as inputs, so editing a document re-runs this test instead of
 * leaving it {@code UP-TO-DATE} — an undeclared input is not a slower check, it is a check that
 * silently does not run.
 */
class UiLineDocumentHygieneTest {

    /**
     * An assertion that the module is absent. Bounded by {@code [^.|]} so it cannot run across a
     * sentence or a table cell and pick up an unrelated "нет" further down the row.
     *
     * <p>{@link Pattern#UNICODE_CHARACTER_CLASS} is load-bearing, not decoration: without it Java's
     * {@code \w} and {@code \b} mean ASCII only, so {@code [Мм]одул\w*} matches nothing in a Cyrillic
     * document and the whole check passes on zero hits. That is precisely why
     * {@link #theClaimIsActuallyPresentSomewhere()} exists — it caught this exact mistake here.
     */
    private static final Pattern ABSENCE_CLAIM =
            Pattern.compile("[Мм]одул\\w*\\s+`?stand-test-ui`?[^.|]{0,40}?(не существует|пока нет|нет\\b)", Pattern.UNICODE_CHARACTER_CLASS);

    /** A correction marker, the SDK team's note, or a plain statement that the module exists. */
    private static final Pattern REBUTTAL = Pattern.compile("Устарело|Пометка команды SDK|существует");

    /** The same words in the negative — a rebuttal must not be another copy of the claim. */
    private static final Pattern NEGATION = Pattern.compile("не существует|пока нет");

    /**
     * How far a rebuttal may sit from the claim. Twenty lines is one table plus its caption, which is
     * the widest shape in use ({@code 03-conflicts-and-gaps.md} puts «Источники» and «Фактическое
     * состояние» in adjacent rows of the same table, the BRD puts its note above the appendix).
     */
    private static final int REBUTTAL_WINDOW_LINES = 20;

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts")) && Files.isDirectory(current.resolve(Paths.get("docs", "ui-test-generation")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    /** Every markdown document of the UI line, plus the BRD its appendices belong to. */
    private static List<Path> lineDocuments() {
        Path root = repositoryRoot();
        List<Path> documents = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root.resolve(Paths.get("docs", "ui-test-generation")))) {
            tree.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".md")).sorted().forEach(documents::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk the UI line documents", e);
        }
        Path brd = root.resolve(Paths.get("docs", "brd", "ui-test-generation-brd.md"));
        assertThat(brd).as("the BRD whose appendices §21/§22 carry the CONF-03 claim").exists();
        documents.add(brd);
        return documents;
    }

    private static List<String> readLines(Path document) {
        try {
            return Files.readAllLines(document, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + document, e);
        }
    }

    /**
     * A line rebuts the claim when, with the claim itself removed, what remains still carries a
     * correction marker. Removing the claim first is what lets a single line both quote the words and
     * refute them — «"Модуля stand-test-ui пока нет" — существует» is a correction, not a violation.
     */
    private static boolean rebuts(String line) {
        String withoutClaim = ABSENCE_CLAIM.matcher(line).replaceAll(" ");
        return REBUTTAL.matcher(withoutClaim).find() && !NEGATION.matcher(withoutClaim).find();
    }

    private static boolean rebuttedNearby(List<String> lines, int claimIndex) {
        int from = Math.max(0, claimIndex - REBUTTAL_WINDOW_LINES);
        int to = Math.min(lines.size() - 1, claimIndex + REBUTTAL_WINDOW_LINES);
        for (int index = from; index <= to; index++) {
            if (rebuts(lines.get(index))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("no document of the UI line asserts that stand-test-ui does not exist without correcting itself on the spot")
    void everyAbsenceClaimCarriesItsRebuttal() {
        Path root = repositoryRoot();
        List<String> unrebutted = new ArrayList<>();
        for (Path document : lineDocuments()) {
            List<String> lines = readLines(document);
            for (int index = 0; index < lines.size(); index++) {
                if (ABSENCE_CLAIM.matcher(lines.get(index)).find() && !rebuttedNearby(lines, index)) {
                    unrebutted.add(root.relativize(document) + ":" + (index + 1) + " — " + lines.get(index).trim());
                }
            }
        }
        assertThat(unrebutted)
                .as("документы линии, утверждающие что модуля stand-test-ui нет, без поправки рядом (UITG-F002, CONF-03/CONF-07). Пометьте место блоком «Устарело» или назовите факт — не удаляйте запись конфликта")
                .isEmpty();
    }

    @Test
    @DisplayName("the check is not vacuous: the documents really do carry the claim, so a removed correction would be caught")
    void theClaimIsActuallyPresentSomewhere() {
        long documentsCarryingTheClaim = lineDocuments().stream()
                .filter(document -> readLines(document).stream().anyMatch(line -> ABSENCE_CLAIM.matcher(line).find()))
                .count();

        assertThat(documentsCarryingTheClaim)
                .as("если ни один документ больше не содержит утверждения, первый тест проходит вакуумно — тогда либо запись конфликта потеряна, либо шаблон ABSENCE_CLAIM перестал совпадать с текстом")
                .isGreaterThanOrEqualTo(3L);
    }

    @Test
    @DisplayName("a rebuttal that merely repeats the claim does not count as one")
    void repeatingTheClaimDoesNotCountAsRebutting() {
        assertThat(rebuts("Модуля `stand-test-ui` не существует")).isFalse();
        assertThat(rebuts("| **Источники** | `00-current-state.md:45` — «Модуля `stand-test-ui` не существует» |")).isFalse();
        assertThat(rebuts("> **Устарело (2026-08-04).** Модуль **существует**: 36 java-файлов")).isTrue();
        assertThat(rebuts("| BRD §21 | «Модуля `stand-test-ui` пока нет» — существует; API примера не совпадает |")).isTrue();
    }
}
