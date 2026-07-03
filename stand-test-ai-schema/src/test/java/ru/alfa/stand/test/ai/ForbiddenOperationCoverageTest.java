package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import ru.alfa.stand.test.core.validation.ForbiddenOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForbiddenOperationCoverageTest {

    /**
     * Codes whose Layer cell MUST claim runtime enforcement — the core {@code DefaultScenarioValidator}
     * (or an adapter) actually enforces them, and the generation rules must not under- or over-state
     * that. If enforcement moves, both the code and this table must change together.
     */
    private static final Map<ForbiddenOperation, String> EXPECTED_LAYER_KEYWORD = Map.of(
            ForbiddenOperation.THREAD_SLEEP, "runtime",
            ForbiddenOperation.SECRET_IN_SOURCE, "runtime",
            ForbiddenOperation.UNBOUNDED_TIMEOUT, "runtime",
            ForbiddenOperation.DESTRUCTIVE_SQL_WITHOUT_ALLOW, "runtime",
            ForbiddenOperation.NON_WHITELISTED_ENVIRONMENT, "runtime",
            ForbiddenOperation.NON_WHITELISTED_DATASOURCE, "runtime");

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
