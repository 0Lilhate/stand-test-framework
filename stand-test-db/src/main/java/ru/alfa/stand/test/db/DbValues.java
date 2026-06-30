package ru.alfa.stand.test.db;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Type-aware comparison of an expected DSL value against a value read from a JDBC {@code ResultSet}, with
 * the same semantics as the REST and Kafka adapters: numbers compare by numeric value (so an expected
 * {@code int 100} matches a {@code BIGINT} {@code 100L} or a {@code NUMERIC} {@code 100.0}) but any other
 * type change is a genuine mismatch rather than being string-coerced, so a column changing type is caught
 * (plan §8.8).
 */
final class DbValues {

    private DbValues() {
    }

    static boolean valuesMatch(Object expected, Object actual) {
        if (Objects.equals(expected, actual)) {
            return true;
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            try {
                return new BigDecimal(expectedNumber.toString()).compareTo(new BigDecimal(actualNumber.toString())) == 0;
            } catch (NumberFormatException notComparable) {
                // A non-finite value (NaN / Infinity) has no BigDecimal form, so when compared against a
                // *different* value it reaches here and is a mismatch, rather than letting a raw
                // NumberFormatException escape (plan §8.3). Two identical Double.NaN already matched via the
                // Objects.equals fast path above (Double.equals canonicalises NaN), so they never reach here.
                return false;
            }
        }
        return false;
    }

    static String render(Object value) {
        return (value == null) ? "<null>" : String.valueOf(value);
    }
}
