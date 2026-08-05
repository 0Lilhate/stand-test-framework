// Example: generated UI test for case UI-201.
// Derived from ../stand-test-ui-case-intake/example-ui-case.md through the discovery report, the
// scenario design and ../stand-test-ui-page-object-design/example-page-object.java. Generic aliases
// only; the portal's address lives behind CLIENT_PORTAL_URL in the consumer registry, never here.
//
// NOTE: this file lives under docs/ as an illustration — in a real consumer project it goes to
// src/test/java/<base package>/ui/ and must compile and pass checkstyle there.

package example.qa.portal.ui;

import static org.assertj.core.api.Assertions.assertThat;

import example.qa.portal.ui.pages.NewApplicationPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.junit.StandTest;
import ru.alfa.stand.test.rest.RestStep;
import ru.alfa.stand.test.ui.UiStep;

/**
 * Case UI-201: an application submitted by a client from the portal is accepted, is given a number,
 * and the same number is found in the applications service.
 *
 * <p>Design: UiScenarioDesign.md (`ui-application-submitted`). Locators observed on ift on
 * 2026-08-03 and owned by {@link NewApplicationPage}; none appears in this class.
 *
 * <p>UI-to-backend binding is the number read off the screen, not the correlation id: there is no
 * confirmation that the portal's back end propagates the header end to end (external gate G-6). The
 * link therefore rests on the identifier both sides show, and that limit is stated in the generation
 * report.
 *
 * <p>Assumptions: the 20 s bound comes from the case's "почти сразу" — the smallest realistic value;
 * the application number is asserted by shape (`AP-\d+`) because the system mints it.
 *
 * <p>Not covered: the submitted-application screen (reachable only through the irreversible submit,
 * which discovery may not perform), and a screenshot on failure (absent from this SDK version).
 * Residual data: every run leaves one application in status «Принята» on the stand — a browser action
 * has no compensation in this SDK version, and no delete endpoint is curated.
 */
@StandTest(env = "ift")
@EnabledIfEnvironmentVariable(named = "CLIENT_PORTAL_URL", matches = ".+")
class ApplicationSubmittedUiTest {

    private static final String APPLICATION = "client-portal";

    @Test
    @DisplayName("Заявка, поданная клиентом из портала, принимается и получает номер")
    void applicationSubmittedByClientIsAccepted(StandClient stand) {
        String externalId = "ext-${testRunId}";

        Scenario scenario = Scenario.builder("ui-application-submitted")
                .environment("ift")
                .tag("ui")
                .tag("integration")
                .tag("applications")
                .step(UiStep.login(APPLICATION)
                        .id("login")
                        .role("client")
                        .withinSeconds(30)
                        .build())
                .step(NewApplicationPage.open())
                .step(NewApplicationPage.expectFormIsReady())
                .step(NewApplicationPage.expectSubmitDisabled())
                .step(NewApplicationPage.fillAmount("100000"))
                .step(NewApplicationPage.fillExternalId(externalId))
                .step(NewApplicationPage.expectAmountTyped("100000"))
                .step(NewApplicationPage.submit())
                .step(NewApplicationPage.awaitAccepted())
                .step(NewApplicationPage.expectNumberIssued())
                // The REST path IS resolved, so ${applicationNumber} — captured off the screen — binds
                // the two halves. The expected values of assertions are NOT resolved, which is why
                // externalId is checked for presence rather than compared against "ext-${testRunId}":
                // that comparison would run against the literal string and fail.
                .step(RestStep.get("applications-service", "/api/applications/${applicationNumber}")
                        .id("check-backend")
                        .expectStatus(200)
                        .assertPath("$.status", "ACCEPTED")
                        .assertPathNotNull("$.externalId")
                        .build())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}
