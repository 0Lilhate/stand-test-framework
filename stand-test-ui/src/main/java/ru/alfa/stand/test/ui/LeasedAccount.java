package ru.alfa.stand.test.ui;

import java.time.Duration;

/**
 * Exclusive possession of one {@link UiAccount} for the duration of one scenario run.
 *
 * <p>Exclusivity is the whole point: while a lease is open no other run can be given the same account, so
 * two parallel tests cannot sign in as the same user, invalidate each other's session or race on the
 * browser state that belongs to it. The lease is registered in the run's {@code ResourceScope}, which the
 * runner closes in its {@code finally} — the same mechanism that closes the browser — so an account comes
 * back to the pool on a green run, on a failed run and on a run that threw somewhere unexpected. There is
 * no second release path to forget.
 *
 * <p>{@link #close()} is idempotent: closing twice returns the account once. That matters because the
 * scope closes it, and defensive code elsewhere may too.
 *
 * <p><strong>Deviation from ADR-UI-006, deliberate.</strong> The ADR sketches a {@code storageState()}
 * accessor on this interface. It is not here: the pool is JVM-wide and outlives any single run, whereas
 * the artefact directory the state file lives under is per-run configuration, so a path resolved by the
 * pool would freeze whichever run happened to create it. The ADR's actual requirement — that the state
 * belongs to an account rather than to a suite — is met by {@link #accountId()} plus
 * {@code StorageStateStore}, which derives the path from the account id and the current run's settings.
 */
public interface LeasedAccount extends AutoCloseable {

    /**
     * The stable identity of the leased account — what the browser storage state is keyed by.
     *
     * @return the account id
     */
    String accountId();

    /**
     * The role the account plays.
     *
     * @return the role
     */
    String role();

    /**
     * Reference resolving to the login. A reference, never the login itself.
     *
     * @return the username reference
     */
    String usernameRef();

    /**
     * Reference resolving to the password. A reference, never the password itself.
     *
     * @return the password reference
     */
    String passwordRef();

    /**
     * How long the run waited before this account became free. Recorded in the step diagnostics so that a
     * pool becoming the bottleneck of a parallel suite is visible in reports rather than inferred from
     * wall-clock time.
     *
     * @return the wait before the lease was granted
     */
    Duration waited();

    /**
     * Returns the account to the pool. Idempotent.
     */
    @Override
    void close();
}
