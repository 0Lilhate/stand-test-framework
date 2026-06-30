package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.validation.SqlSpanScanner.Span;
import ru.alfa.stand.test.core.validation.SqlSpanScanner.SpanType;

class SqlSpanScannerTest {

    @Test
    @DisplayName("no span starts at an ordinary character, a positional $1 or a colon")
    void noSpanAtOrdinaryCharacter() {
        assertThat(SqlSpanScanner.spanAt("SELECT a", 0)).isNull();
        assertThat(SqlSpanScanner.spanAt("a $1 b", 2)).isNull();
        assertThat(SqlSpanScanner.spanAt("a :b", 2)).isNull();
    }

    @Test
    @DisplayName("a line comment ends before \\n or a lone \\r, with the terminator excluded")
    void lineCommentEndsAtLineBreak() {
        assertThat(SqlSpanScanner.spanAt("a -- c\nd", 2)).isEqualTo(new Span(SpanType.LINE_COMMENT, 2, 6));
        assertThat(SqlSpanScanner.spanAt("a -- c\rd", 2)).isEqualTo(new Span(SpanType.LINE_COMMENT, 2, 6));
        assertThat(SqlSpanScanner.spanAt("a -- c", 2)).isEqualTo(new Span(SpanType.LINE_COMMENT, 2, 6));
    }

    @Test
    @DisplayName("a block comment ends after */ (or at end of input when unterminated)")
    void blockCommentEndsAfterClose() {
        assertThat(SqlSpanScanner.spanAt("a /* c */ d", 2)).isEqualTo(new Span(SpanType.BLOCK_COMMENT, 2, 9));
        assertThat(SqlSpanScanner.spanAt("a /* c", 2)).isEqualTo(new Span(SpanType.BLOCK_COMMENT, 2, 6));
    }

    @Test
    @DisplayName("a single-quoted string treats '' as an escape and ends after the real closing quote")
    void singleQuoteHandlesDoublingAndTruncation() {
        assertThat(SqlSpanScanner.spanAt("a 'b''c' d", 2)).isEqualTo(new Span(SpanType.SINGLE_QUOTE, 2, 8));
        assertThat(SqlSpanScanner.spanAt("''", 0)).isEqualTo(new Span(SpanType.SINGLE_QUOTE, 0, 2));
        assertThat(SqlSpanScanner.spanAt("'b", 0)).isEqualTo(new Span(SpanType.SINGLE_QUOTE, 0, 2));
    }

    @Test
    @DisplayName("double-quoted and backtick identifiers are spans with their own delimiter")
    void quotedIdentifiers() {
        assertThat(SqlSpanScanner.spanAt("\"a\"\"b\"", 0)).isEqualTo(new Span(SpanType.DOUBLE_QUOTE, 0, 6));
        assertThat(SqlSpanScanner.spanAt("`a`", 0)).isEqualTo(new Span(SpanType.BACKTICK, 0, 3));
    }

    @Test
    @DisplayName("a dollar quote opens only on a valid $tag$ and closes on the same tag")
    void dollarQuote() {
        assertThat(SqlSpanScanner.spanAt("$$a;b$$", 0)).isEqualTo(new Span(SpanType.DOLLAR_QUOTE, 0, 7));
        assertThat(SqlSpanScanner.spanAt("$t$x$t$", 0)).isEqualTo(new Span(SpanType.DOLLAR_QUOTE, 0, 7));
        assertThat(SqlSpanScanner.spanAt("$$$$", 0)).isEqualTo(new Span(SpanType.DOLLAR_QUOTE, 0, 4));
        assertThat(SqlSpanScanner.spanAt("$$ oops", 0)).isEqualTo(new Span(SpanType.DOLLAR_QUOTE, 0, 7));
    }

    @Test
    @DisplayName("quoted identifiers handle a doubled delimiter and an unterminated span")
    void quotedIdentifierEdges() {
        assertThat(SqlSpanScanner.spanAt("`a``b`", 0)).isEqualTo(new Span(SpanType.BACKTICK, 0, 6));
        assertThat(SqlSpanScanner.spanAt("\"x", 0)).isEqualTo(new Span(SpanType.DOUBLE_QUOTE, 0, 2));
        assertThat(SqlSpanScanner.spanAt("`x", 0)).isEqualTo(new Span(SpanType.BACKTICK, 0, 2));
    }

    @Test
    @DisplayName("a dollar-quote tag may contain digits or start with '_'; $1$ and an unclosed tag are not openers")
    void dollarQuoteTagsAndNonOpeners() {
        assertThat(SqlSpanScanner.spanAt("$a1$x$a1$", 0)).isEqualTo(new Span(SpanType.DOLLAR_QUOTE, 0, 9));
        assertThat(SqlSpanScanner.spanAt("$_t$x$_t$", 0)).isEqualTo(new Span(SpanType.DOLLAR_QUOTE, 0, 9));
        assertThat(SqlSpanScanner.spanAt("$1$ x", 0)).isNull();
        assertThat(SqlSpanScanner.spanAt("$abc x", 0)).isNull();
    }
}
