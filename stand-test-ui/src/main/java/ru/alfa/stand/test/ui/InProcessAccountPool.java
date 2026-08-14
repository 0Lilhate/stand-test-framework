package ru.alfa.stand.test.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The wave-1 {@link AccountPool}: a fixed roster of accounts shared by every run inside one JVM, handed
 * out one run at a time.
 *
 * <p>In-process is a deliberate choice, not a shortcut. It needs no service to build, deploy and operate,
 * and it has one property an external broker has to work for: a pool that dies with the JVM cannot leak a
 * lease. What it cannot do is coordinate several JVMs — which is precisely why {@link AccountPool} is an
 * interface.
 *
 * <p>The implementation is a monitor over a list of free accounts rather than a queue per role, because a
 * request may name a role or accept any account, and a per-role queue makes the second case a
 * take-and-put-back dance that can hand the same account to two waiters. Waiting is bounded by the
 * caller's timeout and re-checked on every wakeup, so neither a spurious wakeup nor a notification meant
 * for another role can turn a bounded wait into an unbounded one.
 *
 * <p>The pool is fair enough, not strictly FIFO: when several runs wait for the same role, the one that
 * next acquires the monitor wins. The guarantee this class makes is the one the SDK needs — every wait
 * ends, and it ends within the timeout the caller declared.
 */
public final class InProcessAccountPool implements AccountPool {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessAccountPool.class);

    private final List<UiAccount> roster;

    private final Object lock = new Object();

    private final List<UiAccount> free;

    /**
     * Creates a pool over the given accounts.
     *
     * @param accounts the roster (never empty; account ids must be unique)
     */
    public InProcessAccountPool(Collection<UiAccount> accounts) {
        Objects.requireNonNull(accounts, "accounts must not be null");
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("an account pool must hold at least one account");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (UiAccount account : accounts) {
            if (!ids.add(account.accountId().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("account id '" + account.accountId()
                        + "' appears more than once in the pool (ids are compared ignoring case) — ids key the browser session state, so they must be unique");
            }
        }
        this.roster = List.copyOf(accounts);
        this.free = new ArrayList<>(this.roster);
    }

    /**
     * How many accounts of the given role the pool holds in total (leased or not) — the ceiling on how
     * many runs can hold that role at once.
     *
     * @param role the role, or null for the whole roster
     * @return the number of accounts
     */
    public int size(String role) {
        return (int) this.roster.stream().filter(account -> matches(account, role)).count();
    }

    @Override
    public LeasedAccount lease(String application, String role, Duration timeout) {
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the account lease timeout must be strictly positive, but was " + timeout);
        }
        requireRoleRepresented(application, role);
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        synchronized (this.lock) {
            while (true) {
                UiAccount taken = takeFree(role);
                if (taken != null) {
                    Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
                    LOG.debug("Leased UI account '{}' (role '{}') of application '{}' after {}", taken.accountId(), taken.role(), application, waited);
                    return new Lease(taken, waited);
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw exhausted(application, role, timeout);
                }
                awaitRelease(remainingNanos);
            }
        }
    }

    /**
     * Fails immediately, without burning the timeout, when the pool holds no account of the requested role
     * at all. Waiting a minute for something that can never become free is not a bounded wait in any useful
     * sense — it is a slow way of reporting a configuration error.
     */
    private void requireRoleRepresented(String application, String role) {
        if (role == null || size(role) > 0) {
            return;
        }
        throw new StandTestException("The account pool of UI application '" + application + "' holds no account of role '" + role
                + "' — it holds " + this.roster.size() + " account(s) covering roles " + roles()
                + "; add one to the roster the credentials-pool-ref points at, or request a role that exists");
    }

    private StandTestException exhausted(String application, String role, Duration timeout) {
        String what = (role == null) ? "any role" : "role '" + role + "'";
        return new StandTestException("No test account of " + what + " became free for UI application '" + application + "' within " + timeout
                + " — the pool holds " + size(role) + " account(s) of " + what + " and all of them are leased by other runs."
                + " Either enlarge the roster the credentials-pool-ref points at, lower the parallelism of the suite, or raise the step's accountTimeout;"
                + " the wait is deliberately bounded, because a suite that hangs reports nothing.");
    }

    private void awaitRelease(long remainingNanos) {
        long millis = Math.max(1L, remainingNanos / 1_000_000L);
        try {
            this.lock.wait(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StandTestException("Interrupted while waiting for a free UI test account", interrupted);
        }
    }

    private UiAccount takeFree(String role) {
        for (int index = 0; index < this.free.size(); index++) {
            if (matches(this.free.get(index), role)) {
                return this.free.remove(index);
            }
        }
        return null;
    }

    private void release(UiAccount account) {
        synchronized (this.lock) {
            this.free.add(account);
            this.lock.notifyAll();
        }
        LOG.debug("Returned UI account '{}' to the pool", account.accountId());
    }

    private Set<String> roles() {
        return this.roster.stream().map(UiAccount::role).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean matches(UiAccount account, String role) {
        return role == null || role.equals(account.role());
    }

    /**
     * The lease handed to one run. It is closed by the run's resource scope; closing twice returns the
     * account once, so a scope close and any defensive close elsewhere cannot double-free it into the pool.
     */
    private final class Lease implements LeasedAccount {

        private final UiAccount account;

        private final Duration waited;

        private final AtomicBoolean returned = new AtomicBoolean();

        private Lease(UiAccount account, Duration waited) {
            this.account = account;
            this.waited = waited;
        }

        @Override
        public String accountId() {
            return this.account.accountId();
        }

        @Override
        public String role() {
            return this.account.role();
        }

        @Override
        public String usernameRef() {
            return this.account.usernameRef();
        }

        @Override
        public String passwordRef() {
            return this.account.passwordRef();
        }

        @Override
        public Duration waited() {
            return this.waited;
        }

        @Override
        public void close() {
            if (this.returned.compareAndSet(false, true)) {
                release(this.account);
            }
        }
    }
}
