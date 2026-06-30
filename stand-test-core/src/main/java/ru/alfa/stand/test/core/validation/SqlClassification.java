package ru.alfa.stand.test.core.validation;

import java.util.Objects;

/**
 * Immutable result of classifying a single SQL statement (plan §8.8).
 *
 * <p>It carries the {@link SqlStatementKind kind} plus the facts a write-guard needs to decide whether
 * a write is permitted: the leading keyword, the (schema-qualified) write target, whether the statement
 * references the reserved {@code :testRunId} bind (the {@code UPDATE}/{@code DELETE} marker) and whether
 * it carries a {@code WHERE} clause. {@code writeSchema}/{@code writeTable} are {@code null} for reads,
 * destructive statements and writes whose target could not be extracted; consumers must treat a missing
 * schema as "not proven" and fail closed.
 *
 * @param kind the statement classification
 * @param detail a short human-readable note (the rejection reason when {@link SqlStatementKind#REJECTED}, else a description)
 * @param leadingKeyword the upper-cased leading keyword (for example {@code DELETE}), or empty when rejected
 * @param writeSchema the schema of a schema-qualified write target, or null
 * @param writeTable the table token of a write target, or null
 * @param writeTargetSchemaQualified whether a write target was found and is schema-qualified
 * @param referencesTestRunIdBind whether the statement references the reserved {@code :testRunId} bind
 * @param containsWhereClause whether the statement carries a {@code WHERE} clause
 */
public record SqlClassification(
        SqlStatementKind kind,
        String detail,
        String leadingKeyword,
        String writeSchema,
        String writeTable,
        boolean writeTargetSchemaQualified,
        boolean referencesTestRunIdBind,
        boolean containsWhereClause) {

    public SqlClassification {
        Objects.requireNonNull(kind, "kind must not be null");
        detail = (detail == null) ? "" : detail;
        leadingKeyword = (leadingKeyword == null) ? "" : leadingKeyword;
    }

    /**
     * Returns whether the statement was rejected (unparseable, empty or multi-statement).
     *
     * @return true if the kind is {@link SqlStatementKind#REJECTED}
     */
    public boolean isRejected() {
        return kind == SqlStatementKind.REJECTED;
    }

    /**
     * Returns whether the statement is a read.
     *
     * @return true if the kind is {@link SqlStatementKind#READ}
     */
    public boolean isRead() {
        return kind == SqlStatementKind.READ;
    }

    /**
     * Returns whether the statement is a constrained write.
     *
     * @return true if the kind is {@link SqlStatementKind#WRITE}
     */
    public boolean isWrite() {
        return kind == SqlStatementKind.WRITE;
    }
}
