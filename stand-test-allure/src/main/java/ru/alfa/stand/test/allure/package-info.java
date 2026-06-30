/**
 * Stand test SDK — Allure reporting adapter.
 *
 * <p>Turns the SDK's generic reporting events ({@link ru.alfa.stand.test.core.event.ScenarioEvent} /
 * {@link ru.alfa.stand.test.core.event.StepEvent} with their
 * {@link ru.alfa.stand.test.core.event.Attachment}s) into Allure steps, labels, parameters and
 * attachments. {@link ru.alfa.stand.test.allure.AllureReportingEventPublisher} implements the core
 * {@link ru.alfa.stand.test.core.event.ReportingEventPublisher} SPI; the dependency edge is one-way
 * ({@code allure → core}) so core never depends on Allure.
 *
 * <p>The adapter is a pure consumer: it executes no scenario and contains no REST/Kafka/DB/gRPC logic.
 * Sub-packages: {@link ru.alfa.stand.test.allure.lifecycle} (the testable seam over the Allure
 * lifecycle), {@link ru.alfa.stand.test.allure.mapping} (events → steps/labels/parameters),
 * {@link ru.alfa.stand.test.allure.attachment} (generic attachments) and
 * {@link ru.alfa.stand.test.allure.masking} (secret masking at the sink).
 */
package ru.alfa.stand.test.allure;
