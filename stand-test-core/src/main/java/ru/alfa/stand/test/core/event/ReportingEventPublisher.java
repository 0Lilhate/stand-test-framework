package ru.alfa.stand.test.core.event;

/**
 * Sink the runner publishes reporting events to.
 *
 * <p>Core depends on no reporting library: an Allure adapter (a later iteration) implements this
 * contract and consumes the events. The default implementation is
 * {@link NoOpReportingEventPublisher}.
 *
 * <p><strong>Thread-safety (plan §15).</strong> A single publisher instance may be shared by one
 * {@code ScenarioRunner} across concurrent scenario runs (the sanctioned "one cached client per JUnit
 * engine" wiring), so {@code publish(...)} can be called simultaneously from several run threads.
 * Implementations must therefore be thread-safe: either hold no mutable state, or confine per-run state to
 * the calling thread (as the Allure adapter does with a thread-local step stack). The runner treats
 * publishing as a best-effort side-channel and swallows any thrown exception, but it does not serialise the
 * calls.
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
