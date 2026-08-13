package ru.alfa.stand.test.ui;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * The registry of {@link InProcessAccountPool}s, one per (environment, application).
 *
 * <p>Its default instance is JVM-wide, and that is what the feature is. An account pool exists to make
 * parallel runs exclusive of one another; a pool held per run, per executor instance or per test class
 * would hand the same account to every run and quietly deliver the opposite of what it promises. The
 * executor itself is created afresh by every {@code ServiceLoader} lookup, so an instance field on it would
 * have been exactly that mistake.
 *
 * <p>The class is nonetheless <em>instantiable</em>, and the executor takes one, because a JVM-wide
 * singleton with a {@code reset()} for tests is a worse shape than it looks: a reset clears every pool,
 * including one another test is holding a lease from, so the very suite that proves parallel safety would
 * be the suite most likely to interfere with itself. A test constructs its own registry; production uses
 * {@link #shared()}.
 *
 * <p>The key is (environment, application) and the roster is <em>checked</em> against it rather than folded
 * into it. Folding it in looks safer and is not: two rosters for one application would produce two pools,
 * each leasing "exclusively" while handing the same account id to two runs at once. A second roster is
 * therefore refused with a message, which also catches what the folded key was meant to catch — a roster
 * variable that changed under a running JVM.
 *
 * <p>Pools are never evicted. An in-process pool that outlives the runs using it costs a few objects, while
 * one evicted while a run holds a lease would break exclusivity. The map is bounded by the number of
 * distinct (environment, application) pairs a JVM meets, which is a handful.
 */
final class UiAccountPools {

    private static final UiAccountPools SHARED = new UiAccountPools();

    private final Map<String, Registration> pools = new ConcurrentHashMap<>();

    /**
     * The JVM-wide registry every real run draws from — the one that makes exclusivity mean anything.
     *
     * @return the shared registry
     */
    static UiAccountPools shared() {
        return SHARED;
    }

    /**
     * Returns the pool for an application, creating it on first use.
     *
     * @param environment the scenario environment
     * @param application the UI application alias
     * @param roster the accounts the application's credentials-pool variable declares
     * @return the pool, shared by every run of this application in this environment
     * @throws StandTestException if a different roster was already registered for the same application
     */
    AccountPool forApplication(String environment, String application, List<UiAccount> roster) {
        Registration registration = this.pools.computeIfAbsent(
                environment + '/' + application,
                ignored -> new Registration(new InProcessAccountPool(roster), fingerprint(roster)));
        String asked = fingerprint(roster);
        if (!registration.fingerprint.equals(asked)) {
            throw new StandTestException("The account roster of UI application '" + application + "' in environment '" + environment
                    + "' changed within one JVM: this process is already leasing from " + registration.fingerprint + ", and was now asked for " + asked
                    + ". Two rosters for one application would each lease 'exclusively' while handing the same account to two runs;"
                    + " use one roster per application per environment, or a distinct application alias for a distinct set of accounts.");
        }
        return registration.pool;
    }

    /**
     * The identity of a roster: account ids and roles, in order. Never a credential — the roster holds none —
     * so this is safe to hold, to compare and to print in the message above.
     */
    private static String fingerprint(List<UiAccount> roster) {
        StringBuilder identity = new StringBuilder("[");
        for (UiAccount account : roster) {
            if (identity.length() > 1) {
                identity.append(", ");
            }
            identity.append(account.accountId()).append(':').append(account.role());
        }
        return identity.append(']').toString();
    }

    private record Registration(AccountPool pool, String fingerprint) {
    }
}
