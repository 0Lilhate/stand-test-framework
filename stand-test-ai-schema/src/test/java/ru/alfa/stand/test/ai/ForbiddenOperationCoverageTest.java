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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.alfa.stand.test.core.validation.ForbiddenOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForbiddenOperationCoverageTest {

    /**
     * Documentation files allowed to state HOW MANY forbidden-operation codes exist. The count is a
     * fact about the enum, so prose that repeats it is a copy that rots: {@code docs/ai-agent/README.md}
     * said "12-code" from the day the kit landed, and three {@code NON_WHITELISTED_*} codes were added
     * later without anyone revisiting the sentence.
     *
     * <p>The planning corpus under {@code docs/agent-implementation/} is deliberately NOT scanned: it
     * quotes the stale number while describing the defect itself, and a test that failed on its own
     * bug report would be unusable.
     */
    private static final List<String> COUNT_BEARING_DOCS = List.of(
            "CLAUDE.md",
            "README.md",
            "docs/ai-agent/README.md",
            "docs/ai-agent/.claude/rules/stand-test-guardrails.md",
            "docs/ai-agent/.opencode/rules/stand-test-guardrails.md");

    /**
     * A count claim: a number spelled as {@code N-code}/{@code N codes}/{@code N constants} on a line
     * that also names the enum. Requiring the identifier on the same line keeps unrelated numbers out;
     * a rephrasing that moves the number away from the name makes the corpus claim-free, which the
     * test reports as a failure rather than passing silently.
     */
    private static final Pattern COUNT_CLAIM = Pattern.compile("\\b(\\d+)[- ](?:codes?|constants?)\\b");

    /**
     * Codes whose Layer cell MUST claim runtime enforcement — the core {@code DefaultScenarioValidator}
     * (or an adapter) actually enforces them, and the generation rules must not under- or over-state
     * that. If enforcement moves, both the code and this table must change together.
     */
    private static final Map<ForbiddenOperation, String> EXPECTED_LAYER_KEYWORD = Map.ofEntries(
            Map.entry(ForbiddenOperation.THREAD_SLEEP, "runtime"),
            Map.entry(ForbiddenOperation.SECRET_IN_SOURCE, "runtime"),
            Map.entry(ForbiddenOperation.UNBOUNDED_TIMEOUT, "runtime"),
            Map.entry(ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW, "runtime"),
            Map.entry(ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT, "runtime"),
            Map.entry(ForbiddenOperation.NON_WHITELISTED_DATASOURCE, "runtime"),
            Map.entry(ForbiddenOperation.NON_WHITELISTED_SERVICE, "runtime"),
            Map.entry(ForbiddenOperation.NON_WHITELISTED_TOPIC, "runtime"),
            Map.entry(ForbiddenOperation.NON_WHITELISTED_GRPC_TARGET, "runtime"),
            Map.entry(ForbiddenOperation.NON_WHITELISTED_UI_APPLICATION, "runtime"));

    @Test
    @DisplayName("every core ForbiddenOperation code is catalogued as a row of the forbidden-ops table")
    void everyForbiddenOperation_isCatalogued() {
        String rules = AiSchemaResources.generationRules();
        assertThat(rules).as("the forbidden-operations table must exist").contains("| Code | Meaning | Layer |");
        for (ForbiddenOperation op : ForbiddenOperation.values()) {
            assertThat(rules)
                    .as("ForbiddenOperation %s must be catalogued as a table row, not merely mentioned", op.code())
                    .contains("| `" + op.code() + "` |");
        }
    }

    @Test
    @DisplayName("runtime-enforced codes claim the runtime layer in their table row — the Layer column cannot drift from the actual enforcement")
    void runtimeEnforcedCodes_claimRuntimeLayer() {
        String rules = AiSchemaResources.generationRules();
        EXPECTED_LAYER_KEYWORD.forEach((op, keyword) ->
                assertThat(layerCell(rules, op.code()))
                        .as("the Layer cell of %s must claim '%s' enforcement", op.code(), keyword)
                        .contains(keyword));
    }

    @Test
    @DisplayName("every documented count of forbidden-operation codes equals the enum's actual size")
    void documentedCodeCount_matchesTheEnum() {
        int actual = ForbiddenOperation.values().length;
        List<String> wrong = new ArrayList<>();
        int claims = 0;

        for (String document : COUNT_BEARING_DOCS) {
            Path file = repositoryRoot().resolve(document);
            int lineNumber = 0;
            for (String line : read(file).lines().toList()) {
                lineNumber++;
                if (!line.contains("ForbiddenOperation")) {
                    continue;
                }
                Matcher matcher = COUNT_CLAIM.matcher(line);
                while (matcher.find()) {
                    claims++;
                    if (Integer.parseInt(matcher.group(1)) != actual) {
                        wrong.add(document + ":" + lineNumber + " claims " + matcher.group(1) + ", enum has " + actual);
                    }
                }
            }
        }

        assertThat(wrong).as("documentation states a forbidden-operation count that the enum contradicts — the enum is the source of truth, so fix the prose").isEmpty();
        assertThat(claims).as("no scanned document states the count any more: either the sentence was rephrased past " + COUNT_CLAIM.pattern() + " — restore a recognisable spelling — or a scanned file was renamed").isPositive();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

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

    private static String layerCell(String rules, String code) {
        return rules.lines()
                .filter(line -> line.startsWith("| `" + code + "` |"))
                .findFirst()
                .map(ForbiddenOperationCoverageTest::lastCell)
                .orElseThrow(() -> new AssertionError("No table row for " + code));
    }

    private static String lastCell(String tableRow) {
        String[] cells = tableRow.split("\\|");
        return cells[cells.length - 1].trim();
    }
}
