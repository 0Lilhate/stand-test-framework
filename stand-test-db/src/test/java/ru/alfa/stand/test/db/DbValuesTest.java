package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DbValues}: type-aware value comparison (numbers by value, any other type change is a
 * genuine mismatch) and the diagnostic {@code render}.
 */
class DbValuesTest {

    @Test
    @DisplayName("numbers compare by value across integer types and scales")
    void numericValuesMatchByValue() {
        assertThat(DbValues.valuesMatch(100, 100L)).isTrue();
        assertThat(DbValues.valuesMatch(100, new BigDecimal("100.00"))).isTrue();
        assertThat(DbValues.valuesMatch(1, 2)).isFalse();
    }

    @Test
    @DisplayName("equal non-numbers match, but a type change is a mismatch (not string-coerced)")
    void nonNumericEqualityAndTypeChange() {
        assertThat(DbValues.valuesMatch("READY", "READY")).isTrue();
        assertThat(DbValues.valuesMatch("READY", "DONE")).isFalse();
        assertThat(DbValues.valuesMatch(1, "1")).isFalse();
    }

    @Test
    @DisplayName("a non-finite number is a mismatch rather than a thrown NumberFormatException")
    void nonFiniteIsAMismatch() {
        assertThat(DbValues.valuesMatch(Double.NaN, 1)).isFalse();
        assertThat(DbValues.valuesMatch(1, Double.POSITIVE_INFINITY)).isFalse();
    }

    @Test
    @DisplayName("render shows <null> for null and the value otherwise")
    void renderHandlesNull() {
        assertThat(DbValues.render(null)).isEqualTo("<null>");
        assertThat(DbValues.render(42)).isEqualTo("42");
    }
}
