package ru.alfa.stand.test.example;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.allure.lifecycle.AllureLabel;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;

/**
 * Records the Allure lifecycle calls the {@code AllureReportingEventPublisher} makes, so the reporting
 * example can assert what would be rendered without a real Allure runtime (the facade is the public seam
 * the publisher accepts via its constructor).
 */
final class CapturingAllureLifecycleFacade implements AllureLifecycleFacade {

    private final List<String> startedStepNames = new ArrayList<>();
    private final List<AllureStatus> stepStatuses = new ArrayList<>();
    private final List<AllureLabel> testLabels = new ArrayList<>();
    private final Map<String, String> testParameters = new LinkedHashMap<>();
    private final List<Map<String, String>> stepParameterSets = new ArrayList<>();
    private final List<String> attachmentNames = new ArrayList<>();
    private int stoppedSteps;

    @Override
    public void startStep(String uuid, String name) {
        startedStepNames.add(name);
    }

    @Override
    public void updateStep(String uuid, AllureStatus status, String statusMessage, String statusTrace, Map<String, String> parameters) {
        stepStatuses.add(status);
        stepParameterSets.add(new LinkedHashMap<>(parameters));
    }

    @Override
    public void addAttachment(String name, String type, String fileExtension, String content) {
        attachmentNames.add(name);
    }

    @Override
    public void addAttachment(String name, String type, String fileExtension, Path file) {
        attachmentNames.add(name);
    }

    @Override
    public void stopStep(String uuid) {
        stoppedSteps++;
    }

    @Override
    public void updateTestCase(List<AllureLabel> labels, Map<String, String> parameters) {
        testLabels.addAll(labels);
        testParameters.putAll(parameters);
    }

    List<String> startedStepNames() {
        return startedStepNames;
    }

    List<AllureStatus> stepStatuses() {
        return stepStatuses;
    }

    List<AllureLabel> testLabels() {
        return testLabels;
    }

    Map<String, String> testParameters() {
        return testParameters;
    }

    List<Map<String, String>> stepParameterSets() {
        return stepParameterSets;
    }

    List<String> attachmentNames() {
        return attachmentNames;
    }

    int stoppedSteps() {
        return stoppedSteps;
    }
}
