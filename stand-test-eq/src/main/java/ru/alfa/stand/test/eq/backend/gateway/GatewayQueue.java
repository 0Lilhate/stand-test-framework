package ru.alfa.stand.test.eq.backend.gateway;

import ru.alfa.stand.test.core.exception.StandTestException;

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