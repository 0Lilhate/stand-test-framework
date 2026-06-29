package ru.alfa.stand.test.await;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for a single await: what is being waited for and the timing/behaviour of
 * the polling loop.
 *
 * <p>{@code description} is the human-readable name of the await (for example
 * {@code "kafka.expect response-topic"}); it is carried into {@link TimeoutDiagnostics} and timeout
 * messages so a failure is self-explanatory. {@code timeout} and {@code pollInterval} must be strictly
 * positive; {@code pollDelay} is an optional non-negative initial wait performed before the first
 * probe. When {@code ignoreExceptions} is {@code true} (the default) a probe/predicate that throws is
 * treated as "not satisfied yet" and polling continues — the last error is captured in diagnostics;
 * when {@code false} such an exception aborts the await and propagates to the caller.
 *
 * @param description the human-readable name of what is being awaited
 * @param timeout the maximum total time to wait (strictly positive)
 * @param pollInterval the wait between consecutive probes (strictly positive)
 * @param pollDelay the initial wait before the first probe (non-negative)
 * @param ignoreExceptions whether a throwing probe/predicate is treated as "not yet satisfied"
 */
public record AwaitPolicy(
        String description,
        Duration timeout,
        Duration pollInterval,
        Duration pollDelay,
        boolean ignoreExceptions) {

    /** Poll interval used when none is specified. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);

    public AwaitPolicy {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("await description must not be blank");
        }
        requirePositive(timeout, "timeout");
        requirePositive(pollInterval, "pollInterval");
        Objects.requireNonNull(pollDelay, "pollDelay must not be null");
        if (pollDelay.isNegative()) {
            throw new IllegalArgumentException("pollDelay must not be negative");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be strictly positive");
        }
    }

    /**
     * Creates a policy with the given timeout and the {@link #DEFAULT_POLL_INTERVAL default poll
     * interval}, no initial delay and exception-ignoring polling.
     *
     * @param description the human-readable name of what is being awaited
     * @param timeout the maximum total time to wait
     * @return a new policy
     */
    public static AwaitPolicy of(String description, Duration timeout) {
        return new AwaitPolicy(description, timeout, DEFAULT_POLL_INTERVAL, Duration.ZERO, true);
    }

    /**
     * Creates a policy with the given timeout expressed in seconds and default polling.
     *
     * @param description the human-readable name of what is being awaited
     * @param timeoutSeconds the maximum total time to wait, in seconds
     * @return a new policy
     */
    public static AwaitPolicy ofSeconds(String description, long timeoutSeconds) {
        return of(description, Duration.ofSeconds(timeoutSeconds));
    }

    /**
     * Starts a builder for an await with the given description.
     *
     * @param description the human-readable name of what is being awaited
     * @return a new builder
     */
    public static Builder builder(String description) {
        return new Builder(description);
    }

    /**
     * Mutable builder that assembles an immutable {@link AwaitPolicy}.
     */
    public static final class Builder {

        private final String description;
        private Duration timeout;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;
        private Duration pollDelay = Duration.ZERO;
        private boolean ignoreExceptions = true;

        private Builder(String description) {
            this.description = description;
        }

        /**
         * Sets the maximum total time to wait.
         *
         * @param timeout the timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the maximum total time to wait, in seconds.
         *
         * @param timeoutSeconds the timeout in seconds
         * @return this builder
         */
        public Builder timeoutSeconds(long timeoutSeconds) {
            this.timeout = Duration.ofSeconds(timeoutSeconds);
            return this;
        }

        /**
         * Sets the wait between consecutive probes.
         *
         * @param pollInterval the poll interval
         * @return this builder
         */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        /**
         * Sets the initial wait before the first probe.
         *
         * @param pollDelay the initial delay
         * @return this builder
         */
        public Builder pollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
            return this;
        }

        /**
         * Sets whether a throwing probe/predicate is treated as "not yet satisfied" (true) or aborts
         * the await (false).
         *
         * @param ignoreExceptions the exception-handling flag
         * @return this builder
         */
        public Builder ignoreExceptions(boolean ignoreExceptions) {
            this.ignoreExceptions = ignoreExceptions;
            return this;
        }

        /**
         * Builds the immutable policy.
         *
         * @return the assembled policy
         */
        public AwaitPolicy build() {
            return new AwaitPolicy(description, timeout, pollInterval, pollDelay, ignoreExceptions);
        }
    }
}
