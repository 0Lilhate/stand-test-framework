package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ru.alfa.stand.test.core.validation.ForbiddenOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForbiddenOperationCoverageTest {

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
}
