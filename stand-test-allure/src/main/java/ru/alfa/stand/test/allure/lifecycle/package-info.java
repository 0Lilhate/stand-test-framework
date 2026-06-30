/**
 * The seam over the Allure lifecycle.
 *
 * <p>{@link ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade} is the thin interface the rest of
 * the adapter depends on, expressed in transport-neutral types
 * ({@link ru.alfa.stand.test.allure.lifecycle.AllureStatus},
 * {@link ru.alfa.stand.test.allure.lifecycle.AllureLabel}, parameter maps, textual content) so the
 * mapping is unit-testable without an Allure runtime.
 * {@link ru.alfa.stand.test.allure.lifecycle.DefaultAllureLifecycleFacade} is the production
 * implementation that talks to {@code io.qameta.allure.*}.
 */
package ru.alfa.stand.test.allure.lifecycle;
