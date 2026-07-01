package ru.alfa.stand.test.junit;

import java.util.concurrent.atomic.AtomicInteger;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;

/**
 * Test {@link ReportingEventPublisher} discovered via {@link java.util.ServiceLoader} (registered in
 * {@code META-INF/services}). It counts the lifecycle events the runner publishes, so a test can assert
 * that the extension wired a discovered publisher into the run instead of the NoOp default. Must be
 * public with a public no-arg constructor for the service loader; the counter is static so the test can
 * read it after an isolated engine run.
 *
 * <p>The static counter is shared JVM-wide and accumulates events from every {@code @StandTest} fixture
 * that runs in this module, so the asserting test must {@link #reset()} immediately before its engine run
 * and read immediately after. That window is safe only while the suite runs sequentially — it relies on
 * the absence of a {@code junit-platform.properties} enabling parallel test execution (the same
 * assumption the existing static-state fixtures in {@code StandTestExtensionTest} already make).
 */
public final class CountingReportingEventPublisher implements ReportingEventPublisher {

    private static final AtomicInteger PUBLISHED = new AtomicInteger();

    @Override
    public void publish(ScenarioEvent event) {
        PUBLISHED.incrementAndGet();
    }

    @Override
    public void publish(StepEvent event) {
        PUBLISHED.incrementAndGet();
    }

    static int published() {
        return PUBLISHED.get();
    }

    static void reset() {
        PUBLISHED.set(0);
    }
}
