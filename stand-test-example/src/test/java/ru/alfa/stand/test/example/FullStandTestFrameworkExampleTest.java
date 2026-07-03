package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.allure.lifecycle.AllureStatus;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.AwaitResult;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.await.DefaultAwaiter;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.grpc.GrpcStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * The framework-composition proof: one scenario runs through every offline-capable SDK layer at once —
 * Scenario model → {@code DefaultScenarioValidator} → {@code DefaultScenarioRunner} → the
 * {@code StepExecutor} SPI (real REST/DB/gRPC executors plus a test-only {@link VariableSnapshotProbe})
 * → the await engine (inside {@code db.expectEventually}) → per-run variable capture/resolve →
 * SDK-owned correlation propagation (REST header + gRPC metadata) → reporting events → the Allure
 * mapping. Kafka is the one adapter that cannot join the offline composition ({@code kafka.expect} arms
 * a real consumer) — see {@code KafkaExampleTest} (tagged) and the AI documents for its coverage.
 *
 * <p>Not a business test: the endpoints are in-process doubles whitelisted through the example registry,
 * and the flow (create → await stored state → unary call → verify variables) is generic.
 */
class FullStandTestFrameworkExampleTest {

    @BeforeAll
    static void bootstrapSchema() {
        ExampleH2.createOrdersTable();
    }

    @Test
    @DisplayName("one scenario composes REST, DB await, gRPC, variable capture, correlation and Allure reporting")
    void fullFramework_composesInOneScenario() {
        String target = Objects.requireNonNull(
                System.getenv("GRPC_TARGET"), "GRPC_TARGET must be set (see stand-test-example/build.gradle.kts)");
        int grpcPort = Integer.parseInt(target.substring(target.lastIndexOf(':') + 1));
        try (ExampleHttpServer restService = new ExampleHttpServer(200, "{\"requestId\":\"full-1\",\"status\":\"ACCEPTED\"}");
                ExampleGrpcServer grpcService = new ExampleGrpcServer(grpcPort)) {
            CapturingAllureLifecycleFacade allure = new CapturingAllureLifecycleFacade();
            VariableSnapshotProbe probe = new VariableSnapshotProbe();
            StandClient stand = ExampleStand.fullStand(
                    ExampleStand.fullRegistry(restService.baseUrl()), new AllureReportingEventPublisher(allure), probe);

            Scenario scenario = Scenario.builder("full-framework-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .tag("composition")
                    .step(RestStep.post(ExampleStand.SERVICE, "/api/requests")
                            .id("create-request")
                            .header("Content-Type", "application/json")
                            .body("{\"amount\":100}")
                            .injectCorrelationId()
                            .expectStatus(200)
                            .assertPath("$.status", "ACCEPTED")
                            .capture("requestId", "$.requestId")
                            .build())
                    .step(DbStep.seed(ExampleStand.DATASOURCE)
                            .id("seed-order")
                            .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'ACCEPTED', :testRunId)")
                            .param("id", "${requestId}")
                            .build())
                    .step(DbStep.expectEventually(ExampleStand.DATASOURCE)
                            .id("await-stored-status")
                            .sql("SELECT status FROM test_data.orders WHERE id = :id")
                            .param("id", "${requestId}")
                            .expectValue("ACCEPTED")
                            .withinSeconds(2)
                            .build())
                    .step(GrpcStep.unary(ExampleStand.GRPC_TARGET)
                            .id("check-operation")
                            .method("grpc.health.v1.Health/Check")
                            .requestFromResource("fixtures/health-check.json")
                            .injectCorrelationId()
                            .withinSeconds(5)
                            .assertPath("$.status", "SERVING")
                            .capture("operationId", "$.status")
                            .build())
                    .step(GenericStep.of("verify-variables", VariableSnapshotProbe.STEP_TYPE))
                    .step(DbStep.cleanup(ExampleStand.DATASOURCE)
                            .id("cleanup-orders")
                            .sql("DELETE FROM test_data.orders")
                            .whereTestRunId("test_run_id")
                            .build())
                    .build();

            ScenarioResult result = stand.run(scenario);

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.stepResults()).hasSize(6);
            assertThat(result.stepResults()).allSatisfy(step -> assertThat(step.status()).isEqualTo(StepStatus.SUCCESS));

            // CorrelationId: SDK-owned, injected outbound into the REST header AND the gRPC metadata,
            // carried on the result and in the reporting metadata — one id everywhere.
            String correlationId = result.correlationId().value();
            assertThat(restService.receivedHeader(ExampleStand.CORRELATION_HEADER)).isEqualTo(correlationId);
            assertThat(grpcService.receivedMetadata(ExampleStand.GRPC_CORRELATION_METADATA)).isEqualTo(correlationId);
            assertThat(allure.testParameters()).containsEntry("correlationId", correlationId);

            // VariableStore: the REST capture wrote requestId, the gRPC capture wrote operationId, the DB
            // steps resolved ${requestId} (they passed), and nothing else leaked into the per-run store.
            assertThat(probe.snapshots()).hasSize(1);
            assertThat(probe.snapshots().get(0))
                    .containsEntry("requestId", "full-1")
                    .containsEntry("operationId", "SERVING")
                    .hasSize(2);

            // Reporting: every step produced Allure lifecycle calls with PASSED status.
            assertThat(allure.startedStepNames()).hasSize(6);
            assertThat(allure.startedStepNames().get(0)).contains("create-request");
            assertThat(allure.stepStatuses()).containsOnly(AllureStatus.PASSED);
            assertThat(allure.stoppedSteps()).isEqualTo(6);
            assertThat(allure.testParameters())
                    .containsEntry("scenarioId", "full-framework-example")
                    .containsEntry("environment", ExampleStand.ENVIRONMENT)
                    .containsKey("testRunId");

            // VariableStore isolation: a second run on the SAME client starts from an empty store and a
            // fresh testRunId — variables never live in static state or in the ScenarioContext.
            Scenario secondRun = Scenario.builder("full-framework-isolation")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(GenericStep.of("verify-empty-store", VariableSnapshotProbe.STEP_TYPE))
                    .build();
            assertThat(stand.run(secondRun).isSuccessful()).isTrue();
            assertThat(probe.snapshots()).hasSize(2);
            assertThat(probe.snapshots().get(1)).isEmpty();
            assertThat(probe.testRunIds().get(1)).isNotEqualTo(probe.testRunIds().get(0));
        }
    }

    @Test
    @DisplayName("the await engine collects poll attempts deterministically on a fake time source")
    void await_collectsAttemptsWithoutWallClockTime() {
        Awaiter awaiter = new DefaultAwaiter(new AdvancingTimeSource());
        AtomicInteger polls = new AtomicInteger();
        AwaitPolicy policy = AwaitPolicy.builder("fake event becomes visible")
                .timeout(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .build();

        AwaitResult<Integer> result = awaiter.await(policy, polls::incrementAndGet, attempt -> attempt >= 3);

        assertThat(result.satisfied()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.value()).isEqualTo(3);
    }
}
