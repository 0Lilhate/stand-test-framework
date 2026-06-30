/**
 * Reporting event contracts.
 *
 * <p>{@link ru.alfa.stand.test.core.event.StepEvent} and
 * {@link ru.alfa.stand.test.core.event.ScenarioEvent} carry the run metadata
 * (scenarioId/testRunId/correlationId, plus environment/tags on the scenario event), the step/phase, a
 * timestamp and — on a finished step — diagnostics and transport-agnostic
 * {@link ru.alfa.stand.test.core.event.Attachment}s (plan §8.9).
 * {@link ru.alfa.stand.test.core.event.ReportingEventPublisher} is the sink the runner publishes to;
 * the Allure adapter (a later iteration) consumes these events so that core never depends on Allure.
 * {@link ru.alfa.stand.test.core.event.NoOpReportingEventPublisher} is the default no-op sink.
 */
package ru.alfa.stand.test.core.event;
