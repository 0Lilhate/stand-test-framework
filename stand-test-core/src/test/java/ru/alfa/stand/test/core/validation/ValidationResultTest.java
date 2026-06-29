package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class ValidationResultTest {

    @Test
    @DisplayName("valid() has no issues and is valid")
    void valid_hasNoIssues() {
        ValidationResult result = ValidationResult.valid();

        assertThat(result.isValid()).isTrue();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("errors are filtered and warnings do not invalidate")
    void errorsAndWarnings() {
        ValidationResult result = ValidationResult.of(List.of(
                ValidationIssue.warning("W", "a warning"),
                ValidationIssue.error("E", "an error")));

        assertThat(result.isValid()).isFalse();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.errors()).extracting(ValidationIssue::code).containsExactly("E");

        ValidationResult warningOnly = ValidationResult.of(List.of(ValidationIssue.warning("W", "a warning")));
        assertThat(warningOnly.isValid()).isTrue();
    }

    @Test
    @DisplayName("throwIfInvalid throws a StandTestException listing the errors")
    void throwIfInvalid_throwsOnErrors() {
        ValidationResult result = ValidationResult.of(List.of(ValidationIssue.error("E", "boom")));

        assertThatThrownBy(result::throwIfInvalid)
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("E")
                .hasMessageContaining("boom");
        assertThatCode(ValidationResult.valid()::throwIfInvalid).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the issue list is immutable")
    void issues_areImmutable() {
        ValidationResult result = ValidationResult.of(List.of(ValidationIssue.error("E", "boom")));

        assertThatThrownBy(() -> result.issues().add(ValidationIssue.warning("W", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("ValidationIssue rejects blank code and message")
    void validationIssue_rejectsBlank() {
        assertThatThrownBy(() -> ValidationIssue.error(" ", "m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ValidationIssue.error("C", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
