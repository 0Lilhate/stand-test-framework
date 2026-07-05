package ru.alfa.stand.test.core.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AssertionMatchersTest {

    @Test
    @DisplayName("EQUALS keeps the historical semantics: equality, numeric coercion, strict types, absent fails")
    void equalsSemantics() {
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, "DONE", true, "DONE")).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, 100, true, 100.0)).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, 100, true, "100")).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, true, true, "true")).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, Double.NaN, true, Double.NaN)).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, Double.NaN, true, 1.0)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EQUALS, "DONE", false, null)).isFalse();
    }

    @Test
    @DisplayName("CONTAINS: substring on a String value, element-equality on a List value")
    void containsSemantics() {
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "TARIFF", true, "INDIVIDUAL_TARIFF")).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "MISSING", true, "INDIVIDUAL_TARIFF")).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, 42, true, "value 42")).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "P_AS", true, List.of("PU_LST", "P_AS"))).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, 2, true, List.of(1, 2.0, 3))).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "STS", true, List.of("PU_LST", "P_AS"))).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "x", true, Map.of("x", 1))).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "x", true, 42)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "x", true, null)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.CONTAINS, "x", false, null)).isFalse();
    }

    @Test
    @DisplayName("EXISTS is about path presence: JSON null counts as present; exists:false passes only on absence")
    void existsSemantics() {
        assertThat(AssertionMatchers.matches(AssertionMatcher.EXISTS, true, true, "anything")).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EXISTS, true, true, null)).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EXISTS, true, false, null)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EXISTS, false, false, null)).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.EXISTS, false, true, "anything")).isFalse();
    }

    @Test
    @DisplayName("NOT_NULL is about the present value's nullness: an absent path fails both polarities")
    void notNullSemantics() {
        assertThat(AssertionMatchers.matches(AssertionMatcher.NOT_NULL, true, true, "value")).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.NOT_NULL, true, true, null)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.NOT_NULL, false, true, null)).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.NOT_NULL, false, true, "value")).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.NOT_NULL, true, false, null)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.NOT_NULL, false, false, null)).isFalse();
    }

    @Test
    @DisplayName("MATCHES is a full regex match over a String value; non-String values fail rather than coerce")
    void matchesSemantics() {
        assertThat(AssertionMatchers.matches(AssertionMatcher.MATCHES, "r-[0-9]+", true, "r-42")).isTrue();
        assertThat(AssertionMatchers.matches(AssertionMatcher.MATCHES, "r-[0-9]+", true, "id r-42 tail")).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.MATCHES, "[0-9]+", true, 42)).isFalse();
        assertThat(AssertionMatchers.matches(AssertionMatcher.MATCHES, "[0-9]+", false, null)).isFalse();
    }

    @Test
    @DisplayName("a null matcher is rejected")
    void nullMatcher_isRejected() {
        assertThatThrownBy(() -> AssertionMatchers.matches(null, "x", true, "x"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matcher");
    }
}
