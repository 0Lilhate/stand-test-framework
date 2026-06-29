package ru.alfa.stand.test.await;

/**
 * Deterministic {@link TimeSource} test double: time only advances when {@link #sleep(long)} is
 * called, so timeout behaviour can be asserted exactly without any real blocking.
 */
final class FakeTimeSource implements TimeSource {

    private final boolean interruptOnSleep;
    private long nowNanos;
    private long sleepCalls;
    private long totalSleptNanos;

    FakeTimeSource() {
        this(false);
    }

    FakeTimeSource(boolean interruptOnSleep) {
        this.interruptOnSleep = interruptOnSleep;
    }

    @Override
    public long nanoTime() {
        return nowNanos;
    }

    @Override
    public void sleep(long nanos) throws InterruptedException {
        if (interruptOnSleep) {
            throw new InterruptedException("fake interrupt");
        }
        sleepCalls++;
        if (nanos > 0) {
            nowNanos += nanos;
            totalSleptNanos += nanos;
        }
    }

    long sleepCalls() {
        return sleepCalls;
    }

    long totalSleptNanos() {
        return totalSleptNanos;
    }
}
