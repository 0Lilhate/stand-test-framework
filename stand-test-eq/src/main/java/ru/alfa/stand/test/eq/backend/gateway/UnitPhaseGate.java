package ru.alfa.stand.test.eq.backend.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Reads and caches the AS/400 unit phase, and refuses a seed when the unit is not in a working phase
 * (BR-38, BR-39).
 *
 * <p>The cache has a TTL ({@code unit-phase.cache-ttl}, five minutes by default) rather than the JVM's
 * lifetime: if the unit enters end-of-day during a long run, a permanent cache would hide it and produce a
 * stream of obscure failures instead of one clear refusal. A phase is cached per {@code (system, unit)}.
 *
 * <p>The check runs on {@code prepare}, so one read serves every scenario in the TTL window and no gateway
 * call is made when the unit is not working. The gate is an instantiable object; production takes
 * {@link Shared#instance()}.
 */
public final class UnitPhaseGate {

    private final UnitPhaseReader reader;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public UnitPhaseGate(UnitPhaseReader reader, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Returns the current phase, reading through the AS/400 reader at most once per TTL window.
     *
     * @param system the resolved system reference
     * @param username the resolved user name (never logged)
     * @param password the resolved password (never logged)
     * @param unit the resolved unit, part of the cache key
     * @param cacheTtl how long a read stays valid
     * @return the phase value
     */
    public String currentPhase(String system, String username, String password, String unit, Duration cacheTtl) {
        String key = system + '\u0000' + unit;
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && !cached.expired(now, cacheTtl)) {
            return cached.phase();
        }
        String phase = read(system, unit, username, password);
        cache.put(key, new Cached(phase, now));
        return phase;
    }

    /**
     * Fails with an infrastructure error unless the unit phase is one of {@code allowed}. The error text
     * names the unit and the observed phase but never a credential.
     *
     * @param unit the unit being checked
     * @param phase the observed phase
     * @param allowed the phases in which seeding may proceed
     * @return the observed phase when it is allowed
     */
    public String requireWorkingPhase(String unit, String phase, List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            throw new StandTestException("EQ unit-phase.allowed is empty; configure at least one working phase");
        }
        if (!allowed.contains(phase)) {
            throw new StandTestException("EQ unit <" + unit + "> is in phase <" + phase
                    + ">, which is not among the allowed working phases " + allowed);
        }
        return phase;
    }

    /** Drops the cached phase for a {@code (system, unit)} pair, so the next read is fresh. */
    public void invalidate(String system, String unit) {
        cache.remove(system + '\u0000' + unit);
    }

    private String read(String system, String unit, String username, String password) {
        try {
            String phase = reader.readPhase(system, unit, username, password);
            if (phase == null || phase.isBlank()) {
                throw new StandTestException("EQ unit-phase read returned no phase");
            }
            return phase;
        } catch (StandTestException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new StandTestException("EQ unit-phase read failed: " + failure.getClass().getSimpleName(), failure);
        }
    }

    /** Holds the JVM-wide gate; a separate holder keeps this class out of SpotBugs' singleton pattern. */
    public static final class Shared {

        private static final UnitPhaseGate INSTANCE = new UnitPhaseGate(new Jt400UnitPhaseReader(), Clock.systemUTC());

        private Shared() {
        }

        /** The JVM-wide gate production uses. */
        public static UnitPhaseGate instance() {
            return INSTANCE;
        }
    }

    private record Cached(String phase, Instant readAt) {
        boolean expired(Instant now, Duration ttl) {
            return readAt.plus(ttl).isBefore(now);
        }
    }
}