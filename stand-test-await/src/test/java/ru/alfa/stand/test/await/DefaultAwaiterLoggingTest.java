package ru.alfa.stand.test.await;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DefaultAwaiterLoggingTest {

    private final FakeTimeSource time = new FakeTimeSource();
    private final DefaultAwaiter awaiter = new DefaultAwaiter(time);

    @Test
    @DisplayName("DEBUG tracing names the await and its attempt count while leaking neither the polled value nor error messages")
    void debugTracing_emitsMetadataOnly() {
        Logger awaiterLogger = (Logger) LoggerFactory.getLogger(DefaultAwaiter.class);
        Level originalLevel = awaiterLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        awaiterLogger.setLevel(Level.DEBUG);
        awaiterLogger.addAppender(appender);
        try {
            AwaitPolicy satisfying = AwaitPolicy.builder("wait-for-secret")
                    .timeout(Duration.ofMillis(1000))
                    .pollInterval(Duration.ofMillis(200))
                    .build();
            AtomicInteger counter = new AtomicInteger();
            AwaitResult<String> resolved = awaiter.await(
                    satisfying,
                    () -> counter.incrementAndGet() >= 3 ? "TOP-SECRET-BODY" : "still-pending",
                    "TOP-SECRET-BODY"::equals);
            assertThat(resolved.satisfied()).isTrue();
            assertThat(resolved.attempts()).isEqualTo(3);

            AwaitPolicy timing = AwaitPolicy.builder("wait-for-missing")
                    .timeout(Duration.ofMillis(600))
                    .pollInterval(Duration.ofMillis(200))
                    .build();
            AwaitResult<String> timedOut = awaiter.await(timing, () -> {
                throw new IllegalStateException("SENSITIVE-ERROR-DETAIL");
            }, value -> true);
            assertThat(timedOut.satisfied()).isFalse();

            // A DEBUG line names the await and carries the per-attempt counter.
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage())
                        .contains("wait-for-secret")
                        .contains("attempt");
            });
            // The resolved line reports the satisfying attempt count.
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("wait-for-secret").contains("3"));
            // The timeout line reports metadata (attempts/elapsed) and only the error CLASS name.
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage())
                            .contains("wait-for-missing")
                            .contains("IllegalStateException"));
            // Safety: no captured line leaks the polled value or the probe's error message.
            assertThat(appender.list).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("TOP-SECRET-BODY"));
            assertThat(appender.list).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("still-pending"));
            assertThat(appender.list).noneSatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("SENSITIVE-ERROR-DETAIL"));
        } finally {
            awaiterLogger.detachAppender(appender);
            awaiterLogger.setLevel(originalLevel);
        }
    }
}
