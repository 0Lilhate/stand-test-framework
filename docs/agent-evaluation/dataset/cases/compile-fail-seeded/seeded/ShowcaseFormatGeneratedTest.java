// EVALUATION FIXTURE — a generated test that does NOT compile.
// Not part of the build: it lives under docs/ and is handed to the agent as input.
//
// Planted defects, all of the kind a generator actually produces:
//   1. StandClient imported from a package that does not exist (it lives in ru.alfa.stand.test.core)
//   2. RestStep.get(...) called with one argument instead of (alias, path)
//   3. a missing import for ScenarioResult
package ru.alfa.qa.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.client.StandClient;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.junit.StandTest;
import ru.alfa.stand.test.rest.RestStep;

@StandTest
class ShowcaseFormatGeneratedTest {

    @Test
    @DisplayName("Showcase format is returned with HTTP 200")
    void showcaseFormatIsReturned(StandClient stand) {
        Scenario scenario = Scenario.builder("showcase-format")
                .environment("ift")
                .step(RestStep.get("/showcases/formats/DEMO-001")
                        .id("fetch-format")
                        .expectStatus(200)
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
