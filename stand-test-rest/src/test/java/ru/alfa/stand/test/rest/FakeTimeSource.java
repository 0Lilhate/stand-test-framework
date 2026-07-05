package ru.alfa.stand.test.rest;

import ru.alfa.stand.test.await.TimeSource;

/**
 * Deterministic {@link TimeSource} test double: time advances only when {@link #sleep(long)} is called,
 * so a {@code rest.expectEventually} timeout can be exercised without any real blocking.
 */
final class FakeTimeSource implements TimeSource {

    private long nowNanos;

    @Override
    public long nanoTime() {
        return this.nowNanos;
    }

    @Override
    public void sleep(long nanos) {
        if (nanos > 0) {
            this.nowNanos += nanos;
        }
    }
}
