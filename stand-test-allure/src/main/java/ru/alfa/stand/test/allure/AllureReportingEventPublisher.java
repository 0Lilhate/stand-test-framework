package ru.alfa.stand.test.allure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import ru.alfa.stand.test.allure.attachment.AllureAttachmentPublisher;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.allure.lifecycle.DefaultAllureLifecycleFacade;
import ru.alfa.stand.test.allure.mapping.AllureMetadataMapper;
import ru.alfa.stand.test.allure.mapping.AllureStepMapper;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
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

    private final AllureLifecycleFacade lifecycle;
    private final AllureStepMapper stepMapper;
    private final AllureMetadataMapper metadataMapper;
    private final AllureAttachmentPublisher attachmentPublisher;
    private final ThreadLocal<Deque<String>> stepUuids = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Creates a publisher writing to the global Allure lifecycle.
     */
    public AllureReportingEventPublisher() {
        this(new DefaultAllureLifecycleFacade());
    }

    /**
     * Creates a publisher writing through the given lifecycle facade (the seam used by tests).
     *
     * @param lifecycle the Allure lifecycle facade
     */
    public AllureReportingEventPublisher(AllureLifecycleFacade lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        SecretMasker secretMasker = new SecretMasker();
        this.stepMapper = new AllureStepMapper();
        this.metadataMapper = new AllureMetadataMapper(secretMasker);
        this.attachmentPublisher = new AllureAttachmentPublisher(lifecycle, secretMasker);
    }

    @Override
    public void publish(ScenarioEvent event) {
        try {
            if (event.phase() == ScenarioPhase.STARTED) {
                lifecycle.updateTestCase(metadataMapper.scenarioLabels(event), metadataMapper.scenarioParameters(event));
            }
            // ScenarioPhase.FINISHED is a no-op: the JUnit/Allure integration owns closing the test case.
        } catch (RuntimeException reportingFailure) {
            // Reporting is a best-effort side-channel (plan §17): a rendering error must never change the
            // test outcome. Swallowed; becomes a WARN log once SLF4J is wired.
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
        } catch (RuntimeException reportingFailure) {
            // Reporting is a best-effort side-channel (plan §17): a rendering error must never change the
            // test outcome (and must never hide an SDK failure). Swallowed; becomes a WARN log later.
        }
    }

    private void startStep(StepEvent event) {
        String uuid = UUID.randomUUID().toString();
        stepUuids.get().push(uuid);
        lifecycle.startStep(uuid, stepMapper.stepName(event));
    }

    private void finishStep(StepEvent event) {
        Deque<String> stack = stepUuids.get();
        String uuid = stack.poll();
        try {
            if (uuid == null) {
                // A FINISHED without a matching STARTED (e.g. the publisher was attached mid-run):
                // synthesise a step so the outcome is still recorded rather than dropped.
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
            // Guarantee the per-thread stack is cleaned up even if a (best-effort) lifecycle call throws,
            // so a reused pool thread never inherits an orphaned entry. The publish() guard swallows the
            // throwable; this finally only restores the ThreadLocal invariant.
            if (stack.isEmpty()) {
                stepUuids.remove();
            }
        }
    }
}
