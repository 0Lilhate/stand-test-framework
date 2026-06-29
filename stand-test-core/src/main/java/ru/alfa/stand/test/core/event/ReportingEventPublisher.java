package ru.alfa.stand.test.core.event;

/**
 * Sink the runner publishes reporting events to.
 *
 * <p>Core depends on no reporting library: an Allure adapter (a later iteration) implements this
 * contract and consumes the events. The default implementation is
 * {@link NoOpReportingEventPublisher}.
 */
public interface ReportingEventPublisher {

    /**
     * Publishes a scenario lifecycle event.
     *
     * @param event the scenario event
     */
    void publish(ScenarioEvent event);

    /**
     * Publishes a step lifecycle event.
     *
     * @param event the step event
     */
    void publish(StepEvent event);
}
