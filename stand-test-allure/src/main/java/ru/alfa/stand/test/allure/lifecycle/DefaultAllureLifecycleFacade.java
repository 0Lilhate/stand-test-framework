package ru.alfa.stand.test.allure.lifecycle;

import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Parameter;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.StepResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Production {@link AllureLifecycleFacade}: the single place that talks to {@code io.qameta.allure.*}.
 *
 * <p>It translates the adapter's transport-neutral types ({@link AllureStatus}, {@link AllureLabel},
 * parameter maps, textual attachments) into the Allure model and drives {@link AllureLifecycle}. Because
 * every other class depends only on {@link AllureLifecycleFacade}, the mapping logic is fully testable
 * without an Allure runtime; this class is the thin, untested-by-design boundary.
 *
 * <p>Confinement: Allure's lifecycle is thread-bound, and a single scenario run executes on one thread,
 * so steps started here nest correctly under the JUnit/Allure test case active on that thread.
 */
public final class DefaultAllureLifecycleFacade implements AllureLifecycleFacade {

    private final AllureLifecycle lifecycle;

    /**
     * Creates a facade bound to the global Allure lifecycle ({@code Allure.getLifecycle()}).
     */
    public DefaultAllureLifecycleFacade() {
        this(Allure.getLifecycle());
    }

    DefaultAllureLifecycleFacade(AllureLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    }

    @Override
    public void startStep(String uuid, String name) {
        StepResult step = new StepResult();
        step.setName(name);
        lifecycle.startStep(uuid, step);
    }

    @Override
    public void updateStep(String uuid, AllureStatus status, String statusMessage, String statusTrace, Map<String, String> parameters) {
        List<Parameter> modelParameters = toParameters(parameters);
        StatusDetails details = toStatusDetails(statusMessage, statusTrace);
        lifecycle.updateStep(uuid, step -> {
            step.setStatus(toModelStatus(status));
            if (details != null) {
                step.setStatusDetails(details);
            }
            step.setParameters(modelParameters);
        });
    }

    @Override
    public void addAttachment(String name, String type, String fileExtension, String content) {
        lifecycle.addAttachment(name, type, normaliseExtension(fileExtension), content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void addAttachment(String name, String type, String fileExtension, Path file) {
        byte[] body;
        try {
            body = Files.readAllBytes(file);
        } catch (IOException e) {
            // The publisher already skipped a missing file with a WARN, so reaching here means the file
            // vanished between that check and this read. The publisher's guard swallows and logs it —
            // reporting must never change a test outcome (plan §17).
            throw new UncheckedIOException("cannot read attachment file " + file, e);
        }
        lifecycle.addAttachment(name, type, normaliseExtension(fileExtension), body);
    }

    @Override
    public void stopStep(String uuid) {
        lifecycle.stopStep(uuid);
    }

    @Override
    public void updateTestCase(List<AllureLabel> labels, Map<String, String> parameters) {
        List<Label> modelLabels = new ArrayList<>();
        for (AllureLabel label : labels) {
            Label modelLabel = new Label();
            modelLabel.setName(label.name());
            modelLabel.setValue(label.value());
            modelLabels.add(modelLabel);
        }
        List<Parameter> modelParameters = toParameters(parameters);
        lifecycle.updateTestCase(testResult -> {
            testResult.getLabels().addAll(modelLabels);
            testResult.getParameters().addAll(modelParameters);
        });
    }

    private static String normaliseExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.isBlank()) {
            return ".txt";
        }
        return fileExtension.startsWith(".") ? fileExtension : "." + fileExtension;
    }

    private static List<Parameter> toParameters(Map<String, String> parameters) {
        List<Parameter> modelParameters = new ArrayList<>();
        parameters.forEach((name, value) -> {
            Parameter parameter = new Parameter();
            parameter.setName(name);
            parameter.setValue(value);
            modelParameters.add(parameter);
        });
        return modelParameters;
    }

    private static StatusDetails toStatusDetails(String message, String trace) {
        if (message == null && trace == null) {
            return null;
        }
        StatusDetails details = new StatusDetails();
        details.setMessage(message);
        details.setTrace(trace);
        return details;
    }

    private static Status toModelStatus(AllureStatus status) {
        return switch (status) {
            case PASSED -> Status.PASSED;
            case FAILED -> Status.FAILED;
            case BROKEN -> Status.BROKEN;
            case SKIPPED -> Status.SKIPPED;
        };
    }
}
