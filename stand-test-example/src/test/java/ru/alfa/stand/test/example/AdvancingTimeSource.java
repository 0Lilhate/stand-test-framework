package ru.alfa.stand.test.example;

import ru.alfa.stand.test.await.TimeSource;

/**
 * A deterministic {@link TimeSource} for the await example: {@link #sleep(long)} advances the monotonic
 * reading instead of blocking, so an await that "waits" several poll intervals completes instantly and
 * with a reproducible attempt count — the SDK-sanctioned way to test waiting without wall-clock time
 * (and the reason the examples never need {@code Thread.sleep}).
 */
final class AdvancingTimeSource implements TimeSource {

    private long nanos;

    @Override
    public long nanoTime() {
        return this.nanos;
    }

    @Override
    public void sleep(long requestedNanos) {
        if (requestedNanos > 0) {
            this.nanos += requestedNanos;
        }
    }
}
