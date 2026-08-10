// Template: Page Object for one screen of one UI application.
//
// Copy into the consumer project's test sources (e.g. src/test/java/<base package>/ui/pages/) and
// replace every <placeholder>. Locators come from UiDiscoveryReport.md and from nowhere else.
//
// Shape rules this template already encodes (see stand-test-ui-page-object-design/SKILL.md):
//   * final class, private constructor on two lines, no instance state — test classes run concurrently;
//   * every locator is a private static final constant — none may appear in a test body;
//   * the application alias is spelled once;
//   * factories RETURN a step; nothing here performs IO — UiStep is a lazy builder;
//   * assertions and captures only on expect / expectEventually; withinSeconds only where a step waits;
//   * no waiting helper, no branching, no Thread.sleep, no driver-level wait.

package <consumer.base.package>.ui.pages;

import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiStep;

/**
 * Page Object for the "<screen name as the user calls it>" screen of the <alias> application.
 *
 * <p>Locators observed on <environment> on <YYYY-MM-DD>; see UiDiscoveryReport.md rows <n>–<m>.
 * Fragile locators (everything except TEST_ID) are listed in the generation report.
 */
public final class <Screen>Page {

    /** The registry alias — never a URL; the base address lives behind base-url-ref. */
    private static final String APPLICATION = "<application-alias>";

    /** Relative path of the screen; an absolute address is refused by the builder. */
    private static final String PATH = "/<relative/path>";

    // --- Locators. Highest rung the screen supports: testId > role+name > label > attribute(css) > text > css.

    private static final UiLocator <ELEMENT_ONE> = UiLocator.testId("<data-testid>");

    private static final UiLocator <ELEMENT_TWO> = UiLocator.role("<aria-role>", "<accessible name>");

    private static final UiLocator <ELEMENT_THREE> = UiLocator.label("<label text>");

    /** Holds a secret or personal data: asSensitive() keeps its value out of failure messages and diagnostics. */
    private static final UiLocator <SENSITIVE_ELEMENT> = UiLocator.label("<label text>").asSensitive();

    private <Screen>Page() {
    }

    public static ScenarioStep open() {
        return UiStep.open(APPLICATION, PATH)
                .id("<open-step-id>")
                .injectCorrelationId()
                .build();
    }

    public static ScenarioStep fill<Field>(String value) {
        return UiStep.fill(APPLICATION, <ELEMENT_THREE>, value)
                .id("<fill-step-id>")
                .build();
    }

    public static ScenarioStep <action>() {
        return UiStep.click(APPLICATION, <ELEMENT_TWO>)
                .id("<click-step-id>")
                .build();
    }

    /** Checks the screen as it is now — no waiting. Use await… when the screen needs a moment. */
    public static ScenarioStep expect<State>() {
        return UiStep.expect(APPLICATION, <ELEMENT_ONE>)
                .id("<expect-step-id>")
                .assertVisible()
                .assertEnabled(true)
                .build();
    }

    /**
     * Polls until every assertion holds or the bounded timeout expires — the only sanctioned way to
     * wait for a UI. The captured value is visible to every later step of any adapter as ${var}.
     */
    public static ScenarioStep await<Outcome>() {
        return UiStep.expectEventually(APPLICATION, <ELEMENT_ONE>)
                .id("<await-step-id>")
                .assertVisible()
                .assertText("<exact text observed on the screen>")
                .capture("<variableName>", <ELEMENT_ONE>)
                .withinSeconds(<seconds>)
                .build();
    }
}

// Reference — what a builder accepts, so nothing outside it is written:
//
//   step types      ui.open · ui.click · ui.fill · ui.expect · ui.expectEventually · ui.login
//   assertions      assertVisible() · assertVisible(boolean) · assertEnabled(boolean)
//                   assertText · assertTextContains · assertTextMatches · assertValue
//                   assertAttribute(name, expected) · assertProperty(UiProperty, AssertionMatcher, expected)
//   captures        capture(var) · capture(var, from) · capture(var, from, UiCaptureSource)
//                   captureAttribute(var, from, attribute)
//   waits           within(Duration) · withinSeconds(long)   [expectEventually / login]
//                   pollInterval(Duration)                   [expectEventually ONLY — ui.login throws at build()]
//   login only      role(String) · accountTimeout(Duration)
//   correlation     injectCorrelationId() · injectCorrelationId(boolean)
//
//   matcher asymmetry: TEXT / VALUE / ATTRIBUTE take all five matchers;
//                      VISIBLE / ENABLED take EQUALS only (enforced at build() and at execution).
//
//   absent by design: XPath; a screenshot or trace ON DEMAND; URL/title/console assertions;
//                     select/hover/press/upload/drag-drop/back/tabs/iframe; network interception.
//   automatic, not authored: a failing step attaches a screenshot (asSensitive() zones masked first),
//                     the console, the network story and — where the registry says trace: on-failure —
//                     a Playwright trace. Nothing here orders them; a green step leaves nothing.
