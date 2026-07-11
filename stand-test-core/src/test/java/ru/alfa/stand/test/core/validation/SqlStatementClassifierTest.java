package ru.alfa.stand.test.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlStatementClassifierTest {

    @Test
    @DisplayName("a plain SELECT is classified as a read")
    void selectIsRead() {
        SqlClassification classification = SqlStatementClassifier.classify("SELECT status FROM test_data.orders WHERE id = :id");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.READ);
        assertThat(classification.isRead()).isTrue();
        assertThat(classification.leadingKeyword()).isEqualTo("SELECT");
        assertThat(classification.containsWhereClause()).isTrue();
    }

    @Test
    @DisplayName("a WITH ... SELECT is a read")
    void withSelectIsRead() {
        SqlClassification classification = SqlStatementClassifier.classify("WITH recent AS (SELECT * FROM test_data.orders) SELECT count(*) FROM recent");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.READ);
        assertThat(classification.leadingKeyword()).isEqualTo("WITH");
    }

    @Test
    @DisplayName("a data-modifying WITH (CTE wrapping a DELETE) is rejected, not treated as a read")
    void dataModifyingWithIsRejected() {
        SqlClassification classification = SqlStatementClassifier.classify("WITH gone AS (DELETE FROM test_data.orders RETURNING id) SELECT * FROM gone");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(classification.detail()).contains("WITH");
    }

    @Test
    @DisplayName("an INSERT into a schema-qualified table is a write with the schema extracted")
    void insertIsQualifiedWrite() {
        SqlClassification classification = SqlStatementClassifier.classify("INSERT INTO test_data.orders(id, test_run_id) VALUES (:id, :testRunId)");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.writeTargetSchemaQualified()).isTrue();
        assertThat(classification.writeSchema()).isEqualTo("test_data");
        assertThat(classification.writeTable()).isEqualTo("orders");
        assertThat(classification.referencesTestRunIdBind()).isTrue();
    }

    @Test
    @DisplayName("an UPDATE referencing the testRunId bind is recognised")
    void updateMarkerRecognised() {
        SqlClassification classification = SqlStatementClassifier.classify("UPDATE test_data.orders SET status = 'X' WHERE test_run_id = :testRunId");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.leadingKeyword()).isEqualTo("UPDATE");
        assertThat(classification.writeSchema()).isEqualTo("test_data");
        assertThat(classification.referencesTestRunIdBind()).isTrue();
    }

    @Test
    @DisplayName("a DELETE without the testRunId bind is a write that does not reference the marker")
    void deleteWithoutMarker() {
        SqlClassification classification = SqlStatementClassifier.classify("DELETE FROM test_data.orders");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.leadingKeyword()).isEqualTo("DELETE");
        assertThat(classification.referencesTestRunIdBind()).isFalse();
    }

    @Test
    @DisplayName("a write to an unqualified table yields no schema (cannot be proven)")
    void unqualifiedWriteHasNoSchema() {
        SqlClassification classification = SqlStatementClassifier.classify("INSERT INTO orders(id) VALUES (:id)");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.writeTargetSchemaQualified()).isFalse();
        assertThat(classification.writeSchema()).isNull();
        assertThat(classification.writeTable()).isEqualTo("orders");
    }

    @Test
    @DisplayName("TRUNCATE / DROP / ALTER are destructive")
    void ddlIsDestructive() {
        assertThat(SqlStatementClassifier.classify("TRUNCATE TABLE test_data.orders").kind()).isEqualTo(SqlStatementKind.DESTRUCTIVE);
        assertThat(SqlStatementClassifier.classify("DROP TABLE test_data.orders").kind()).isEqualTo(SqlStatementKind.DESTRUCTIVE);
        assertThat(SqlStatementClassifier.classify("ALTER TABLE test_data.orders ADD COLUMN x int").kind()).isEqualTo(SqlStatementKind.DESTRUCTIVE);
        assertThat(SqlStatementClassifier.classify("MERGE INTO test_data.orders USING dual ON (1=1)").kind()).isEqualTo(SqlStatementKind.DESTRUCTIVE);
    }

    @Test
    @DisplayName("a multi-statement batch is rejected (one statement per step)")
    void multiStatementRejected() {
        SqlClassification classification = SqlStatementClassifier.classify("SELECT 1; DELETE FROM test_data.orders");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(classification.detail()).contains("multiple statements");
    }

    @Test
    @DisplayName("a single trailing semicolon is allowed and does not count as multi-statement")
    void trailingSemicolonAllowed() {
        assertThat(SqlStatementClassifier.classify("SELECT 1;").kind()).isEqualTo(SqlStatementKind.READ);
        assertThat(SqlStatementClassifier.classify("SELECT 1 ;  ").kind()).isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("a semicolon inside a string literal does not trigger a multi-statement reject")
    void semicolonInsideLiteralIgnored() {
        SqlClassification classification = SqlStatementClassifier.classify("SELECT id FROM test_data.orders WHERE note = 'a;b'");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("a DELETE keyword hidden inside a comment does not change a SELECT classification")
    void commentsAreStripped() {
        SqlClassification lineComment = SqlStatementClassifier.classify("SELECT 1 -- DELETE FROM test_data.orders\n");
        SqlClassification blockComment = SqlStatementClassifier.classify("SELECT 1 /* ; DROP TABLE x */ FROM test_data.orders");

        assertThat(lineComment.kind()).isEqualTo(SqlStatementKind.READ);
        assertThat(blockComment.kind()).isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("blank or comment-only SQL is rejected")
    void blankRejected() {
        assertThat(SqlStatementClassifier.classify(null).kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(SqlStatementClassifier.classify("   ").kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(SqlStatementClassifier.classify("-- just a comment\n").kind()).isEqualTo(SqlStatementKind.REJECTED);
    }

    @Test
    @DisplayName("a double-quoted write target cannot be proven schema-qualified and yields no schema (fail-closed)")
    void quotedIdentifierFailsClosed() {
        SqlClassification classification = SqlStatementClassifier.classify("INSERT INTO \"test_data\".\"orders\"(id) VALUES (:id)");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.writeTargetSchemaQualified()).isFalse();
    }

    @Test
    @DisplayName("the testRunId bind is not matched when it is part of a longer identifier")
    void testRunIdBindBoundary() {
        assertThat(SqlStatementClassifier.classify("SELECT 1 WHERE x = :testRunIdOther").referencesTestRunIdBind()).isFalse();
        assertThat(SqlStatementClassifier.classify("SELECT 1 WHERE x = :testRunId").referencesTestRunIdBind()).isTrue();
    }

    @Test
    @DisplayName("a SELECT ... INTO is a write disguised as a read and is rejected")
    void selectIntoRejected() {
        assertThat(SqlStatementClassifier.classify("SELECT * INTO scratch.dump FROM test_data.orders").kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(SqlStatementClassifier.classify("SELECT id INTO OUTFILE '/tmp/x' FROM test_data.orders").kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(SqlStatementClassifier.classify("WITH c AS (SELECT 1) SELECT * INTO scratch.dump FROM c").kind()).isEqualTo(SqlStatementKind.REJECTED);
        // A plain read with "IN" (not "INTO") is unaffected.
        assertThat(SqlStatementClassifier.classify("SELECT id FROM test_data.orders WHERE id IN (1, 2)").kind()).isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("an INSERT ... ON CONFLICT / ON DUPLICATE KEY upsert is rejected (mutates existing rows)")
    void upsertRejected() {
        assertThat(SqlStatementClassifier.classify("INSERT INTO test_data.orders(id) VALUES (:id) ON CONFLICT (id) DO UPDATE SET status = 'X'").kind())
                .isEqualTo(SqlStatementKind.REJECTED);
        assertThat(SqlStatementClassifier.classify("INSERT INTO test_data.orders(id) VALUES (:id) ON DUPLICATE KEY UPDATE status = 'X'").kind())
                .isEqualTo(SqlStatementKind.REJECTED);
    }

    @Test
    @DisplayName("a 3-part catalog.schema.table write target is not provably schema-qualified (fail-closed)")
    void threePartNameNotQualified() {
        SqlClassification classification = SqlStatementClassifier.classify("INSERT INTO catalog.test_data.orders(id) VALUES (:id)");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.writeTargetSchemaQualified()).isFalse();
        assertThat(classification.writeSchema()).isNull();
    }

    @Test
    @DisplayName("a semicolon or keyword inside a dollar-quoted string does not change a SELECT classification")
    void dollarQuotedLiteralIgnored() {
        assertThat(SqlStatementClassifier.classify("SELECT id FROM test_data.orders WHERE note = $$a;b DROP TABLE x$$").kind())
                .isEqualTo(SqlStatementKind.READ);
        assertThat(SqlStatementClassifier.classify("SELECT id FROM test_data.orders WHERE note = $tag$a;b$tag$").kind())
                .isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("a stray single quote inside a dollar-quoted body no longer hides a trailing destructive statement (fail-closed)")
    void dollarQuotedQuoteDoesNotHideTrailingStatement() {
        // The $$'$$ body contains a single quote that, without dollar-quote awareness, would open a
        // spurious single-quoted span and blank across the real ';', hiding the DROP — classifying READ.
        SqlClassification classification = SqlStatementClassifier.classify("SELECT * FROM test_data.orders WHERE note = $$'$$ ; DROP TABLE test_data.orders --$$");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(classification.detail()).contains("multiple statements");
    }

    @Test
    @DisplayName("a positional parameter ($1) is not a dollar quote, so a trailing statement stays visible")
    void positionalParameterIsNotDollarQuote() {
        // If $1 were mistaken for a dollar-quote opener it would search for a closing $1, find none, blank
        // to end and hide the ';' — classifying READ. It must stay REJECTED as multi-statement.
        SqlClassification classification = SqlStatementClassifier.classify("SELECT $1 ; DROP TABLE test_data.orders");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(classification.detail()).contains("multiple statements");
    }

    @Test
    @DisplayName("an INSERT whose dollar-quoted value embeds a semicolon is still a clean schema-qualified write")
    void dollarQuotedInsertIsCleanWrite() {
        SqlClassification classification = SqlStatementClassifier.classify("INSERT INTO test_data.events(payload, test_run_id) VALUES ($$ {\"a\":1}; drop $$, :testRunId)");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.writeTargetSchemaQualified()).isTrue();
        assertThat(classification.writeSchema()).isEqualTo("test_data");
        assertThat(classification.writeTable()).isEqualTo("events");
        assertThat(classification.referencesTestRunIdBind()).isTrue();
    }

    @Test
    @DisplayName("an unterminated dollar quote is blanked to the end (malformed SQL the driver rejects), classified by its leading keyword")
    void unterminatedDollarQuoteBlankedToEnd() {
        // PostgreSQL cannot parse an unterminated dollar quote, so nothing executes; classifying by the
        // leading keyword (and letting the driver reject) is safe and consistent with unterminated '...'.
        assertThat(SqlStatementClassifier.classify("SELECT 1 $$ oops ; DROP TABLE test_data.orders").kind())
                .isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("a semicolon inside a backtick-quoted identifier does not change a SELECT classification")
    void backtickIdentifierIgnored() {
        assertThat(SqlStatementClassifier.classify("SELECT `weird;col` FROM t").kind()).isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("a stray single quote inside a backtick-quoted identifier no longer hides a trailing destructive statement (fail-closed)")
    void backtickIdentifierQuoteDoesNotHideTrailingStatement() {
        // Without backtick awareness the ' inside `a'b` opens a spurious single-quoted span and blanks across
        // the real ';', hiding the DROP and classifying READ.
        SqlClassification classification = SqlStatementClassifier.classify("SELECT * FROM `a'b` ; DROP TABLE test_data.orders");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(classification.detail()).contains("multiple statements");
    }

    @Test
    @DisplayName("a backtick-quoted write target cannot be proven schema-qualified and yields no schema (fail-closed)")
    void backtickWriteTargetFailsClosed() {
        SqlClassification classification = SqlStatementClassifier.classify("INSERT INTO `test_data`.`orders`(id) VALUES (:id)");

        assertThat(classification.kind()).isEqualTo(SqlStatementKind.WRITE);
        assertThat(classification.writeTargetSchemaQualified()).isFalse();
    }

    @Test
    @DisplayName("a GO batch separator (case-insensitive) on its own line is rejected")
    void goBatchSeparatorRejected() {
        assertThat(SqlStatementClassifier.classify("SELECT 1\nGO\nSELECT 2").kind()).isEqualTo(SqlStatementKind.REJECTED);
        SqlClassification lowercase = SqlStatementClassifier.classify("SELECT 1\ngo\nSELECT 2");
        assertThat(lowercase.kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(lowercase.detail()).contains("batch separators");
    }

    @Test
    @DisplayName("a SQL*Plus slash batch separator on its own line is rejected")
    void slashBatchSeparatorRejected() {
        assertThat(SqlStatementClassifier.classify("DELETE FROM test_data.orders\n/").kind()).isEqualTo(SqlStatementKind.REJECTED);
    }

    @Test
    @DisplayName("GO and / are only separators on their own line: inline use and use inside a literal are unaffected")
    void batchSeparatorNonTriggers() {
        // 'go' as an inline column reference, and a GO inside a string literal, must not be mistaken for a
        // batch separator (the check runs on the stripped skeleton and requires a line of just GO or /).
        assertThat(SqlStatementClassifier.classify("SELECT go, id FROM test_data.t").kind()).isEqualTo(SqlStatementKind.READ);
        assertThat(SqlStatementClassifier.classify("SELECT 'GO' FROM t").kind()).isEqualTo(SqlStatementKind.READ);
        assertThat(SqlStatementClassifier.classify("SELECT count(*) / 2 FROM test_data.t").kind()).isEqualTo(SqlStatementKind.READ);
    }

    @Test
    @DisplayName("a bare CR (\\r) terminates a PostgreSQL line comment, so a data-modifying tail hidden behind it is rejected, not read")
    void bareCarriageReturnLineCommentIsRejected() {
        // PostgreSQL ends a `--` comment on a lone '\r' (scan.l: non_newline [^\n\r]); the classifier must do the
        // same, otherwise a WITH-CTE DELETE/UPDATE after `--<...>\r` is mis-classified READ and sails past the
        // write-guard onto the stand as one unscoped statement (DBSEC-1).
        assertThat(SqlStatementClassifier.classify("WITH x AS (SELECT 1) --c\rDELETE FROM test_data.orders").kind()).isEqualTo(SqlStatementKind.REJECTED);
        assertThat(SqlStatementClassifier.classify("WITH x AS (SELECT 1) --c\rUPDATE test_data.orders SET status = 'X'").kind()).isEqualTo(SqlStatementKind.REJECTED);
        // The ';' hidden behind the CR-terminated comment becomes visible again -> multi-statement reject.
        assertThat(SqlStatementClassifier.classify("SELECT 1 --c\r; DROP TABLE test_data.orders").kind()).isEqualTo(SqlStatementKind.REJECTED);
        // A batch separator (GO) separated only by bare CRs is still detected.
        assertThat(SqlStatementClassifier.classify("SELECT 1\rGO\rSELECT 2").kind()).isEqualTo(SqlStatementKind.REJECTED);
    }

    @Test
    @DisplayName("insertColumns parses the explicit INSERT column list, ignoring case, quotes-in-strings and comments")
    void insertColumnsParsesColumnList() {
        assertThat(SqlStatementClassifier.insertColumns("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)"))
                .containsExactlyInAnyOrder("id", "status", "test_run_id");
        // Case-folded; a comment inside the column list is stripped and adds no phantom column.
        assertThat(SqlStatementClassifier.insertColumns("INSERT INTO test_data.orders(ID, /* note */ TEST_RUN_ID) VALUES (:id, :testRunId)"))
                .containsExactlyInAnyOrder("id", "test_run_id");
    }

    @Test
    @DisplayName("insertColumns returns empty for a non-INSERT or an INSERT with no explicit column list")
    void insertColumnsEmptyWhenAbsent() {
        assertThat(SqlStatementClassifier.insertColumns("INSERT INTO test_data.orders VALUES (:id, :testRunId)")).isEmpty();
        assertThat(SqlStatementClassifier.insertColumns("UPDATE test_data.orders SET status = 'X' WHERE test_run_id = :testRunId")).isEmpty();
        assertThat(SqlStatementClassifier.insertColumns(null)).isEmpty();
    }
}
