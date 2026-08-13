package ru.alfa.stand.test.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.validation.SqlSpanScanner;

/**
 * The SDK's own {@code :name} → {@code ?} rewriter over {@link PreparedStatement} (plan §4 / §8.8 — no
 * Spring-JDBC dependency, only parameterized binds so values can never be string-spliced into SQL).
 *
 * <p>Parsing is literal/comment-aware: a {@code :name} appearing inside a string literal, a double-quoted
 * or backtick-quoted identifier, a PostgreSQL dollar-quoted string ({@code $$ … $$} / {@code $tag$ … $tag$})
 * or a comment is left untouched, and a PostgreSQL-style {@code ::} cast is never mistaken for a bind. Each
 * {@code :name}
 * (name = {@code [A-Za-z_][A-Za-z0-9_]*}) becomes a positional {@code ?} and is recorded in order;
 * {@link #bind} sets each position from the supplied value map and fails closed if a referenced name has
 * no value.
 */
final class NamedParameterStatement {

    /**
     * Bounded default per-statement query timeout, in seconds. Every statement the SDK executes is capped
     * so a blocking or sleeping query on a real DEV/IFT stand can never hang the calling test thread
     * unbounded — the SDK's single bounded-wait invariant (plan §2.5). A {@code db.expectEventually} poll
     * passes a smaller value when its poll timeout is shorter, so one poll cannot overshoot the await window.
     */
    static final int DEFAULT_STATEMENT_TIMEOUT_SECONDS = 60;

    private final String translatedSql;
    private final List<String> orderedNames;

    private NamedParameterStatement(String translatedSql, List<String> orderedNames) {
        this.translatedSql = translatedSql;
        this.orderedNames = List.copyOf(orderedNames);
    }

    static NamedParameterStatement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new StandTestException("SQL must not be blank");
        }
        StringBuilder translated = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            SqlSpanScanner.Span span = SqlSpanScanner.spanAt(sql, index);
            if (span != null) {
                translated.append(sql, span.start(), span.end());
                index = span.end();
                continue;
            }
            char current = sql.charAt(index);
            char next = (index + 1 < sql.length()) ? sql.charAt(index + 1) : '\0';
            if (current == ':' && next == ':') {
                translated.append("::");
                index += 2;
            } else if (current == ':' && isNameStart(next)) {
                index = readBind(sql, index, translated, names);
            } else {
                translated.append(current);
                index++;
            }
        }
        return new NamedParameterStatement(translated.toString(), names);
    }

    /**
     * Prepares the translated statement, applies a bounded query timeout, and binds the values. The
     * {@code queryTimeoutSeconds} cap is set before any execution so a blocking/sleeping query cannot hang
     * the calling thread unbounded (plan §2.5) — a driver that does not honour {@link
     * PreparedStatement#setQueryTimeout(int)} is a driver limitation, but the standard JDBC bound is always
     * requested.
     *
     * @param connection the run-scoped connection
     * @param values the named bind values
     * @param queryTimeoutSeconds the bounded per-statement timeout in seconds (must be positive)
     * @return the prepared, bounded, bound statement
     * @throws SQLException if preparing, bounding or binding fails
     */
    PreparedStatement create(Connection connection, Map<String, Object> values, int queryTimeoutSeconds) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(this.translatedSql);
        try {
            statement.setQueryTimeout(queryTimeoutSeconds);
            bind(statement, values);
        } catch (SQLException | RuntimeException failure) {
            statement.close();
            throw failure;
        }
        return statement;
    }

    private void bind(PreparedStatement statement, Map<String, Object> values) throws SQLException {
        for (int position = 0; position < this.orderedNames.size(); position++) {
            String name = this.orderedNames.get(position);
            if (!values.containsKey(name)) {
                throw new StandTestException("No bind value supplied for ':" + name + "'");
            }
            statement.setObject(position + 1, values.get(name));
        }
    }

    String translatedSql() {
        return this.translatedSql;
    }

    List<String> orderedNames() {
        return this.orderedNames;
    }

    private static int readBind(String sql, int start, StringBuilder translated, List<String> names) {
        int index = start + 1;
        int nameStart = index;
        while (index < sql.length() && isNamePart(sql.charAt(index))) {
            index++;
        }
        names.add(sql.substring(nameStart, index));
        translated.append('?');
        return index;
    }

    private static boolean isNameStart(char character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') || character == '_';
    }

    private static boolean isNamePart(char character) {
        return isNameStart(character) || (character >= '0' && character <= '9');
    }
}
