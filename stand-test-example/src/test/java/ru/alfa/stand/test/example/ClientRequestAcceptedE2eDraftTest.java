package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.grpc.GrpcStep;
import ru.alfa.stand.test.junit.StandEnv;
import ru.alfa.stand.test.junit.StandScenarioId;
import ru.alfa.stand.test.junit.StandTest;
import ru.alfa.stand.test.kafka.KafkaStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * GENERATED DRAFT (AI-agent chain validation, case {@code client-request-accepted-e2e}): a created
 * client request is accepted end to end — REST 200 with {@code requestId}, {@code request-created}
 * event ACCEPTED with the same correlationId, DB status ACCEPTED, gRPC CheckResult = OK.
 *
 * <p>Every contract detail traces to the knowledge base ({@code docs/ai-agent/knowledge-base}):
 * endpoint {@code create-request}, topic {@code request-created-topic}, probe
 * {@code request-status-by-request-id} (aligned via kb-update), gRPC {@code result-grpc-service}/
 * {@code check-result}; environment {@code ift} per the kb-lookup tie-break.
 *
 * <p>Assumptions (recorded in the lookup result): the stand pre-provisions a client with an active
 * product and accepts a run-scoped clientId derived from testRunId; timeouts 30s (Kafka/DB) and a 5s
 * gRPC deadline. Residual data: the request row is system-created without a test_run_id column, so
 * no SDK cleanup is possible; rows are identifiable by the run-scoped clientId. The case's report
 * expectation (scenarioId/testRunId/correlationId present) is satisfied by the SDK's automatic
 * reporting, not asserted in-test. The {@code operationId} capture is intentionally unconsumed:
 * it surfaces the gRPC operation id in the step result for report traceability (KB
 * {@code check-result} response.captures). This draft is gated to SKIP unless a real stand is
 * configured with the KB ift bindings; the aliases do not exist in this module's offline registry.
 */
@StandTest(env = "ift")
@StandScenarioId("client-request-accepted-e2e")
@EnabledIfEnvironmentVariable(named = "CLIENT_SERVICE_BASE_URL", matches = ".+")
class ClientRequestAcceptedE2eDraftTest {

    @Test
    @DisplayName("Created client request is accepted, published to Kafka, projected to the DB and confirmed over gRPC")
    void clientRequestIsAcceptedEndToEnd(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
        Scenario scenario = Scenario.builder(id)
                .environment(env)
                .tag("integration")
                .tag("client")
                .step(RestStep.post("client-service", "/api/requests")
                        .id("create-request")
                        .header("Content-Type", "application/json")
                        .body("{\"clientId\":\"client-${testRunId}\",\"amount\":100}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .assertPath("$.status", "ACCEPTED")
                        .capture("requestId", "$.requestId")
                        .build())
                .step(KafkaStep.expect("request-created")
                        .id("await-request-created")
                        .correlationIdFromContext()
                        .withinSeconds(30)
                        .assertPath("$.status", "ACCEPTED")
                        .build())
                .step(DbStep.expectEventually("client-db")
                        .id("verify-db-status")
                        .sql("select status from client.request where request_id = :requestId")
                        .param("requestId", "${requestId}")
                        .expectValue("ACCEPTED")
                        .withinSeconds(30)
                        .build())
                .step(GrpcStep.unary("result-grpc-service")
                        .id("check-result-grpc")
                        .method("example.ResultService/CheckResult")
                        .requestFromResource("fixtures/grpc/check-result.json")
                        .injectCorrelationId()
                        .withinSeconds(5)
                        .assertPath("$.result", "OK")
                        .capture("operationId", "$.operationId")
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
