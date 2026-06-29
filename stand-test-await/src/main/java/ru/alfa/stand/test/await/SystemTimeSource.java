package ru.alfa.stand.test.await;

/**
 * Default {@link TimeSource} backed by the JVM clock.
 *
 * <p>Singleton enum: {@link #nanoTime()} reads {@link System#nanoTime()} and {@link #sleep(long)}
 * blocks the calling thread via {@link Thread#sleep(long, int)}. This is the single place in the SDK
 * that performs a real sleep — it is the sanctioned poll-wait inside the await engine, not a fixed
 * test pause (which the SDK forbids, plan §2.5).
 */
enum SystemTimeSource implements TimeSource {

    INSTANCE;

    private static final long NANOS_PER_MILLI = 1_000_000L;

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    @Override
    public void sleep(long nanos) throws InterruptedException {
        if (nanos <= 0) {
            return;
        }
        long millis = nanos / NANOS_PER_MILLI;
        int remainder = (int) (nanos % NANOS_PER_MILLI);
        Thread.sleep(millis, remainder);
    }
}
