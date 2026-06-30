package ru.alfa.stand.test.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.validation.SqlStatementClassifier;

/**
 * Anti-drift differential test for the two lexers built on the shared
 * {@link ru.alfa.stand.test.core.validation.SqlSpanScanner}: the
 * classifier's {@code strip()} (whether {@code :testRunId} is a live bind, exposed via
 * {@code referencesTestRunIdBind()}) and the {@code :name} rewriter (whether {@code testRunId} is bound)
 * must agree on whether a given {@code :testRunId} is inside a comment/literal span. A divergence here was
 * the core of DBSEC-1; this locks the two lexers together so a future edit to one cannot silently drift.
 */
class SqlLexerConsistencyTest {

    @Test
    @DisplayName("the classifier and the :name rewriter agree on whether :testRunId is a live bind")
    void classifierAndRewriterAgreeOnTestRunIdSpanMembership() {
        List<String> corpus = List.of(
                "DELETE FROM t WHERE c = :testRunId",
                "DELETE FROM t WHERE note = ':testRunId'",
                "DELETE FROM t WHERE note = \":testRunId\"",
                "DELETE FROM t WHERE note = `:testRunId`",
                "DELETE FROM t -- :testRunId\n",
                "DELETE FROM t /* :testRunId */ WHERE c = 1",
                "DELETE FROM t WHERE note = $$ :testRunId $$",
                "DELETE FROM t WHERE note = $tag$ :testRunId $tag$",
                "UPDATE t SET note = ':a''b :testRunId' WHERE c = :testRunId",
                "DELETE FROM t -- x\r WHERE c = :testRunId",
                "SELECT $1, :testRunId FROM t",
                "SELECT x::testRunId FROM t",
                "DELETE FROM t WHERE c = :testRunIdX",
                "DELETE FROM t WHERE c = :testRunId -- :testRunId");
        for (String sql : corpus) {
            NamedParameterStatement rewritten = NamedParameterStatement.parse(sql);
            boolean classifierSeesBind = SqlStatementClassifier.classify(sql).referencesTestRunIdBind();
            boolean rewriterSeesBind = rewritten.orderedNames().contains("testRunId");
            assertThat(classifierSeesBind)
                    .withFailMessage("lexers disagree on :testRunId for <%s>: classifier=%s rewriter=%s",
                            sql, classifierSeesBind, rewriterSeesBind)
                    .isEqualTo(rewriterSeesBind);
            // Every bind becomes exactly one '?'; the corpus carries no literal '?', so the counts match.
            long questionMarks = rewritten.translatedSql().chars().filter(character -> character == '?').count();
            assertThat(questionMarks)
                    .withFailMessage("'?' count %s != bind count %s for <%s>", questionMarks, rewritten.orderedNames().size(), sql)
                    .isEqualTo(rewritten.orderedNames().size());
        }
    }
}
