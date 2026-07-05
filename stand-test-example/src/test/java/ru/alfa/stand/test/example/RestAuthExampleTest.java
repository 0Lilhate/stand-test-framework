package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Example: registry-driven service authentication. The service definition carries
 * {@code AuthConfig.basic} with secret <em>references</em> ({@code CLIENT_USER}/{@code CLIENT_PASSWORD}
 * env vars pinned by the build script to the RFC 7617 example credentials); the REST executor resolves
 * them at execution time and injects the {@code Authorization} header — the scenario itself carries no
 * credentials, and an inline {@code Authorization} header would be rejected by the validator.
 */
class RestAuthExampleTest {

    @Test
    @DisplayName("registry-driven basic auth resolves env references and reaches the service as a correct Authorization header")
    void basicAuth_fromRegistryReferences_reachesTheService() {
        try (ExampleHttpServer service = new ExampleHttpServer(200, "{\"status\":\"ACCEPTED\"}")) {
            StandClient stand = ExampleStand.stand(ExampleStand.authRegistry(service.baseUrl()));
            Scenario scenario = Scenario.builder("rest-auth-example")
                    .environment(ExampleStand.ENVIRONMENT)
                    .step(RestStep.get(ExampleStand.SERVICE, "/api/requests")
                            .expectStatus(200)
                            .build())
                    .build();

            ScenarioResult result = stand.run(scenario);

            assertThat(result.isSuccessful()).isTrue();
            // "Aladdin:open sesame" in UTF-8 base64 — the RFC 7617 reference example.
            assertThat(service.receivedHeader("Authorization")).isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==");
        }
    }
}
