package ru.alfa.stand.test.core.event;

/**
 * A {@link ReportingEventPublisher} that discards all events.
 *
 * <p>Used as the default sink when no reporting adapter is wired.
 */
public final class NoOpReportingEventPublisher implements ReportingEventPublisher {

    /** Shared, stateless instance. */
    public static final NoOpReportingEventPublisher INSTANCE = new NoOpReportingEventPublisher();

    private NoOpReportingEventPublisher() {
    }

    @Override
    public void publish(ScenarioEvent event) {
        // no-op
    }

    @Override
    public void publish(StepEvent event) {
        // no-op
    }
}
