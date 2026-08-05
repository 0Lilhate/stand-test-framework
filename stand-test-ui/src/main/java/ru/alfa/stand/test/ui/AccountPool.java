package ru.alfa.stand.test.ui;

import java.time.Duration;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The source of technical accounts a UI sign-in draws from, by role.
 *
 * <p>An interface rather than a class for two concrete reasons. It is the seam every test of the sign-in
 * machinery hangs on — a pool of two fake accounts proves exclusivity, exhaustion and return without a
 * browser or a stand. And it is the place an external account broker would plug in later without a single
 * scenario changing: wave 1 ships the in-process implementation because a broker is a service to build and
 * operate, not a library feature.
 *
 * <p>Implementations must be safe for concurrent use: parallel runs in one JVM lease from the same pool,
 * and that is exactly what makes them isolated from each other.
 */
public interface AccountPool {

    /**
     * Leases a free account of the given role, waiting no longer than the timeout.
     *
     * <p><strong>The wait is bounded and the bound is mandatory.</strong> An exhausted pool must fail, not
     * hang: an infinite wait is forbidden here for the same reason it is forbidden everywhere else in this
     * SDK — a suite that hangs reports nothing, while a suite that fails reports what was missing. The
     * failure is a {@link StandTestException} (recorded as broken, not failed): running out of test
     * accounts says nothing about the product under test.
     *
     * @param application the UI application alias the account belongs to, for the diagnostics
     * @param role the requested role, or null to accept an account of any role — which is only meaningful
     *     for an application that declares no roles
     * @param timeout the bound on waiting for a free account (must be strictly positive)
     * @return the lease, to be closed by the run's resource scope
     * @throws StandTestException if no account of that role becomes free within the timeout, or the role
     *     is not represented in the pool at all
     */
    LeasedAccount lease(String application, String role, Duration timeout);
}
