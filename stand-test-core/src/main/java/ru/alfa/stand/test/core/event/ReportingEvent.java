package ru.alfa.stand.test.core.event;

/**
 * Common marker for reporting events.
 *
 * <p>Sealed over {@link ScenarioEvent} and {@link StepEvent} so that a reporting consumer (for
 * example the Allure adapter) can buffer, queue or pattern-match the full set of event types.
 */
public sealed interface ReportingEvent permits ScenarioEvent, StepEvent {
}
