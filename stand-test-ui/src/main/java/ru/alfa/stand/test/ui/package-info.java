/**
 * The UI adapter: browser steps as ordinary steps of the SDK's one scenario model.
 *
 * <p>A test author writes {@link ru.alfa.stand.test.ui.UiStep} exactly as they write {@code RestStep} or
 * {@code DbStep}; the step is validated by the same pre-flight guardrails, executed by the same runner,
 * waits through the same await engine and shares the same per-run variable store, so a value read off a
 * screen is visible to a later Kafka or DB step as {@code ${variable}} with no new machinery.
 *
 * <p>The application is always named by a registry alias and the path is always relative: there is no
 * parameter anywhere in this package that accepts an absolute URL.
 */
package ru.alfa.stand.test.ui;
