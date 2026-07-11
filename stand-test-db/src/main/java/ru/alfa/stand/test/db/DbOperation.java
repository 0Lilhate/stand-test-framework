package ru.alfa.stand.test.db;

import java.util.Objects;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The four DB step operations (plan §4 / §8.8), each bound to its core step type.
 *
 * <p>{@code db.query} is a one-shot read that captures column values; {@code db.expectEventually} polls
 * a read until a single value matches (or times out); {@code db.seed} and {@code db.cleanup} are the
 * only operations allowed to write, and only under the write-guard ({@link DbWriteGuard}).
 */
public enum DbOperation {

    /** One-shot {@code SELECT} that captures column values into the run's variable store. */
    QUERY("db.query"),

    /** Polls a {@code SELECT} through the awaiter until its single value matches the expected value. */
    EXPECT_EVENTUALLY("db.expectEventually"),

    /** Constrained write that prepares test data (typically an {@code INSERT}). */
    SEED("db.seed"),

    /** Constrained delete-by-{@code testRunId} that removes a run's test data. */
    CLEANUP("db.cleanup"),

    /**
     * Business write ({@code INSERT}) whose effect is undone automatically by a primary-key-scoped
     * compensation registered into the run's undo-log (no {@code testRunId} marker column required). See
     * {@code docs/arch/stand-test-db-rollback-design.md}.
     */
    WRITE("db.write");

    private final String stepType;

    DbOperation(String stepType) {
        this.stepType = stepType;
    }

    /**
     * Returns the core step type this operation produces (for example {@code db.query}).
     *
     * @return the step type
     */
    public String stepType() {
        return stepType;
    }

    /**
     * Returns whether this operation is permitted to write (seed, cleanup or an undo-captured write).
     *
     * @return true for {@link #SEED}, {@link #CLEANUP} and {@link #WRITE}
     */
    public boolean isWrite() {
        return this == SEED || this == CLEANUP || this == WRITE;
    }

    /**
     * Resolves the operation for a core step type.
     *
     * @param stepType the step type (for example {@code db.seed})
     * @return the matching operation
     * @throws StandTestException if the step type is not a DB operation
     */
    public static DbOperation fromStepType(String stepType) {
        Objects.requireNonNull(stepType, "stepType must not be null");
        for (DbOperation operation : values()) {
            if (operation.stepType.equals(stepType)) {
                return operation;
            }
        }
        throw new StandTestException("Unsupported DB step type: '" + stepType + "'");
    }
}
