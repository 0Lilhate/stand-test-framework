package ru.alfa.stand.test.allure.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.allure.lifecycle.AllureLabel;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;

/**
 * Maps core run metadata to Allure labels and parameters.
 *
 * <p>Scenario level: the free-form tags become {@code tag} labels (Allure renders these natively), while
 * the scenarioId/testRunId/correlationId/environment become test-case parameters (always visible in the
 * report). Step level: the same identity (scenarioId/testRunId/correlationId) plus the step id and type
 * become step parameters, so a failing step in the report still answers "which run / which correlation".
 * All parameter values pass through the {@link SecretMasker} (identity keys are not sensitive, so this is
 * a no-op for them, but it keeps a single, consistent masking path).
 */
public final class AllureMetadataMapper {

    private final SecretMasker secretMasker;

    /**
     * Creates a metadata mapper that masks parameter values with the given masker.
     *
     * @param secretMasker the masker applied to parameter values
     */
    public AllureMetadataMapper(SecretMasker secretMasker) {
        this.secretMasker = Objects.requireNonNull(secretMasker, "secretMasker must not be null");
    }

    /**
     * Builds the test-case labels for a scenario event: one {@code tag} label per scenario tag.
     *
     * @param event the scenario event
     * @return the labels to apply to the Allure test case
     */
    public List<AllureLabel> scenarioLabels(ScenarioEvent event) {
        List<AllureLabel> labels = new ArrayList<>();
        for (String tag : event.tags()) {
            labels.add(new AllureLabel("tag", tag));
        }
        return labels;
    }

    /**
     * Builds the test-case parameters for a scenario event: scenarioId, testRunId, correlationId and
     * environment.
     *
     * @param event the scenario event
     * @return the ordered, masked parameter map
     */
    public Map<String, String> scenarioParameters(ScenarioEvent event) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("scenarioId", event.scenarioId().value());
        parameters.put("testRunId", event.testRunId().value());
        parameters.put("correlationId", event.correlationId().value());
        parameters.put("environment", event.environment());
        return secretMasker.mask(parameters);
    }

    /**
     * Builds the step parameters for a step event: scenarioId, testRunId, correlationId, step id and
     * step type.
     *
     * @param event the step event
     * @return the ordered, masked parameter map
     */
    public Map<String, String> stepParameters(StepEvent event) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("scenarioId", event.scenarioId().value());
        parameters.put("testRunId", event.testRunId().value());
        parameters.put("correlationId", event.correlationId().value());
        parameters.put("stepId", event.stepId());
        parameters.put("stepType", event.stepType());
        return secretMasker.mask(parameters);
    }
}
