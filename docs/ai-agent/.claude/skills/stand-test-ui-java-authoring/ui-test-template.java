// Template: JUnit 5 UI test on stand-test-ui, plain-JUnit wiring.
//
// Copy into the consumer project's test sources and replace every <placeholder>. The scenario is
// composed from Page Object factories — no UiLocator may appear in this file.
//
// Wiring: @StandTest discovers every StepExecutor through ServiceLoader, and stand-test-ui registers
// UiStepExecutor in META-INF/services, so nothing further is needed. On the Spring starter, swap the
// two annotations for @SpringBootTest + @Autowired StandClient — and that is all: since ADR-UI-008 the
// starter loads SPI-registered executors too, so no UiStepExecutor bean has to be declared.

package <consumer.base.package>.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.junit.StandTest;
import ru.alfa.stand.test.ui.UiStep;
import <consumer.base.package>.ui.pages.<Screen>Page;

/**
 * Case <case-id>: <one sentence — the behaviour this test proves>.
 *
 * <p>Design: UiScenarioDesign.md. Locators observed on <environment> on <YYYY-MM-DD>
 * (UiDiscoveryReport.md); they live in <Screen>Page and nowhere else.
 *
 * <p>Assumptions: <the recorded ones, so the next reader sees them without opening the report>.
 *
 * <p>Not covered: <what the case asked for and this SDK version cannot express — screenshots, URL
 * assertions, file upload, …>. Residual data: <what this run leaves on the stand, and why nothing
 * removes it — a browser action has no compensation in this SDK version>.
 */
@StandTest(env = "<environment>")
class <Case>UiTest {

    private static final String APPLICATION = "<application-alias>";

    @Test
    @DisplayName("<the behaviour, in the language of the case>")
    void <caseInCamelCase>(StandClient stand) {
        // Run-unique values derive from ${testRunId}; the resolver substitutes them at execution.
        String <externalId> = "<prefix>-${testRunId}";

        Scenario scenario = Scenario.builder("<scenario-id>")
                .environment("<environment>")
                .tag("ui")
                .tag("integration")
                // Sign-in first: a saved session can only be restored while the browsing context is
                // created, so a later ui.login cannot reuse one.
                .step(UiStep.login(APPLICATION)
                        .id("login")
                        .role("<role>")
                        .withinSeconds(<seconds>)
                        .build())
                .step(<Screen>Page.open())
                .step(<Screen>Page.expect<State>())
                .step(<Screen>Page.fill<Field>(<externalId>))
                .step(<Screen>Page.<action>())
                // The only sanctioned wait: bounded, polled by the SDK's Awaiter.
                .step(<Screen>Page.await<Outcome>())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
    }
}

// Negative path, when the RUN itself must fail (a validation message on the screen is an ordinary
// assertion step instead — assertEnabled(false), assertText(...) — not an expected exception):
//
//     assertThatThrownBy(() -> stand.run(scenario))
//             .isInstanceOf(StandTestAssertionError.class)
//             .hasMessageContaining("<step id or expected text>");
//
// Failure classification, so the right assertion is chosen:
//     unmet expectation, element never actionable, capture from a missing element
//                                                        -> StandTestAssertionError  (FAILED)
//     locator matched several elements, browser would not start, alias not whitelisted,
//     account pool exhausted, declared challenge with no handler
//                                                        -> StandTestException       (BROKEN)
