package ru.alfa.stand.test.core.validation;

/**
 * Classification of a single SQL statement for the DB guardrails (plan §8.8).
 *
 * <p>The classification is deliberately coarse and fail-closed: anything that cannot be proven safe
 * is {@link #REJECTED} rather than optimistically allowed. The DB adapter and (later) the static
 * {@code ScenarioValidator} both derive their allow/deny decision from this single classification, so
 * the two cannot drift apart.
 */
public enum SqlStatementKind {

    /** A pure read: {@code SELECT} (or {@code WITH … SELECT}). Always allowed. */
    READ,

    /** A constrained write: {@code INSERT}/{@code UPDATE}/{@code DELETE}. Allowed only under the write-guard. */
    WRITE,

    /** A destructive or DDL statement ({@code TRUNCATE}/{@code DROP}/{@code ALTER}/…). Always forbidden in the MVP. */
    DESTRUCTIVE,

    /** Unparseable, empty or multi-statement input. Fail-closed: never executed. */
    REJECTED
}
