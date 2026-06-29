package ru.alfa.stand.test.await;

/**
 * Abstraction over the passage of time used by the await engine.
 *
 * <p>It bundles the two time-related operations a polling loop needs — reading a monotonic clock and
 * waiting between polls — behind a single seam so that timeout behaviour can be unit-tested
 * deterministically. A test double can advance its own monotonic reading inside {@link #sleep(long)}
 * instead of really blocking, which keeps tests fast and free of wall-clock flakiness.
 *
 * <p>Production code uses {@link #system()}, which reads {@link System#nanoTime()} and blocks the
 * calling thread. A monotonic reading (rather than {@code Clock.instant()}) is used for measuring
 * elapsed time and deadlines so that wall-clock adjustments (NTP, manual changes) cannot corrupt a
 * timeout.
 */
public interface TimeSource {

    /**
     * Returns the current value of a monotonic time source, in nanoseconds.
     *
     * <p>Only differences between two readings are meaningful; the absolute value has no relation to
     * wall-clock time. Mirrors the contract of {@link System#nanoTime()}.
     *
     * @return the current monotonic reading in nanoseconds
     */
    long nanoTime();

    /**
     * Waits for at least the given number of nanoseconds.
     *
     * <p>A non-positive duration returns immediately. Implementations must advance {@link #nanoTime()}
     * by (at least) the requested amount so that elapsed-time accounting stays consistent.
     *
     * @param nanos the number of nanoseconds to wait
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    void sleep(long nanos) throws InterruptedException;

    /**
     * Returns the shared system time source backed by {@link System#nanoTime()} and
     * {@link Thread#sleep(long, int)}.
     *
     * @return the system time source
     */
    static TimeSource system() {
        return SystemTimeSource.INSTANCE;
    }
}
