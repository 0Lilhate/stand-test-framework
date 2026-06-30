package ru.alfa.stand.test.core.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed classifier for a single SQL statement (plan §8.8 — the DB safety prerequisite).
 *
 * <p>It is the single source of truth for "what is this SQL?" that both the DB adapter (at runtime,
 * before any IO) and the static {@code ScenarioValidator} / {@code ai-schema} derive their allow/deny
 * decisions from, so the runtime guard and the static checks cannot drift apart (plan §8.6).
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

    private SqlStatementClassifier() {
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
