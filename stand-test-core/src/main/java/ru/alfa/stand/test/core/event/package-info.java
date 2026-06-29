/**
 * Reporting event contracts.
 *
 * <p>{@link ru.alfa.stand.test.core.event.StepEvent} and
 * {@link ru.alfa.stand.test.core.event.ScenarioEvent} carry the run metadata
 * (scenarioId/testRunId/correlationId), the step/phase and a timestamp.
 * {@link ru.alfa.stand.test.core.event.ReportingEventPublisher} is the sink the runner publishes to;
 * the Allure adapter (a later iteration) consumes these events so that core never depends on Allure.
 * {@link ru.alfa.stand.test.core.event.NoOpReportingEventPublisher} is the default no-op sink.
 */
package ru.alfa.stand.test.core.event;
