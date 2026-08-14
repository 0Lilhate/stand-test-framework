// Example: Page Object generated for case UI-201, screen "Новая заявка".
// Derived from ../stand-test-ui-scenario-design/ui-scenario-design-template.md (filled for UI-201)
// and from the discovery report rows 1–5. Generic alias only; the base address lives behind
// base-url-ref in the consumer registry and appears nowhere here.
//
// NOTE: this file lives under docs/ as an illustration — in a real consumer project it goes to
// src/test/java/<base package>/ui/pages/ and must compile and pass checkstyle there.

package example.qa.portal.ui.pages;

import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiStep;

/**
 * Page Object for the "Новая заявка" screen of the client portal.
 *
 * <p>Locators observed on ift on 2026-08-03 (UiDiscoveryReport.md rows 1–5). Three of the five are
 * fragile — AMOUNT and EXTERNAL_ID by label, SUBMIT by role and accessible name — because the screen
 * carries no data-testid on its input controls. That is listed in the generation report, and it is the
 * evidence behind the request for test ids on this form.
 */
public final class NewApplicationPage {

    private static final String APPLICATION = "client-portal";

    private static final String PATH = "/applications/new";

    private static final UiLocator AMOUNT = UiLocator.label("Сумма");

    private static final UiLocator EXTERNAL_ID = UiLocator.label("Внешний номер");

    private static final UiLocator SUBMIT = UiLocator.role("button", "Подтвердить");

    private static final UiLocator STATUS = UiLocator.testId("application-status");

    private static final UiLocator NUMBER = UiLocator.testId("application-number");

    private NewApplicationPage() {
    }

    public static ScenarioStep open() {
        return UiStep.open(APPLICATION, PATH)
                .id("open-form")
                .injectCorrelationId()
                .build();
    }

    public static ScenarioStep expectFormIsReady() {
        return UiStep.expect(APPLICATION, AMOUNT)
                .id("form-is-ready")
                .assertVisible()
                .assertEnabled(true)
                .build();
    }

    public static ScenarioStep expectSubmitDisabled() {
        return UiStep.expect(APPLICATION, SUBMIT)
                .id("submit-disabled-when-empty")
                .assertEnabled(false)
                .build();
    }

    public static ScenarioStep fillAmount(String amount) {
        return UiStep.fill(APPLICATION, AMOUNT, amount)
                .id("fill-amount")
                .build();
    }

    public static ScenarioStep fillExternalId(String externalId) {
        return UiStep.fill(APPLICATION, EXTERNAL_ID, externalId)
                .id("fill-external-id")
                .build();
    }

    public static ScenarioStep expectAmountTyped(String amount) {
        return UiStep.expect(APPLICATION, AMOUNT)
                .id("amount-was-typed")
                .assertValue(amount)
                .build();
    }

    /** Irreversible: the application is created in the system and stays on the stand after the run. */
    public static ScenarioStep submit() {
        return UiStep.click(APPLICATION, SUBMIT)
                .id("submit")
                .build();
    }

    /**
     * Waits for the outcome and captures the number the backend check reads as ${applicationNumber}.
     *
     * <p>expectEventually rather than expect: the status is rendered after the form's own request
     * returns, and a UI check that does not wait is the commonest cause of a suite that is green
     * locally and flaky on CI.
     */
    public static ScenarioStep awaitAccepted() {
        return UiStep.expectEventually(APPLICATION, STATUS)
                .id("await-accepted")
                .assertVisible()
                .assertText("Принята")
                .capture("applicationNumber", NUMBER)
                .withinSeconds(20)
                .build();
    }

    /** The number itself is system-generated, so its shape is asserted, never an exact value. */
    public static ScenarioStep expectNumberIssued() {
        return UiStep.expect(APPLICATION, NUMBER)
                .id("number-issued")
                .assertTextMatches("AP-\\d+")
                .build();
    }
}
