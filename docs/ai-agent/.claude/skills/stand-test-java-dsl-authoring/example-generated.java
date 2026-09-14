// Example: generated Java DSL test for case OT-101.
// Derived from ../stand-test-scenario-design/example-scenario-design.md. Generic aliases only; endpoints and
// credentials live behind env-var refs in the consumer registry, never here.
//
// NOTE: this file lives under docs/ as an illustration — in a real consumer project it goes
// to src/test/java/<base package>/ and must compile there.

package example.qa.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.kafka.KafkaStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Case OT-101: a created order is accepted synchronously, published as ORDER_CREATED and
 * projected to the reporting database as DONE.
 *
 * <p>Assumptions: Kafka SLA = DB SLA = 30s; reporting rows are purged by the stand
 * (no SDK cleanup possible — rows carry no test_run_id column). Not asserted: "no event is
 * published for a rejected order" (no declarative negative-receive construct in the SDK).
 */
@SpringBootTest
class OrderCreatedProjectionTest {

    @Autowired
    private StandClient stand;

    @Test
    @DisplayName("Created order is accepted, published as ORDER_CREATED and projected as DONE")
    void orderCreatedIsProjected() {
        Scenario scenario = Scenario.builder("order-created-projection")
                .environment("ift")
                .tag("integration")
                .tag("orders")
                .step(RestStep.post("order-service", "/api/orders")
                        .id("create-order")
                        .header("Content-Type", "application/json")
                        .body("{\"externalId\":\"order-${testRunId}\",\"amount\":100,\"currency\":\"EUR\"}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .assertPath("$.status", "ACCEPTED")
                        .assertPathMatches("$.orderId", "ord-[0-9]+")
                        .capture("orderId", "$.orderId")
                        .build())
                .step(KafkaStep.expect("order-events")
                        .id("await-order-event")
                        .correlationIdFromContext()
                        .withinSeconds(30)
                        .assertPath("$.status", "CREATED")
                        .build())
                .step(DbStep.expectEventually("orders-db")
                        .id("verify-projection")
                        .sql("SELECT status FROM reporting.orders WHERE order_id = :orderId")
                        .param("orderId", "${orderId}")
                        .expectValue("DONE")
                        .withinSeconds(30)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("Order with negative amount is rejected and produces no projection")
    void negativeAmountIsRejected() {
        Scenario scenario = Scenario.builder("order-rejected-negative-amount")
                .environment("ift")
                .tag("integration")
                .tag("orders")
                .step(RestStep.post("order-service", "/api/orders")
                        .id("create-rejected-order")
                        .header("Content-Type", "application/json")
                        .body("{\"externalId\":\"order-${testRunId}\",\"amount\":-1,\"currency\":\"EUR\"}")
                        .injectCorrelationId()
                        .expectStatus(200)
                        .assertPath("$.status", "REJECTED")
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
