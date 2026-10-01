package ru.alfa.stand.test.eq.backend.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * JVM-local serialization of gateway write chains: exactly one chain at a time per
 * {@code (base-url, unit)} pair.
 *
 * <p>The key is the pair <strong>after reference resolution</strong>, not the backend alias, so two
 * aliases pointing at one gateway cannot bypass the lock (BR-26). The guarantee holds inside one JVM
 * only: the pilot runs with {@code maxParallelForks = 1} and without concurrent CI jobs on the same
 * pair; a {@code Semaphore} is not a substitute for an external coordinator across JVMs.
 *
 * <p>The queue is instantiable (each test owns one) and {@link Shared#instance()} returns the JVM-wide
 * instance production uses — the {@code UiAccountPools} lesson: a singleton with a {@code reset()}
 * would make the test that proves parallel isolation the one most likely to break it.
 *
 * <p>Waiting for a slot is bounded by the caller-supplied {@code acquireTimeout}; on expiry the
 * failure names the queue length and the wait time. This class never runs the chain itself, so a
 * caller can release the slot before a visibility probe (which must not hold the queue, BR-26/3.5.3).
 */
public final class GatewayQueue {

    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> waiters = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> registryLocks = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    /** Creates an empty queue with the system monotonic clock. */
    public GatewayQueue() {
        this(System::nanoTime);
    }

    /** Creates an empty queue with an injectable clock, so a wait can be tested without sleeping. */
    public GatewayQueue(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    /**
     * Acquires the single slot for {@code (baseUrl, unit)} within the bound and returns a handle whose
     * {@link Lease#close()} releases it. The handle is idempotent, so a {@code finally} release is safe
     * even if the caller already closed it.
     *
     * @param baseUrl the resolved gateway base URL (must not be blank)
     * @param unit the resolved unit (must not be blank)
     * @param acquireTimeout the bound on waiting for the slot; must be positive
     * @return the held slot
     * @throws StandTestException when the slot could not be taken within the bound
     */
    public Lease acquire(String baseUrl, String unit, Duration acquireTimeout) {
        String key = key(baseUrl, unit);
        Semaphore semaphore = lock(key);
        AtomicInteger waiting = waiters.computeIfAbsent(key, ignored -> new AtomicInteger());
        waiting.incrementAndGet();
        long started = nanoTime.getAsLong();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StandTestException("Interrupted while waiting for the EQ gateway queue on '" + key + "'");
        } finally {
            waiting.decrementAndGet();
        }
        if (!acquired) {
            long waitedMillis = (nanoTime.getAsLong() - started) / 1_000_000L;
            throw new StandTestException("Timed out after " + waitedMillis + " ms waiting for the EQ gateway queue on '"
                    + key + "' (queue length " + waiting.get() + ")");
        }
        return new Lease(semaphore);
    }

    /** The current number of waiters for a pair, for diagnostics and tests. */
    public int queueLength(String baseUrl, String unit) {
        AtomicInteger waiting = waiters.get(key(baseUrl, unit));
        return waiting == null ? 0 : waiting.get();
    }

    private Semaphore lock(String key) {
        Semaphore existing = locks.get(key);
        if (existing != null) {
            return existing;
        }
        ReentrantLock registryLock = registryLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        registryLock.lock();
        try {
            return locks.computeIfAbsent(key, ignored -> new Semaphore(1, true));
        } finally {
            registryLock.unlock();
        }
    }

    private static String key(String baseUrl, String unit) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit must not be blank");
        }
        return baseUrl + '\u0000' + unit;
    }

    /** Returns an immutable snapshot of the number of live locks, for tests. */
    Map<String, Integer> lockCounts() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        locks.forEach((key, semaphore) -> snapshot.put(key, semaphore.availablePermits()));
        return Map.copyOf(snapshot);
    }

    /** Holds the JVM-wide queue; a separate holder keeps this class out of SpotBugs' singleton pattern. */
    public static final class Shared {

        private static final GatewayQueue INSTANCE = new GatewayQueue();

        private Shared() {
        }

        /** The JVM-wide queue production uses. */
        public static GatewayQueue instance() {
            return INSTANCE;
        }
    }

    /** A held slot that releases exactly once on {@link #close()}. */
    public static final class Lease implements AutoCloseable {

        private final Semaphore semaphore;
        private boolean released;

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        @Override
        public synchronized void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}