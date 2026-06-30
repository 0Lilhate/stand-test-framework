package ru.alfa.stand.test.allure.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.AllureResultsWriter;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Parameter;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultAllureLifecycleFacadeTest {

    @Test
    @DisplayName("the no-arg constructor binds to the global lifecycle without throwing")
    void noArgConstructor_doesNotThrow() {
        assertThatCode(DefaultAllureLifecycleFacade::new).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("steps, status, parameters, labels and attachments are translated into the Allure model")
    void translatesIntoAllureModel() {
        CapturingWriter writer = new CapturingWriter();
        AllureLifecycle lifecycle = new AllureLifecycle(writer);
        DefaultAllureLifecycleFacade facade = new DefaultAllureLifecycleFacade(lifecycle);
        String testUuid = "test-1";
        lifecycle.scheduleTestCase(new TestResult().setUuid(testUuid));
        lifecycle.startTestCase(testUuid);

        facade.updateTestCase(List.of(new AllureLabel("tag", "smoke")), parameters("scenarioId", "flow"));
        facade.startStep("step-1", "rest.post s1");
        facade.updateStep("step-1", AllureStatus.FAILED, "boom", null, parameters("k", "v"));
        facade.addAttachment("diagnostics", "text/plain", "txt", "k=v");
        facade.stopStep("step-1");

        lifecycle.stopTestCase(testUuid);
        lifecycle.writeTestCase(testUuid);

        assertThat(writer.testResults()).hasSize(1);
        TestResult captured = writer.testResults().get(0);
        assertThat(captured.getLabels()).extracting(Label::getName, Label::getValue).contains(tuple("tag", "smoke"));
        assertThat(captured.getParameters()).extracting(Parameter::getName, Parameter::getValue).contains(tuple("scenarioId", "flow"));
        assertThat(captured.getSteps()).singleElement().satisfies(step -> {
            assertThat(step.getName()).isEqualTo("rest.post s1");
            assertThat(step.getStatus()).isEqualTo(Status.FAILED);
            assertThat(step.getStatusDetails().getMessage()).isEqualTo("boom");
            assertThat(step.getParameters()).extracting(Parameter::getName, Parameter::getValue).contains(tuple("k", "v"));
            assertThat(step.getAttachments()).extracting(io.qameta.allure.model.Attachment::getName).contains("diagnostics");
        });
    }

    @Test
    @DisplayName("a PASSED status with no message produces no status details")
    void passedStatus_hasNoStatusDetails() {
        CapturingWriter writer = new CapturingWriter();
        AllureLifecycle lifecycle = new AllureLifecycle(writer);
        DefaultAllureLifecycleFacade facade = new DefaultAllureLifecycleFacade(lifecycle);
        String testUuid = "test-2";
        lifecycle.scheduleTestCase(new TestResult().setUuid(testUuid));
        lifecycle.startTestCase(testUuid);

        facade.startStep("step-1", "rest.get s1");
        facade.updateStep("step-1", AllureStatus.PASSED, null, null, Map.of());
        facade.stopStep("step-1");

        lifecycle.stopTestCase(testUuid);
        lifecycle.writeTestCase(testUuid);

        StepResult step = writer.testResults().get(0).getSteps().get(0);
        assertThat(step.getStatus()).isEqualTo(Status.PASSED);
        assertThat(step.getStatusDetails()).isNull();
    }

    @Test
    @DisplayName("the file extension is normalised: blank defaults to .txt, a dotted value is kept")
    void addAttachment_normalisesFileExtension() {
        CapturingWriter writer = new CapturingWriter();
        AllureLifecycle lifecycle = new AllureLifecycle(writer);
        DefaultAllureLifecycleFacade facade = new DefaultAllureLifecycleFacade(lifecycle);
        String testUuid = "test-3";
        lifecycle.scheduleTestCase(new TestResult().setUuid(testUuid));
        lifecycle.startTestCase(testUuid);

        facade.addAttachment("a", "text/plain", "txt", "x");
        facade.addAttachment("b", "application/json", ".json", "{}");
        facade.addAttachment("c", "text/plain", null, "y");

        lifecycle.stopTestCase(testUuid);

        assertThat(writer.attachmentSources()).anyMatch(source -> source.endsWith(".txt"));
        assertThat(writer.attachmentSources()).anyMatch(source -> source.endsWith(".json"));
    }

    private static Map<String, String> parameters(String key, String value) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(key, value);
        return parameters;
    }

    private static final class CapturingWriter implements AllureResultsWriter {

        private final List<TestResult> testResults = new ArrayList<>();
        private final List<String> attachmentSources = new ArrayList<>();

        @Override
        public void write(TestResult testResult) {
            testResults.add(testResult);
        }

        @Override
        public void write(TestResultContainer testResultContainer) {
            // not asserted in these tests
        }

        @Override
        public void write(String source, InputStream attachment) {
            attachmentSources.add(source);
        }

        List<TestResult> testResults() {
            return testResults;
        }

        List<String> attachmentSources() {
            return attachmentSources;
        }
    }
}
