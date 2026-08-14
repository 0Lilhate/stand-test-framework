package ru.alfa.stand.test.allure;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.allure.attachment.AllureAttachmentPublisher;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.allure.lifecycle.DefaultAllureLifecycleFacade;
import ru.alfa.stand.test.allure.mapping.AllureMetadataMapper;
import ru.alfa.stand.test.allure.mapping.AllureStepMapper;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.RunArtifacts;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.ScenarioPhase;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;

/**
 * The core {@link ReportingEventPublisher} implementation that renders SDK reporting events as Allure
 * steps, labels, parameters and attachments.
 *
 * <p>This is the whole Allure integration: it is a <em>consumer</em> of the generic core event model and
 * knows nothing about REST/Kafka/DB/gRPC. It runs no scenario and performs no transport IO. The runner
 * publishes a {@code ScenarioEvent(STARTED)} (decorating the active Allure test case with
 * scenarioId/testRunId/correlationId/environment parameters and {@code tag} labels), a
 * {@code StepEvent(STARTED)} and a {@code StepEvent(FINISHED)} per step (mapping status, attaching the
 * step's diagnostics and any {@link Attachment}s), and a closing {@code ScenarioEvent(FINISHED)} (a
 * no-op here — the JUnit/Allure integration closes the test case).
 *
 * <p><strong>Failure safety (plan §7, §17).</strong> Every mapping call is wrapped: a rendering error is
 * swallowed and never replaces the test's real outcome, never hides an SDK assertion failure and never
 * turns a failed step green. The runner already swallows publisher exceptions; this is the second guard.
 *
 * <p><strong>Confinement.</strong> A scenario run executes on one thread, and Allure's lifecycle is
 * thread-bound. The publisher tracks the started-step ids on a per-thread stack so STARTED/FINISHED
 * pair up correctly, which also keeps concurrent runs on different threads isolated.
 */
public final class AllureReportingEventPublisher implements ReportingEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(AllureReportingEventPublisher.class);

    private final AllureLifecycleFacade lifecycle;
    private final AllureStepMapper stepMapper;
    private final AllureMetadataMapper metadataMapper;
    private final AllureAttachmentPublisher attachmentPublisher;
    private final ThreadLocal<Deque<String>> stepUuids = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Creates a publisher writing to the global Allure lifecycle. This is the constructor the
     * {@code ServiceLoader} calls, so it is the wiring every consumer actually gets.
     */
    public AllureReportingEventPublisher() {
        this(new DefaultAllureLifecycleFacade());
    }

    /**
     * Creates a publisher writing through the given lifecycle facade (the seam used by tests), resolving
     * the run's artefacts directory exactly as the no-argument constructor does.
     *
     * @param lifecycle the Allure lifecycle facade
     */
    public AllureReportingEventPublisher(AllureLifecycleFacade lifecycle) {
        this(lifecycle, RunArtifacts.directory(System::getProperty));
    }

    /**
     * Creates a publisher reading file attachments from an explicitly given artefacts directory.
     *
     * <p>The directory is what makes the file channel usable at all: the attachment publisher refuses every
     * file it cannot prove belongs to the run, so a publisher built without one silently drops every
     * screenshot (UITG-F003). The other constructors resolve it through {@link RunArtifacts}, the single
     * definition the producing adapter reads too; this one exists for a consumer that keeps its artefacts
     * somewhere else, and for tests that want a temporary directory.
     *
     * @param lifecycle the Allure lifecycle facade
     * @param artifactsRoot the run's artefacts directory; null refuses every file attachment
     */
    public AllureReportingEventPublisher(AllureLifecycleFacade lifecycle, Path artifactsRoot) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        SecretMasker secretMasker = new SecretMasker();
        this.stepMapper = new AllureStepMapper();
        this.metadataMapper = new AllureMetadataMapper(secretMasker);
        this.attachmentPublisher = new AllureAttachmentPublisher(lifecycle, secretMasker, artifactsRoot);
    }

    @Override
    public void publish(ScenarioEvent event) {
        try {
            if (event.phase() == ScenarioPhase.STARTED) {
                lifecycle.updateTestCase(metadataMapper.scenarioLabels(event), metadataMapper.scenarioParameters(event));
            } else if (event.phase() == ScenarioPhase.FINISHED) {
                stepUuids.remove();
            }
        } catch (Throwable reportingFailure) {
            swallow(reportingFailure);
        }
    }

    @Override
    public void publish(StepEvent event) {
        try {
            if (event.phase() == StepPhase.STARTED) {
                startStep(event);
            } else if (event.phase() == StepPhase.FINISHED) {
                finishStep(event);
            }
        } catch (Throwable reportingFailure) {
            swallow(reportingFailure);
        }
    }

    /**
     * Reporting is a best-effort side-channel (plan §17): a rendering error must never change the test
     * outcome or hide an SDK failure. {@link Throwable} rather than {@link RuntimeException}, so an Error
     * from a version-skewed allure-model on the consumer classpath (LinkageError/NoClassDefFoundError)
     * cannot escape this sink either. The failure is logged, never rethrown.
     */
    private static void swallow(Throwable reportingFailure) {
        LOG.warn("Allure reporting failed and was ignored so the test outcome stays untouched", reportingFailure);
    }

    private void startStep(StepEvent event) {
        String uuid = UUID.randomUUID().toString();
        lifecycle.startStep(uuid, stepMapper.stepName(event));
        stepUuids.get().push(uuid);
    }

    private void finishStep(StepEvent event) {
        Deque<String> stack = stepUuids.get();
        String uuid = stack.poll();
        try {
            if (uuid == null) {
                uuid = UUID.randomUUID().toString();
                lifecycle.startStep(uuid, stepMapper.stepName(event));
            }
            AllureStatus status = stepMapper.toStatus(event.status());
            lifecycle.updateStep(uuid, status, event.message(), null, metadataMapper.stepParameters(event));
            attachmentPublisher.publishDiagnostics(event.diagnostics());
            for (Attachment attachment : event.attachments()) {
                attachmentPublisher.publish(attachment);
            }
            lifecycle.stopStep(uuid);
        } finally {
            if (stack.isEmpty()) {
                stepUuids.remove();
            }
        }
    }
}
