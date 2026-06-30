package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NamedParameterStatementTest {

    @Test
    @DisplayName("named binds become positional placeholders in order")
    void rewritesNamedBinds() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT * FROM t WHERE a = :a AND b = :b AND c = :a");

        assertThat(statement.translatedSql()).isEqualTo("SELECT * FROM t WHERE a = ? AND b = ? AND c = ?");
        assertThat(statement.orderedNames()).containsExactly("a", "b", "a");
    }

    @Test
    @DisplayName("a colon inside a string literal is not a bind")
    void colonInLiteralIgnored() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT ':notabind' AS x, :real FROM t");

        assertThat(statement.translatedSql()).isEqualTo("SELECT ':notabind' AS x, ? FROM t");
        assertThat(statement.orderedNames()).containsExactly("real");
    }

    @Test
    @DisplayName("a PostgreSQL :: cast is not mistaken for a bind")
    void doubleColonCastIgnored() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT :value::text FROM t");

        assertThat(statement.translatedSql()).isEqualTo("SELECT ?::text FROM t");
        assertThat(statement.orderedNames()).containsExactly("value");
    }

    @Test
    @DisplayName("a colon inside a comment is not a bind")
    void colonInCommentIgnored() {
        NamedParameterStatement line = NamedParameterStatement.parse("SELECT :a FROM t -- :b in a comment\n");
        NamedParameterStatement block = NamedParameterStatement.parse("SELECT :a /* :b in a comment */ FROM t");

        assertThat(line.orderedNames()).containsExactly("a");
        assertThat(block.orderedNames()).containsExactly("a");
    }

    @Test
    @DisplayName("a statement with no binds is left unchanged")
    void noBinds() {
        NamedParameterStatement statement = NamedParameterStatement.parse("DELETE FROM test_data.orders");

        assertThat(statement.translatedSql()).isEqualTo("DELETE FROM test_data.orders");
        assertThat(statement.orderedNames()).isEmpty();
    }

    @Test
    @DisplayName("a double-quoted identifier containing a colon is preserved verbatim")
    void quotedIdentifierPreserved() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT \"weird:col\" FROM t WHERE id = :id");

        assertThat(statement.translatedSql()).isEqualTo("SELECT \"weird:col\" FROM t WHERE id = ?");
        assertThat(statement.orderedNames()).containsExactly("id");
    }

    @Test
    @DisplayName("a colon inside a dollar-quoted string is not a bind and the body is preserved verbatim")
    void colonInDollarQuotedLiteralIgnored() {
        NamedParameterStatement plain = NamedParameterStatement.parse("INSERT INTO test_data.t(body) VALUES ($$ hi :notabind $$) RETURNING :real");
        NamedParameterStatement tagged = NamedParameterStatement.parse("INSERT INTO test_data.t(body) VALUES ($body$ :notabind $body$) RETURNING :real");

        assertThat(plain.translatedSql()).isEqualTo("INSERT INTO test_data.t(body) VALUES ($$ hi :notabind $$) RETURNING ?");
        assertThat(plain.orderedNames()).containsExactly("real");
        assertThat(tagged.translatedSql()).isEqualTo("INSERT INTO test_data.t(body) VALUES ($body$ :notabind $body$) RETURNING ?");
        assertThat(tagged.orderedNames()).containsExactly("real");
    }

    @Test
    @DisplayName("a positional parameter ($1) is not a dollar quote and is preserved")
    void positionalParameterPreserved() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT $1, :real FROM t");

        assertThat(statement.translatedSql()).isEqualTo("SELECT $1, ? FROM t");
        assertThat(statement.orderedNames()).containsExactly("real");
    }

    @Test
    @DisplayName("a colon inside a backtick-quoted identifier is not a bind and the identifier is preserved verbatim")
    void colonInBacktickIdentifierIgnored() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT `weird:col` FROM t WHERE id = :id");

        assertThat(statement.translatedSql()).isEqualTo("SELECT `weird:col` FROM t WHERE id = ?");
        assertThat(statement.orderedNames()).containsExactly("id");
    }

    @Test
    @DisplayName("a bare CR (\\r) ends a line comment, so a :name in the live SQL after it is still rewritten")
    void bindAfterBareCarriageReturnLineComment() {
        // PostgreSQL ends a `--` comment on a lone '\r'; the rewriter must resume binding after it, otherwise the
        // ':b' would be left untouched and the executed statement would lose a parameter (mirror of the classifier
        // fix, keeping the two lexers aligned).
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT :a -- c\rFROM t WHERE b = :b");

        assertThat(statement.translatedSql()).isEqualTo("SELECT ? -- c\rFROM t WHERE b = ?");
        assertThat(statement.orderedNames()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a doubled single quote inside a literal is an escaped quote, not a literal boundary")
    void doubledSingleQuoteEscapeHandled() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT * FROM t WHERE note = 'it''s :notabind' AND id = :id");

        assertThat(statement.translatedSql()).isEqualTo("SELECT * FROM t WHERE note = 'it''s :notabind' AND id = ?");
        assertThat(statement.orderedNames()).containsExactly("id");
    }

    @Test
    @DisplayName("a doubled double quote inside an identifier is an escaped quote, not an identifier boundary")
    void doubledDoubleQuoteEscapeHandled() {
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT \"a\"\"b:notabind\" FROM t WHERE id = :id");

        assertThat(statement.translatedSql()).isEqualTo("SELECT \"a\"\"b:notabind\" FROM t WHERE id = ?");
        assertThat(statement.orderedNames()).containsExactly("id");
    }

    @Test
    @DisplayName("an unterminated literal / block comment / dollar quote does not break parsing (the driver rejects the malformed SQL)")
    void unterminatedSpansDoNotThrow() {
        assertThat(NamedParameterStatement.parse("SELECT * FROM t WHERE note = 'oops :x").orderedNames()).isEmpty();
        assertThat(NamedParameterStatement.parse("SELECT * FROM t /* oops :x").orderedNames()).isEmpty();
        assertThat(NamedParameterStatement.parse("SELECT $$ oops :x").orderedNames()).isEmpty();
    }

    @Test
    @DisplayName("a bind name follows ASCII identifier rules, so a non-ASCII character ends the name")
    void bindNameIsAsciiOnly() {
        // ':id' followed by a non-ASCII letter (Ф = Cyrillic Ef): the name ends at the ASCII boundary,
        // matching the documented [A-Za-z_][A-Za-z0-9_]* grammar and the classifier's ASCII \b for :testRunId.
        NamedParameterStatement statement = NamedParameterStatement.parse("SELECT :idФ FROM t");

        assertThat(statement.orderedNames()).containsExactly("id");
        assertThat(statement.translatedSql()).isEqualTo("SELECT ?Ф FROM t");
    }
}
