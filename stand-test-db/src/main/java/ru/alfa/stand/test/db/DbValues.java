package ru.alfa.stand.test.db;

import ru.alfa.stand.test.core.assertion.AssertionMatchers;

/**
 * Type-aware comparison of an expected DSL value against a value read from a JDBC {@code ResultSet}.
 * Delegates to {@link AssertionMatchers#equalsMatch(Object, Object)} — the one evaluator REST, Kafka and
 * gRPC also use — so numbers compare by numeric value (an expected {@code int 100} matches a
 * {@code BIGINT} {@code 100L} or a {@code NUMERIC} {@code 100.0}) while any other type change stays a
 * genuine mismatch rather than being string-coerced, and a column changing type is caught (plan §8.8).
 *
 * <p>Non-finite values (NaN / Infinity) have no {@code BigDecimal} form: two identical {@code Double.NaN}
 * match on the {@code Objects.equals} fast path ({@code Double.equals} canonicalises NaN), and a
 * comparison against a different value is a mismatch rather than a raw {@code NumberFormatException}
 * (plan §8.3).
 */
final class DbValues {

    private DbValues() {
    }

    static boolean valuesMatch(Object expected, Object actual) {
        return AssertionMatchers.equalsMatch(expected, actual);
    }

    static String render(Object value) {
        return (value == null) ? "<null>" : String.valueOf(value);
    }
}
