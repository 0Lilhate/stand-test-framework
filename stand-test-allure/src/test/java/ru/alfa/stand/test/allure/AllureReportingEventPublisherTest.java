package ru.alfa.stand.test.allure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.lifecycle.AllureLabel;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade.RecordedAttachment;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade.UpdatedStep;
import ru.alfa.stand.test.core.event.Attachment;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.ScenarioPhase;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.event.StepPhase;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;
import ru.alfa.stand.test.core.result.StepStatus;

class AllureReportingEventPublisherTest {

    private static final ScenarioId SCENARIO_ID = ScenarioId.of("flow");
    private static final TestRunId TEST_RUN_ID = TestRunId.of("run-1");
    private static final CorrelationId CORRELATION_ID = CorrelationId.of("corr-1");
    private static final Instant NOW = Instant.parse("2026-06-26T10:00:00Z");

    private final FakeAllureLifecycleFacade lifecycle = new FakeAllureLifecycleFacade();
    private final AllureReportingEventPublisher publisher = new AllureReportingEventPublisher(lifecycle);

    @Test
    @DisplayName("a SUCCESS step is started, marked PASSED and stopped under one uuid")
    void successStep_mapsToPassed() {
        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null, Map.of(), List.of()));

        assertThat(lifecycle.startedSteps()).hasSize(1);
        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::status).isEqualTo(AllureStatus.PASSED);
        String startedUuid = lifecycle.startedSteps().get(0).uuid();
        assertThat(lifecycle.updatedSteps().get(0).uuid()).isEqualTo(startedUuid);
        assertThat(lifecycle.stoppedSteps()).containsExactly(startedUuid);
    }

    @Test
    @DisplayName("a FAILED step maps to FAILED and carries the error message")
    void failedStep_mapsToFailedWithMessage() {
        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.FAILED, "expected 200 but was 500", Map.of(), List.of()));

        assertThat(lifecycle.updatedSteps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(AllureStatus.FAILED);
            assertThat(step.message()).isEqualTo("expected 200 but was 500");
        });
    }

    @Test
    @DisplayName("a BROKEN step (infrastructure failure) maps to BROKEN")
    void brokenStep_mapsToBroken() {
        publisher.publish(stepStarted("s1", "db.query"));
        publisher.publish(stepFinished("s1", "db.query", StepStatus.BROKEN, "datasource unreachable",
                Map.of("exception.class", "ru.alfa.stand.test.core.exception.StandTestException"), List.of()));

        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::status).isEqualTo(AllureStatus.BROKEN);
        assertThat(lifecycle.attachments()).anySatisfy(attachment ->
                assertThat(attachment.content()).contains("exception.class"));
    }

    @Test
    @DisplayName("a TIMEOUT step maps to FAILED and attaches its timeout diagnostics")
    void timeoutStep_mapsToFailedWithDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("await", "order visible");
        diagnostics.put("timeout", "PT30S");
        diagnostics.put("pollInterval", "PT1S");
        diagnostics.put("attempts", 30);
        diagnostics.put("elapsed", "PT30S");
        diagnostics.put("lastValue", "PENDING");

        publisher.publish(stepStarted("s1", "kafka.expect"));
        publisher.publish(stepFinished("s1", "kafka.expect", StepStatus.TIMEOUT, "not satisfied in time", diagnostics, List.of()));

        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::status).isEqualTo(AllureStatus.FAILED);
        assertThat(lifecycle.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo("diagnostics");
            assertThat(attachment.content())
                    .contains("timeout=PT30S")
                    .contains("attempts=30")
                    .contains("lastValue=PENDING");
        });
    }

    @Test
    @DisplayName("a SKIPPED step maps to SKIPPED")
    void skippedStep_mapsToSkipped() {
        publisher.publish(stepStarted("s1", "rest.get"));
        publisher.publish(stepFinished("s1", "rest.get", StepStatus.SKIPPED, null, Map.of(), List.of()));

        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::status).isEqualTo(AllureStatus.SKIPPED);
    }

    @Test
    @DisplayName("a SKIPPED step still renders its diagnostics")
    void skippedStep_rendersDiagnostics() {
        publisher.publish(stepStarted("s1", "rest.get"));
        publisher.publish(stepFinished("s1", "rest.get", StepStatus.SKIPPED, null,
                Map.of("skipReason", "feature flag off"), List.of()));

        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::status).isEqualTo(AllureStatus.SKIPPED);
        assertThat(lifecycle.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.name()).isEqualTo("diagnostics");
            assertThat(attachment.content()).contains("skipReason=feature flag off");
        });
    }

    @Test
    @DisplayName("a FAILED step with a null message does not crash and is still recorded as FAILED")
    void failedStep_nullMessage_doesNotCrash() {
        publisher.publish(stepStarted("s1", "rest.post"));

        assertThatCode(() -> publisher.publish(stepFinished("s1", "rest.post", StepStatus.FAILED, null, Map.of(), List.of())))
                .doesNotThrowAnyException();
        assertThat(lifecycle.updatedSteps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(AllureStatus.FAILED);
            assertThat(step.message()).isNull();
        });
    }

    @Test
    @DisplayName("the scenario STARTED event labels the test case with tags and id/env parameters")
    void scenarioStarted_setsLabelsAndParameters() {
        publisher.publish(new ScenarioEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, "ift", Set.of("smoke"), ScenarioPhase.STARTED, NOW));

        assertThat(lifecycle.testCaseUpdates()).singleElement().satisfies(update -> {
            assertThat(update.labels()).containsExactly(new AllureLabel("tag", "smoke"));
            assertThat(update.parameters()).containsExactly(
                    Map.entry("scenarioId", "flow"),
                    Map.entry("testRunId", "run-1"),
                    Map.entry("correlationId", "corr-1"),
                    Map.entry("environment", "ift"));
        });
    }

    @Test
    @DisplayName("the scenario FINISHED event resets the per-thread step stack but does not touch the test case (the JUnit/Allure integration closes the test)")
    void scenarioFinished_resetsStepStackWithoutTouchingTestCase() {
        publisher.publish(new ScenarioEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, "ift", Set.of(), ScenarioPhase.FINISHED, NOW));

        assertThat(lifecycle.testCaseUpdates()).isEmpty();
    }

    @Test
    @DisplayName("a scenario FINISHED clears an orphaned step so a reused thread starts with a clean stack (parallel-safety, plan §15)")
    void scenarioFinished_clearsOrphanedStepForNextRun() {
        // Run 1 leaves an unbalanced stack: a step is STARTED but the run ends (scenario FINISHED) before its
        // FINISHED — what a mid-run failure or a swallowed lifecycle call can leave on a pooled thread.
        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(new ScenarioEvent(SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, "ift", Set.of(), ScenarioPhase.FINISHED, NOW));

        // Run 2 on the SAME thread/publisher: its FINISHED must synthesise a fresh step (the stack was reset),
        // not close run 1's orphan under run 2's data.
        publisher.publish(stepFinished("s2", "kafka.expect", StepStatus.SUCCESS, null, Map.of(), List.of()));

        // Run 1 started s1 (never stopped); run 2 synthesised its own step -> a SECOND startStep, and the stop
        // targets that fresh uuid, never s1's. Without the reset, run 2 would reuse s1's uuid (one startStep).
        assertThat(lifecycle.startedSteps()).hasSize(2);
        String synthesised = lifecycle.startedSteps().get(1).uuid();
        assertThat(lifecycle.stoppedSteps()).containsExactly(synthesised);
    }

    @Test
    @DisplayName("one shared publisher keeps its per-thread step stack isolated across concurrent (and reused) threads")
    void concurrentRuns_keepStepStacksThreadLocal() throws Exception {
        ThreadRecordingFacade facade = new ThreadRecordingFacade();
        AllureReportingEventPublisher shared = new AllureReportingEventPublisher(facade);
        int tasks = 16;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks, Math.max(4, Runtime.getRuntime().availableProcessors())));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                futures.add(pool.submit(() -> {
                    shared.publish(stepStarted("s", "rest.post"));
                    shared.publish(stepFinished("s", "rest.post", StepStatus.SUCCESS, null, Map.of(), List.of()));
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            // Group each thread's events in its own order. Every start must be immediately followed by a stop
            // on the SAME uuid: a shared (non-ThreadLocal) stack would let one thread stop another's uuid.
            Map<String, List<String>> byThread = new LinkedHashMap<>();
            for (String event : facade.events()) {
                String[] parts = event.split(":", 3);
                byThread.computeIfAbsent(parts[1], key -> new ArrayList<>()).add(parts[0] + ":" + parts[2]);
            }
            assertThat(byThread).isNotEmpty();
            for (List<String> sequence : byThread.values()) {
                assertThat(sequence.size() % 2).as("each run contributes a start and a stop").isZero();
                for (int index = 0; index < sequence.size(); index += 2) {
                    String startedUuid = sequence.get(index).substring("start:".length());
                    assertThat(sequence.get(index + 1)).isEqualTo("stop:" + startedUuid);
                }
            }
            assertThat(facade.events()).hasSize(tasks * 2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a finished step carries the scenarioId/testRunId/correlationId/stepId/stepType parameters")
    void finishedStep_carriesIdentityParameters() {
        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null, Map.of(), List.of()));

        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::parameters).isEqualTo(Map.of(
                        "scenarioId", "flow",
                        "testRunId", "run-1",
                        "correlationId", "corr-1",
                        "stepId", "s1",
                        "stepType", "rest.post"));
    }

    @Test
    @DisplayName("core attachments on a finished step are published after the diagnostics block")
    void finishedStep_publishesDiagnosticsThenAttachments() {
        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null,
                Map.of("status", 200), List.of(new Attachment("response", "application/json", "{\"ok\":true}"))));

        assertThat(lifecycle.attachments()).extracting(RecordedAttachment::name)
                .containsExactly("diagnostics", "response");
    }

    @Test
    @DisplayName("secret-looking diagnostics are masked before publishing")
    void diagnostics_areMaskedEndToEnd() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("authorization", "Bearer super-secret");

        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null, diagnostics, List.of()));

        assertThat(lifecycle.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.content()).contains("authorization=***");
            assertThat(attachment.content()).doesNotContain("super-secret");
        });
    }

    @Test
    @DisplayName("a secret-bearing core attachment body is masked before reaching the report")
    void attachmentBody_isMaskedEndToEnd() {
        publisher.publish(stepStarted("s1", "grpc.unary"));
        publisher.publish(stepFinished("s1", "grpc.unary", StepStatus.SUCCESS, null, Map.of(),
                List.of(new Attachment("grpc-response", "application/json", "{\"accessToken\":\"abc12345\"}"))));

        assertThat(lifecycle.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.content()).contains("\"accessToken\":\"***\"");
            assertThat(attachment.content()).doesNotContain("abc12345");
        });
    }

    @Test
    @DisplayName("an attachment publish failure does not hide the original failure or escape")
    void attachmentFailure_isSwallowed() {
        lifecycle.throwOnAddAttachment();

        publisher.publish(stepStarted("s1", "rest.post"));

        assertThatCode(() -> publisher.publish(stepFinished("s1", "rest.post", StepStatus.FAILED,
                "expected 200 but was 500", Map.of("status", 500), List.of())))
                .doesNotThrowAnyException();
        // The step status was recorded before the attachment attempt; the failure is not hidden.
        assertThat(lifecycle.updatedSteps()).singleElement()
                .extracting(UpdatedStep::status).isEqualTo(AllureStatus.FAILED);
    }

    @Test
    @DisplayName("an updateStep failure is swallowed and never escapes")
    void updateFailure_isSwallowed() {
        lifecycle.throwOnUpdateStep();

        publisher.publish(stepStarted("s1", "rest.post"));

        assertThatCode(() -> publisher.publish(stepFinished("s1", "rest.post", StepStatus.FAILED, "boom", Map.of(), List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null message, empty diagnostics and empty attachments do not crash reporting")
    void emptyOptionalFields_doNotCrash() {
        publisher.publish(stepStarted("s1", "rest.post"));

        assertThatCode(() -> publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null, Map.of(), List.of())))
                .doesNotThrowAnyException();
        assertThat(lifecycle.attachments()).isEmpty();
    }

    @Test
    @DisplayName("a finished step with no preceding started step is still recorded")
    void finishedWithoutStarted_synthesisesStep() {
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null, Map.of(), List.of()));

        assertThat(lifecycle.startedSteps()).hasSize(1);
        assertThat(lifecycle.stoppedSteps()).hasSize(1);
        assertThat(lifecycle.updatedSteps()).hasSize(1);
    }

    @Test
    @DisplayName("two sequential steps pair their own started/stopped uuids")
    void twoSteps_pairTheirOwnUuids() {
        publisher.publish(stepStarted("s1", "rest.post"));
        publisher.publish(stepFinished("s1", "rest.post", StepStatus.SUCCESS, null, Map.of(), List.of()));
        publisher.publish(stepStarted("s2", "kafka.expect"));
        publisher.publish(stepFinished("s2", "kafka.expect", StepStatus.SUCCESS, null, Map.of(), List.of()));

        assertThat(lifecycle.stoppedSteps()).hasSize(2);
        assertThat(lifecycle.stoppedSteps().get(0)).isEqualTo(lifecycle.startedSteps().get(0).uuid());
        assertThat(lifecycle.stoppedSteps().get(1)).isEqualTo(lifecycle.startedSteps().get(1).uuid());
        assertThat(lifecycle.stoppedSteps().get(0)).isNotEqualTo(lifecycle.stoppedSteps().get(1));
    }

    private static StepEvent stepStarted(String stepId, String stepType) {
        return new StepEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, stepId, stepType, StepPhase.STARTED,
                null, NOW, null, Map.of());
    }

    private static StepEvent stepFinished(
            String stepId,
            String stepType,
            StepStatus status,
            String message,
            Map<String, Object> diagnostics,
            List<Attachment> attachments) {
        return new StepEvent(
                SCENARIO_ID, TEST_RUN_ID, CORRELATION_ID, stepId, stepType, StepPhase.FINISHED,
                status, NOW, message, diagnostics, attachments);
    }

    /**
     * A thread-safe {@link AllureLifecycleFacade} that records step start/stop with the calling thread, so a
     * concurrent test can assert the publisher's per-thread step stack never crosses threads.
     */
    private static final class ThreadRecordingFacade implements AllureLifecycleFacade {

        private final Queue<String> events = new ConcurrentLinkedQueue<>();

        @Override
        public void startStep(String uuid, String name) {
            this.events.add("start:" + Thread.currentThread().getName() + ":" + uuid);
        }

        @Override
        public void updateStep(String uuid, AllureStatus status, String statusMessage, String statusTrace, Map<String, String> parameters) {
        }

        @Override
        public void addAttachment(String name, String type, String fileExtension, String content) {
        }

        @Override
        public void stopStep(String uuid) {
            this.events.add("stop:" + Thread.currentThread().getName() + ":" + uuid);
        }

        @Override
        public void updateTestCase(List<AllureLabel> labels, Map<String, String> parameters) {
        }

        List<String> events() {
            return List.copyOf(this.events);
        }
    }
}
