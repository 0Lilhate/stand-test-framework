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
                // Comments and string/identifier literals are copied verbatim — a ':name', a '::' cast or a
                // statement boundary inside them is not a bind. Span boundaries come from the shared
                // SqlSpanScanner, so this rewriter and the classifier's strip() cannot drift (plan §8.6).
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
        // A statement with no named binds (a parameterless SELECT/DELETE) is valid: orderedNames is empty.
        return new NamedParameterStatement(translated.toString(), names);
    }

    PreparedStatement create(Connection connection, Map<String, Object> values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(this.translatedSql);
        try {
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
        // ASCII identifier grammar [A-Za-z_], matching this class's Javadoc, SqlIdentifiers.PLAIN_IDENTIFIER
        // and the classifier's ASCII \b for :testRunId — not Unicode-aware Character.isLetter, which would let
        // the bind grammar drift from the single-source-of-truth classifier (plan §8.6).
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') || character == '_';
    }

    private static boolean isNamePart(char character) {
        return isNameStart(character) || (character >= '0' && character <= '9');
    }
}
