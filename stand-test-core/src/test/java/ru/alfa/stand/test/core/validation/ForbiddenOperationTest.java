package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ForbiddenOperationTest {

    @Test
    @DisplayName("every forbidden operation has a non-blank, unique code and a non-blank description")
    void codesAndDescriptions_areWellFormed() {
        var codes = Arrays.stream(ForbiddenOperation.values()).map(ForbiddenOperation::code).toList();

        assertThat(codes).doesNotContainNull();
        assertThat(codes).allSatisfy(code -> assertThat(code).isNotBlank());
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(Arrays.stream(ForbiddenOperation.values()))
                .allSatisfy(op -> assertThat(op.description()).isNotBlank());
    }

    @Test
    @DisplayName("the single source of truth covers the key SDK guardrails")
    void coversKeyGuardrails() {
        var codes = Arrays.stream(ForbiddenOperation.values()).map(ForbiddenOperation::code).toList();

        assertThat(codes).contains(
                "THREAD_SLEEP",
                "HARDCODED_STAND_URL",
                "SECRET_IN_SOURCE",
                "DESTRUCTIVE_SQL_WITHOUT_ALLOW",
                "IMPERATIVE_EAGER_IO");
    }
}
