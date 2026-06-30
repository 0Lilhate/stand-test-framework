package ru.alfa.stand.test.core.validation;

/**
 * The single source of truth for SQL <em>span</em> boundaries — line/block comments, single-quoted strings,
 * double-quoted and MySQL backtick identifiers, and PostgreSQL dollar-quoted strings.
 *
 * <p>Two lexers must agree on exactly where these spans begin and end: {@link SqlStatementClassifier}'s
 * {@code strip()} (which blanks span content to a structural skeleton) and the DB adapter's {@code :name}
 * rewriter (which copies span content verbatim). They originally carried duplicate copies of this scanning
 * logic, and a drift between them was the root cause of a fail-open classification bug (a bare {@code \r}
 * ending a {@code --} comment in PostgreSQL but not in the lexer). Centralising the boundary rules here —
 * with each consumer keeping only its own per-character <em>policy</em> (blank vs copy) — makes that class
 * of drift structurally impossible.
 *
 * <p>The scanner reports boundaries only; it never decides whether a span is "code" or "data" and performs
 * no IO. Boundary rules mirror PostgreSQL: a {@code --} line comment ends at a line break, including a lone
 * {@code \r} (scan.l: {@code non_newline [^\n\r]}), with the terminator left for the caller; a block comment
 * ends after {@code *&#47;}; a quoted span ends after its closing delimiter, treating a doubled delimiter
 * ({@code ''} / {@code ""} / {@code ``}) as an escaped literal one; a dollar quote opens only on a valid
 * {@code $tag$} delimiter (so a positional parameter such as {@code $1} is not a span) and closes on the
 * exact same tag. An unterminated span ends at the end of input (the driver rejects the malformed SQL).
 */
public final class SqlSpanScanner {

    private SqlSpanScanner() {
    }

    /**
     * Returns the span that begins at {@code index}, or {@code null} if no span starts there (an ordinary
     * character, or a {@code $} that is not a dollar-quote opener).
     *
     * @param sql the SQL text
     * @param index the position to inspect (must be a valid index into {@code sql})
     * @return the span starting at {@code index}, or {@code null}
     */
    public static Span spanAt(String sql, int index) {
        int length = sql.length();
        char current = sql.charAt(index);
        char next = (index + 1 < length) ? sql.charAt(index + 1) : '\0';
        if (current == '-' && next == '-') {
            return new Span(SpanType.LINE_COMMENT, index, lineCommentEnd(sql, index));
        }
        if (current == '/' && next == '*') {
            return new Span(SpanType.BLOCK_COMMENT, index, blockCommentEnd(sql, index));
        }
        if (current == '\'') {
            return new Span(SpanType.SINGLE_QUOTE, index, quotedEnd(sql, index, '\''));
        }
        if (current == '"') {
            return new Span(SpanType.DOUBLE_QUOTE, index, quotedEnd(sql, index, '"'));
        }
        if (current == '`') {
            return new Span(SpanType.BACKTICK, index, quotedEnd(sql, index, '`'));
        }
        if (current == '$') {
            int delimiterLength = dollarQuoteDelimiterLength(sql, index);
            if (delimiterLength > 0) {
                return new Span(SpanType.DOLLAR_QUOTE, index, dollarQuotedEnd(sql, index, delimiterLength));
            }
        }
        return null;
    }

    private static int lineCommentEnd(String sql, int start) {
        int index = start;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\n' || current == '\r') {
                return index;
            }
            index++;
        }
        return index;
    }

    private static int blockCommentEnd(String sql, int start) {
        int index = start + 2;
        while (index < sql.length()) {
            if (sql.charAt(index) == '*' && index + 1 < sql.length() && sql.charAt(index + 1) == '/') {
                return index + 2;
            }
            index++;
        }
        return index;
    }

    private static int quotedEnd(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private static int dollarQuotedEnd(String sql, int start, int delimiterLength) {
        int index = start + delimiterLength;
        while (index < sql.length()) {
            if (sql.regionMatches(index, sql, start, delimiterLength)) {
                return index + delimiterLength;
            }
            index++;
        }
        return index;
    }

    /**
     * If a PostgreSQL dollar-quote opening delimiter ({@code $$} or {@code $tag$}) starts at {@code start}
     * (where {@code sql.charAt(start) == '$'}), returns its length; otherwise 0. The optional tag follows
     * unquoted-identifier rules ({@code [A-Za-z_][A-Za-z0-9_]*}, no {@code $}), so a positional parameter
     * such as {@code $1} is not mistaken for an opening delimiter.
     */
    private static int dollarQuoteDelimiterLength(String sql, int start) {
        int index = start + 1;
        if (index < sql.length() && (Character.isLetter(sql.charAt(index)) || sql.charAt(index) == '_')) {
            index++;
            while (index < sql.length() && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) {
                index++;
            }
        }
        if (index < sql.length() && sql.charAt(index) == '$') {
            return index - start + 1;
        }
        return 0;
    }

    /** The kinds of SQL span this scanner recognises. */
    public enum SpanType {

        /** A {@code --} line comment, ending before the next {@code \n} or {@code \r}. */
        LINE_COMMENT,

        /** A {@code /}{@code * … *}{@code /} block comment. */
        BLOCK_COMMENT,

        /** A single-quoted string literal ({@code '…'}, doubled {@code ''} escaped). */
        SINGLE_QUOTE,

        /** A double-quoted identifier ({@code "…"}, doubled {@code ""} escaped). */
        DOUBLE_QUOTE,

        /** A MySQL backtick-quoted identifier ({@code `…`}, doubled {@code ``} escaped). */
        BACKTICK,

        /** A PostgreSQL dollar-quoted string ({@code $$…$$} / {@code $tag$…$tag$}). */
        DOLLAR_QUOTE
    }

    /**
     * A half-open span {@code [start, end)} of a given {@link SpanType}.
     *
     * @param type the span kind
     * @param start the index of the opening character (the value passed to {@link #spanAt})
     * @param end the index just past the span; equals the input length for an unterminated span
     */
    public record Span(SpanType type, int start, int end) {
    }
}
