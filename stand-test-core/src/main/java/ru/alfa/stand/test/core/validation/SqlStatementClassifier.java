package ru.alfa.stand.test.core.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed classifier for a single SQL statement (plan §8.8 — the DB safety prerequisite).
 *
 * <p>It is the single source of truth for "what is this SQL?" that both the DB adapter (at runtime,
 * before any IO) and the {@code ScenarioValidator} derive their allow/deny decisions from, so the guard
 * and the pre-flight checks cannot drift apart (plan §8.6).
 *
 * <p><strong>Why a custom classifier and not a SQL parser.</strong> Comments, string literals,
 * multi-statement batches and schema qualification make naive keyword matching unsafe — a missed
 * destructive statement on a real DEV/IFT stand means data loss. This classifier therefore first strips
 * line/block comments and quoted spans to a structural <em>skeleton</em>, then makes a coarse,
 * conservative decision over that skeleton:
 *
 * <ul>
 *   <li>empty input, or a statement separator ({@code ;}) anywhere but a single trailing one →
 *       {@link SqlStatementKind#REJECTED} (one statement per step, plan §8.8);</li>
 *   <li>leading {@code SELECT} / {@code WITH … SELECT} → {@link SqlStatementKind#READ} (a {@code WITH}
 *       that also carries a write/DDL keyword is data-modifying and is rejected — not silently read);</li>
 *   <li>leading {@code INSERT} / {@code UPDATE} / {@code DELETE} → {@link SqlStatementKind#WRITE},
 *       with the (schema-qualified) target extracted for the schema whitelist;</li>
 *   <li>anything else ({@code TRUNCATE}/{@code DROP}/{@code ALTER}/{@code CREATE}/{@code MERGE}/…) →
 *       {@link SqlStatementKind#DESTRUCTIVE}.</li>
 * </ul>
 *
 * <p>Classifying never throws and never performs IO: it returns a {@link SqlClassification} the caller
 * maps to a {@code StandTestException} (adapter) or a {@code ValidationIssue} (validator).
 */
public final class SqlStatementClassifier {

    private static final Pattern LEADING_KEYWORD = Pattern.compile("^[(\\s]*([A-Za-z]+)");
    private static final Pattern INSERT_TARGET = Pattern.compile("(?i)\\bINSERT\\s+INTO\\s+([A-Za-z0-9_.\"]+)");
    private static final Pattern UPDATE_TARGET = Pattern.compile("(?i)\\bUPDATE\\s+([A-Za-z0-9_.\"]+)");
    private static final Pattern DELETE_TARGET = Pattern.compile("(?i)\\bDELETE\\s+FROM\\s+([A-Za-z0-9_.\"]+)");
    private static final Pattern TEST_RUN_ID_BIND = Pattern.compile("(?<![:\\w]):testRunId\\b");
    private static final Pattern WHERE_CLAUSE = Pattern.compile("(?i)\\bWHERE\\b");
    // A WITH (CTE) statement is read-only unless it embeds a data-modifying or DDL leaf, which cannot be
    // classified safely in the MVP grammar; presence of any such keyword forces a fail-closed reject.
    private static final Pattern DATA_MODIFYING_KEYWORD =
            Pattern.compile("(?i)\\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE|GRANT|REVOKE|CALL|EXEC|EXECUTE)\\b");
    // A SELECT-level INTO is a write, not a read: `SELECT ... INTO new_table` creates and populates a
    // table (PostgreSQL/SQL Server) and `SELECT ... INTO OUTFILE/DUMPFILE` writes a server-side file
    // (MySQL). Treating it as a read would sail it past the entire write-guard, so it is rejected.
    private static final Pattern SELECT_INTO = Pattern.compile("(?i)\\bINTO\\b");
    // An INSERT upsert tail mutates pre-existing rows just like an UPDATE, but hides behind the INSERT
    // leading keyword and so escapes the testRunId-predicate requirement; rejected fail-closed in the MVP.
    private static final Pattern UPSERT_CLAUSE = Pattern.compile("(?i)\\bON\\s+CONFLICT\\b|\\bON\\s+DUPLICATE\\s+KEY\\b");
    // Blocking/side-effecting time functions (a delay/DoS primitive). They have no place in a bounded
    // stand test and can hide behind a READ classification (e.g. SELECT pg_sleep(3600)), so both the static
    // validator and the runtime DB write-guard reject them via {@link #containsSideEffectingTimeFunction}.
    private static final Pattern SIDE_EFFECT_TIME_FUNCTION =
            Pattern.compile("\\b(?:pg_sleep|sleep|waitfor|benchmark|dbms_lock)\\b", Pattern.CASE_INSENSITIVE);

    private SqlStatementClassifier() {
    }

    /**
     * Returns {@code true} if the statement calls a blocking/side-effecting time function
     * ({@code pg_sleep}/{@code sleep}/{@code waitfor}/{@code benchmark}/{@code dbms_lock}) — a delay/DoS
     * primitive that has no place in a bounded stand test and would otherwise hide behind a {@code READ}
     * classification (e.g. {@code SELECT pg_sleep(3600)} runs a real sleep on the stand while classifying as
     * a harmless read). Detected over the same stripped skeleton {@link #classify} uses, so a match inside a
     * comment or a string literal does not trigger (fewer false positives than a raw-text scan).
     *
     * <p>The static {@code ScenarioValidator} (over inline {@code sql}) and the DB write-guard (over the
     * exact assembled SQL, so {@code sqlResource} content is covered too) both call this, so the inline and
     * resource-loaded paths meet the same net and cannot drift (plan §8.6/§8.8).
     *
     * @param sql the raw SQL text
     * @return true if a sleep/side-effecting time function is present outside comments/literals
     */
    public static boolean containsSideEffectingTimeFunction(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return SIDE_EFFECT_TIME_FUNCTION.matcher(strip(sql)).find();
    }

    /**
     * Classifies a single SQL statement.
     *
     * @param sql the raw SQL text (the value actually sent to JDBC)
     * @return the classification (never null; {@link SqlStatementKind#REJECTED} when unsafe to classify)
     */
    public static SqlClassification classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return rejected("SQL is empty");
        }
        String skeleton = strip(sql);
        String trimmed = stripTrailingSemicolon(skeleton.strip());
        if (trimmed.isBlank()) {
            return rejected("SQL is empty after removing comments and literals");
        }
        if (trimmed.indexOf(';') >= 0) {
            return rejected("multiple statements are not allowed (one statement per step)");
        }
        if (containsBatchSeparatorLine(skeleton)) {
            return rejected("batch separators (a line of just GO or /) are not allowed (one statement per step)");
        }
        String keyword = leadingKeyword(trimmed);
        if (keyword.isEmpty()) {
            return rejected("statement does not start with a SQL keyword");
        }
        String upper = keyword.toUpperCase(java.util.Locale.ROOT);
        boolean referencesTestRunId = TEST_RUN_ID_BIND.matcher(trimmed).find();
        boolean hasWhere = WHERE_CLAUSE.matcher(trimmed).find();
        return switch (upper) {
            case "SELECT" -> classifySelect(trimmed, upper, referencesTestRunId, hasWhere);
            case "WITH" -> classifyWith(trimmed, upper, referencesTestRunId, hasWhere);
            case "INSERT", "UPDATE", "DELETE" -> classifyWrite(trimmed, upper, referencesTestRunId, hasWhere);
            default -> destructive(upper);
        };
    }

    /**
     * Returns the lower-cased plain column identifiers of an {@code INSERT}'s explicit column list (the
     * {@code (a, b, c)} that precedes {@code VALUES}), or an empty set when the statement is not an
     * {@code INSERT} or carries no explicit column list. The column list is read from the same stripped
     * skeleton {@link #classify} uses, so commas/parens inside comments or string literals are ignored;
     * a quoted identifier is blanked by that pass and so is reported absent (fail-closed — a caller
     * checking column membership should require a plain identifier).
     *
     * <p>The DB write-guard uses this to verify that a {@code db.seed} INSERT actually tags the reserved
     * {@code testRunId} column that its paired {@code db.cleanup} filters on (plan §15) — a textual
     * {@code :testRunId} reference alone does not prove the row is reapable by the run's own cleanup.
     *
     * @param sql the raw SQL text
     * @return the immutable set of lower-cased column identifiers in the INSERT column list (never null)
     */
    public static Set<String> insertColumns(String sql) {
        if (sql == null) {
            return Set.of();
        }
        String skeleton = strip(sql);
        Matcher matcher = INSERT_TARGET.matcher(skeleton);
        if (!matcher.find()) {
            return Set.of();
        }
        int cursor = matcher.end();
        while (cursor < skeleton.length() && Character.isWhitespace(skeleton.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= skeleton.length() || skeleton.charAt(cursor) != '(') {
            return Set.of();
        }
        int depth = 0;
        int close = -1;
        for (int index = cursor; index < skeleton.length(); index++) {
            char current = skeleton.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    close = index;
                    break;
                }
            }
        }
        if (close < 0) {
            return Set.of();
        }
        Set<String> columns = new LinkedHashSet<>();
        for (String token : splitTopLevel(skeleton.substring(cursor + 1, close))) {
            String column = token.strip().replace("\"", "").toLowerCase(Locale.ROOT);
            if (!column.isEmpty()) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Returns the number of top-level {@code VALUES} row tuples of an {@code INSERT}: {@code 1} for a
     * single-row {@code INSERT ... VALUES (...)}, {@code N > 1} for a multi-row
     * {@code INSERT ... VALUES (...),(...)}, and {@code 0} when the statement is not a {@code VALUES}
     * INSERT at all ({@code INSERT ... SELECT}, {@code INSERT ... DEFAULT VALUES}, the {@code SET} form,
     * or not an INSERT). Read from the same stripped skeleton {@link #classify} uses, so parens/commas
     * inside comments or literals are ignored.
     *
     * <p>The undo-log guard uses this to fail closed on any {@code db.write} INSERT whose written row set
     * cannot be fully captured for compensation: only a single-row {@code VALUES} INSERT yields exactly one
     * primary key to delete. A {@code 0} return (no {@code VALUES}) and an {@code N > 1} return are both
     * un-undoable in the MVP and must be rejected.
     *
     * @param sql the raw SQL text
     * @return the top-level {@code VALUES} tuple count, or {@code 0} when there is no top-level VALUES list
     */
    public static int insertValuesRowArity(String sql) {
        if (sql == null) {
            return 0;
        }
        String skeleton = strip(sql);
        Matcher insert = INSERT_TARGET.matcher(skeleton);
        if (!insert.find()) {
            return 0;
        }
        int valuesStart = topLevelKeyword(skeleton, "VALUES", insert.end());
        if (valuesStart < 0) {
            return 0;
        }
        int rows = 0;
        int depth = 0;
        for (int index = valuesStart + "VALUES".length(); index < skeleton.length(); index++) {
            char current = skeleton.charAt(index);
            if (current == '(') {
                if (depth == 0) {
                    rows++;
                }
                depth++;
            } else if (current == ')') {
                depth--;
            }
        }
        return rows;
    }

    private static int topLevelKeyword(String skeleton, String keyword, int from) {
        int depth = 0;
        for (int index = from; index < skeleton.length(); index++) {
            char current = skeleton.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (depth == 0 && isKeywordAt(skeleton, index, keyword)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isKeywordAt(String skeleton, int index, String keyword) {
        int length = keyword.length();
        if (index + length > skeleton.length() || !skeleton.regionMatches(true, index, keyword, 0, length)) {
            return false;
        }
        char before = (index == 0) ? ' ' : skeleton.charAt(index - 1);
        char after = (index + length < skeleton.length()) ? skeleton.charAt(index + length) : ' ';
        return !isWordChar(before) && !isWordChar(after);
    }

    private static boolean isWordChar(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static List<String> splitTopLevel(String content) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == ',' && depth == 0) {
                parts.add(content.substring(last, index));
                last = index + 1;
            }
        }
        parts.add(content.substring(last));
        return parts;
    }

    private static SqlClassification classifySelect(String skeleton, String keyword, boolean testRunId, boolean hasWhere) {
        if (SELECT_INTO.matcher(skeleton).find()) {
            return rejected("SELECT ... INTO writes data or a file and cannot be classified as a read");
        }
        return read(keyword, testRunId, hasWhere);
    }

    private static SqlClassification classifyWith(String skeleton, String keyword, boolean testRunId, boolean hasWhere) {
        if (DATA_MODIFYING_KEYWORD.matcher(skeleton).find() || SELECT_INTO.matcher(skeleton).find()) {
            return rejected("WITH statement embeds a data-modifying, DDL or INTO keyword and cannot be classified safely");
        }
        return read(keyword, testRunId, hasWhere);
    }

    private static SqlClassification classifyWrite(String skeleton, String keyword, boolean testRunId, boolean hasWhere) {
        if ("INSERT".equals(keyword) && UPSERT_CLAUSE.matcher(skeleton).find()) {
            return rejected("INSERT ... ON CONFLICT / ON DUPLICATE KEY upsert mutates existing rows and is not allowed in the MVP grammar");
        }
        String target = writeTarget(skeleton, keyword);
        String schema = null;
        String table = null;
        boolean qualified = false;
        if (target != null) {
            String cleaned = target.replace("\"", "");
            int dot = cleaned.indexOf('.');
            if (dot > 0 && dot < cleaned.length() - 1) {
                String candidateSchema = cleaned.substring(0, dot);
                String candidateTable = cleaned.substring(dot + 1);
                if (candidateTable.indexOf('.') < 0) {
                    // Exactly two parts (schema.table): the only form whose schema is provable. A 3-part
                    // catalog.schema.table name leaves more than one dot — its schema cannot be proven, so it
                    // stays unqualified and the write-guard rejects it (fail-closed, plan §8.8).
                    schema = candidateSchema;
                    table = candidateTable;
                    qualified = true;
                } else {
                    table = cleaned;
                }
            } else {
                table = cleaned;
            }
        }
        return new SqlClassification(
                SqlStatementKind.WRITE, keyword + " write", keyword, schema, table, qualified, testRunId, hasWhere);
    }

    private static String writeTarget(String skeleton, String keyword) {
        Matcher matcher = switch (keyword) {
            case "INSERT" -> INSERT_TARGET.matcher(skeleton);
            case "UPDATE" -> UPDATE_TARGET.matcher(skeleton);
            case "DELETE" -> DELETE_TARGET.matcher(skeleton);
            default -> null;
        };
        if (matcher != null && matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static SqlClassification read(String keyword, boolean testRunId, boolean hasWhere) {
        return new SqlClassification(SqlStatementKind.READ, keyword + " read", keyword, null, null, false, testRunId, hasWhere);
    }

    private static SqlClassification destructive(String keyword) {
        return new SqlClassification(SqlStatementKind.DESTRUCTIVE, keyword + " is destructive/DDL", keyword, null, null, false, false, false);
    }

    private static SqlClassification rejected(String reason) {
        return new SqlClassification(SqlStatementKind.REJECTED, reason, "", null, null, false, false, false);
    }

    private static String leadingKeyword(String skeleton) {
        Matcher matcher = LEADING_KEYWORD.matcher(skeleton);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String stripTrailingSemicolon(String skeleton) {
        String result = skeleton;
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).strip();
        }
        return result;
    }

    /**
     * Replaces span content (line/block comments and quoted/dollar-quoted spans, with boundaries from
     * {@link SqlSpanScanner}) with spaces, so that a {@code ;}, keyword or {@code .} appearing inside a
     * comment or literal is never mistaken for statement structure. A quoted identifier is blanked rather
     * than preserved — a quoted write target therefore yields no extractable schema and fails closed under
     * the write-guard. Newlines inside block comments and dollar-quoted strings are preserved so the
     * batch-separator line check still sees line structure; everywhere else a newline is blanked to a space.
     *
     * <p>The span boundary rules (dollar-quoting detected before single quotes, a {@code --} line comment
     * ending on a lone {@code \r}, doubled-delimiter escapes) live in {@link SqlSpanScanner}, shared with the
     * DB adapter's {@code :name} rewriter so the two lexers cannot drift. Square brackets are deliberately
     * <em>not</em> a span: on PostgreSQL {@code […]} is array-subscript syntax ({@code tags[1]}), not a
     * quoted identifier as in T-SQL, so blanking it would corrupt valid SQL.
     */
    private static String strip(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            SqlSpanScanner.Span span = SqlSpanScanner.spanAt(sql, index);
            if (span != null) {
                blankSpan(sql, span, out);
                index = span.end();
            } else {
                out.append(sql.charAt(index));
                index++;
            }
        }
        return out.toString();
    }

    /**
     * Blanks a span's characters to spaces, preserving {@code \n} inside block comments and dollar-quoted
     * strings (whose bodies may legally span lines) so the batch-separator line check still works.
     */
    private static void blankSpan(String sql, SqlSpanScanner.Span span, StringBuilder out) {
        boolean preserveNewline = span.type() == SqlSpanScanner.SpanType.BLOCK_COMMENT
                || span.type() == SqlSpanScanner.SpanType.DOLLAR_QUOTE;
        for (int index = span.start(); index < span.end(); index++) {
            char current = sql.charAt(index);
            out.append((preserveNewline && current == '\n') ? '\n' : ' ');
        }
    }

    /**
     * Detects a client-tool batch separator — a line that, once comments and literals are stripped, is
     * exactly {@code GO} (sqlcmd/SSMS, case-insensitive) or {@code /} (SQL*Plus). Such a separator means the
     * input is a multi-statement batch script, which is never a single JDBC statement, so it is rejected
     * fail-closed (plan §8.8). Run on the skeleton so a {@code GO}/{@code /} inside a comment or string does
     * not trigger. Trade-off: a column or table literally named {@code go} placed alone on its own line is
     * falsely rejected — accepted as a rare, fail-closed (safe) false positive.
     */
    private static boolean containsBatchSeparatorLine(String skeleton) {
        for (String line : skeleton.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.equals("/") || stripped.equalsIgnoreCase("GO")) {
                return true;
            }
        }
        return false;
    }
}
