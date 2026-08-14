package ru.alfa.stand.test.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/**
 * Captures everything the module logs while it is open.
 *
 * <p>It exists for one assertion that nothing else can make: that a credential does not reach the log.
 * Exception messages and step diagnostics are objects a test can inspect; a log line is not — it goes to a
 * console and a CI artefact, and a debug statement added a year from now would publish a password with every
 * existing test still green. This closes that gap by reading what was actually written.
 *
 * <p>It attaches at {@code TRACE} to the module's root logger, so a statement at any level is seen — and it
 * keeps only the events written by the thread that opened it. The logger is process-wide and the suite runs
 * classes concurrently, so an unfiltered capture would collect other classes' lines: the "did anything get
 * logged at all?" guard would then be satisfied by somebody else's run, and a leak introduced elsewhere
 * would fail here, pointing at the wrong test. A scenario run is single-threaded by SDK invariant, so the
 * thread name is an exact filter.
 */
final class LogCapture implements AutoCloseable {

    private final Logger logger;

    private final Level previousLevel;

    private final CollectingAppender appender;

    private LogCapture(Logger logger) {
        this.logger = logger;
        this.previousLevel = logger.getLevel();
        this.appender = new CollectingAppender(Thread.currentThread().getName());
        this.appender.start();
        logger.setLevel(Level.TRACE);
        logger.addAppender(this.appender);
    }

    static LogCapture attached() {
        return new LogCapture((Logger) LoggerFactory.getLogger("ru.alfa.stand.test.ui"));
    }

    String text() {
        return String.join("\n", this.appender.lines);
    }

    @Override
    public void close() {
        this.logger.detachAppender(this.appender);
        this.logger.setLevel(this.previousLevel);
        this.appender.stop();
    }

    private static final class CollectingAppender extends AppenderBase<ILoggingEvent> {

        private final List<String> lines = new CopyOnWriteArrayList<>();

        private final String thread;

        private CollectingAppender(String thread) {
            this.thread = thread;
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (!this.thread.equals(event.getThreadName())) {
                return;
            }
            this.lines.add(event.getFormattedMessage());
            for (Object argument : (event.getArgumentArray() == null) ? new Object[0] : event.getArgumentArray()) {
                this.lines.add(String.valueOf(argument));
            }
        }
    }
}
