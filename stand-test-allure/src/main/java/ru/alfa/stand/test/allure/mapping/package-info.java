/**
 * Mapping of core reporting events to Allure constructs.
 *
 * <p>{@link ru.alfa.stand.test.allure.mapping.AllureStepMapper} maps a step outcome (status, name);
 * {@link ru.alfa.stand.test.allure.mapping.AllureMetadataMapper} maps run metadata
 * (scenarioId/testRunId/correlationId/environment/tags, step id/type) to Allure labels and parameters.
 * Both are pure functions over the core event model with no Allure-runtime dependency.
 */
package ru.alfa.stand.test.allure.mapping;
