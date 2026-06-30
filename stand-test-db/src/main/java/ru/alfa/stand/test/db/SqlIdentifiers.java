package ru.alfa.stand.test.db;

import java.util.regex.Pattern;

/**
 * Validation of SQL identifiers that the SDK splices into SQL text rather than binding (currently only
 * the {@code whereTestRunId} column, which becomes {@code WHERE <column> = :testRunId}).
 *
 * <p>Bind <em>values</em> are always parameterized (plan §8.8), but a column <em>name</em> cannot be a
 * bind, so the one identifier the SDK does interpolate is constrained to a plain unqualified identifier —
 * closing the injection vector a free-form column name would otherwise open.
 */
final class SqlIdentifiers {

    private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private SqlIdentifiers() {
    }

    static boolean isPlainIdentifier(String identifier) {
        return identifier != null && PLAIN_IDENTIFIER.matcher(identifier).matches();
    }
}
