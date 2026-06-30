package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariant tests for the {@link ResolvedDatasource} value record: {@code url}/{@code user} must be
 * non-blank, {@code password} must be non-null but may be empty (some stands use an empty password). These
 * are the low-level guards; the executor maps the same misconfiguration to a {@code StandTestException}
 * (see {@link DbStepExecutorConnectionTest}).
 */
class ResolvedDatasourceTest {

    @Test
    @DisplayName("a blank url is rejected")
    void blankUrlRejected() {
        assertThatThrownBy(() -> new ResolvedDatasource("  ", "sa", "pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url must not be blank");
    }

    @Test
    @DisplayName("a blank user is rejected")
    void blankUserRejected() {
        assertThatThrownBy(() -> new ResolvedDatasource("jdbc:h2:mem:x", "", "pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user must not be blank");
    }

    @Test
    @DisplayName("a null password is rejected")
    void nullPasswordRejected() {
        assertThatThrownBy(() -> new ResolvedDatasource("jdbc:h2:mem:x", "sa", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("password must not be null");
    }

    @Test
    @DisplayName("an empty password is allowed (some stands use an empty password)")
    void emptyPasswordAllowed() {
        assertThatCode(() -> new ResolvedDatasource("jdbc:h2:mem:x", "sa", ""))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null url is rejected")
    void nullUrlRejected() {
        assertThatThrownBy(() -> new ResolvedDatasource(null, "sa", "pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url must not be blank");
    }

    @Test
    @DisplayName("a null user is rejected")
    void nullUserRejected() {
        assertThatThrownBy(() -> new ResolvedDatasource("jdbc:h2:mem:x", null, "pw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user must not be blank");
    }
}
