package ru.alfa.stand.test.allure.lifecycle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link AllureLifecycleFacade} test double that records every call, so the publisher and
 * mappers can be asserted without an Allure runtime and without depending on JUnit/Allure ordering.
 */
public final class FakeAllureLifecycleFacade implements AllureLifecycleFacade {

    private final List<StartedStep> startedSteps = new ArrayList<>();
    private final List<UpdatedStep> updatedSteps = new ArrayList<>();
    private final List<RecordedAttachment> attachments = new ArrayList<>();
    private final List<RecordedFileAttachment> fileAttachments = new ArrayList<>();
    private final List<String> stoppedSteps = new ArrayList<>();
    private final List<TestCaseUpdate> testCaseUpdates = new ArrayList<>();
    private boolean throwOnAddAttachment;
    private boolean throwOnUpdateStep;

    @Override
    public void startStep(String uuid, String name) {
        startedSteps.add(new StartedStep(uuid, name));
    }

    @Override
    public void updateStep(String uuid, AllureStatus status, String statusMessage, String statusTrace, Map<String, String> parameters) {
        if (throwOnUpdateStep) {
            throw new IllegalStateException("update boom");
        }
        updatedSteps.add(new UpdatedStep(uuid, status, statusMessage, statusTrace, new LinkedHashMap<>(parameters)));
    }

    @Override
    public void addAttachment(String name, String type, String fileExtension, String content) {
        if (throwOnAddAttachment) {
            throw new IllegalStateException("attachment boom");
        }
        attachments.add(new RecordedAttachment(name, type, fileExtension, content));
    }

    @Override
    public void addAttachment(String name, String type, String fileExtension, Path file) {
        if (throwOnAddAttachment) {
            throw new IllegalStateException("attachment boom");
        }
        fileAttachments.add(new RecordedFileAttachment(name, type, fileExtension, file));
    }

    @Override
    public void stopStep(String uuid) {
        stoppedSteps.add(uuid);
    }

    @Override
    public void updateTestCase(List<AllureLabel> labels, Map<String, String> parameters) {
        testCaseUpdates.add(new TestCaseUpdate(new ArrayList<>(labels), new LinkedHashMap<>(parameters)));
    }

    public void throwOnAddAttachment() {
        this.throwOnAddAttachment = true;
    }

    public void throwOnUpdateStep() {
        this.throwOnUpdateStep = true;
    }

    public List<StartedStep> startedSteps() {
        return startedSteps;
    }

    public List<UpdatedStep> updatedSteps() {
        return updatedSteps;
    }

    public List<RecordedAttachment> attachments() {
        return attachments;
    }

    public List<RecordedFileAttachment> fileAttachments() {
        return fileAttachments;
    }

    public List<String> stoppedSteps() {
        return stoppedSteps;
    }

    public List<TestCaseUpdate> testCaseUpdates() {
        return testCaseUpdates;
    }

    /** A recorded {@code startStep} call. */
    public record StartedStep(String uuid, String name) {
    }

    /** A recorded {@code updateStep} call. */
    public record UpdatedStep(String uuid, AllureStatus status, String message, String trace, Map<String, String> parameters) {
    }

    /** A recorded {@code addAttachment} call. */
    public record RecordedAttachment(String name, String type, String fileExtension, String content) {
    }

    /** A recorded file-backed {@code addAttachment} call. */
    public record RecordedFileAttachment(String name, String type, String fileExtension, Path file) {
    }

    /** A recorded {@code updateTestCase} call. */
    public record TestCaseUpdate(List<AllureLabel> labels, Map<String, String> parameters) {
    }
}
