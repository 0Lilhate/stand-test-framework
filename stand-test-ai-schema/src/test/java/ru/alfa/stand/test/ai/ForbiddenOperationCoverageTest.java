package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import ru.alfa.stand.test.core.validation.ForbiddenOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForbiddenOperationCoverageTest {

    @Test
    @DisplayName("every core ForbiddenOperation code is documented in the AI generation rules")
    void everyForbiddenOperation_isDocumented() {
        String rules = AiSchemaResources.generationRules();
        for (ForbiddenOperation op : ForbiddenOperation.values()) {
            assertThat(rules)
                    .as("generation rules must reference ForbiddenOperation %s", op.code())
                    .contains(op.code());
        }
    }
}
