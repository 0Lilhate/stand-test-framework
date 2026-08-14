package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * The extension point of gate G-1, exercised rather than described.
 *
 * <p>{@code UiLoginStepExecutorTest} proves the half the SDK owns: an application declaring a challenge with
 * no handler is refused, before a credential is resolved, with a message naming the gate. This class proves
 * the other half — that the escape hatch offered in that message actually works. Without it the SPI is a
 * claim: a service file read under the wrong name, a handler found and never invoked, or a request handed a
 * budget of zero would all leave the suite green while the one documented way past a second factor was
 * broken for the first team to try it.
 *
 * <p>The handler is {@link RecordingChallengeHandler}, registered for the test classpath only. Its static
 * recorder is safe under this module's parallelism: methods of one class run on one thread, and no other
 * test declares {@link UiLoginChallenge#CAPTCHA}, which is the only challenge it supports.
 */
class UiLoginChallengeHandlerTest {

    private static final String CLIENT_USERNAME = "portal.client.one";

    private static final String CLIENT_PASSWORD = "s3cret-one-!";

    /** Long enough that "what is left of it" is unambiguously smaller, short enough to keep the suite quick. */
    private static final Duration STEP_TIMEOUT = Duration.ofSeconds(5);

    /** Makes the sign-in cost measurable time, so the remaining budget is provably less than the whole. */
    private static final Duration NAVIGATION_COST = Duration.ofMillis(60);

    @TempDir
    private Path artifacts;

    private final UiAccountPools pools = new UiAccountPools();

    @BeforeEach
    void forgetEarlierRequests() {
        RecordingChallengeHandler.forget();
    }

    @Test
    @DisplayName("a handler registered on the classpath is found by the ServiceLoader, invoked after the form is submitted, and its action is what completes the sign-in")
    void registeredHandlerIsFoundAndCompletesTheSignIn() {
        // The driver signs in on the handler's control and NOT on the form's submit button: if the handler is
        // never invoked, the signed-in marker never appears and the step fails.
        FakeUiDriverFactory factory = challengedFactory();

        StepResult result = execute(factory, UiLoginChallenge.CAPTCHA, step());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(RecordingChallengeHandler.requests()).hasSize(1);
        UiLoginChallengeRequest request = RecordingChallengeHandler.requests().get(0);
        assertThat(request.challenge()).isEqualTo(UiLoginChallenge.CAPTCHA);
        assertThat(request.application()).isEqualTo(UiTestSupport.APPLICATION);
        assertThat(request.role()).isEqualTo("client");
        assertThat(request.accountId()).startsWith("portal-client");
        assertThat(factory.only().calls()).as("the challenge is answered after the form was submitted, not instead of it").containsSubsequence(
                "fill:" + UiLoginTestSupport.USERNAME.describe() + "=" + CLIENT_USERNAME,
                "click:" + UiLoginTestSupport.SUBMIT.describe(),
                "click:" + RecordingChallengeHandler.PASSED.describe());
    }

    @Test
    @DisplayName("the handler is given the account's identity and no credential — neither in the request it receives nor in the shape of the type")
    void theHandlerNeverReceivesACredential() {
        execute(challengedFactory(), UiLoginChallenge.CAPTCHA, step());

        UiLoginChallengeRequest request = RecordingChallengeHandler.requests().get(0);
        assertThat(request.toString()).contains("portal-client").doesNotContain(CLIENT_USERNAME, CLIENT_PASSWORD);
        // Structural, not incidental: a component named for a credential would fail this the day it is added,
        // which is the point at which "handlers do not see passwords" would otherwise quietly stop being true.
        assertThat(Arrays.stream(UiLoginChallengeRequest.class.getRecordComponents()).map(RecordComponent::getName))
                .as("the request type must have no credential-shaped component at all")
                .doesNotContain("username", "password", "credential", "credentials", "secret", "token");
    }

    @Test
    @DisplayName("the handler runs inside the step's budget: it is handed what is LEFT of it, not a fresh copy of the whole")
    void theHandlerGetsWhatIsLeftOfTheStepBudget() {
        execute(challengedFactory(), UiLoginChallenge.CAPTCHA, UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").within(STEP_TIMEOUT).build());

        Duration granted = RecordingChallengeHandler.requests().get(0).timeout();
        assertThat(granted).as("a handler given the whole timeout again would let one step run for twice what its author declared").isLessThan(STEP_TIMEOUT);
        assertThat(granted).as("and it must still be usable — a handler handed zero would fail on arithmetic, not on the screen").isPositive();
    }

    @Test
    @DisplayName("a handler that does not support the declared challenge is skipped, and the sign-in is refused as if none were registered — selection goes through supports(), not 'first provider wins'")
    void handlerThatDoesNotSupportTheChallengeIsSkipped() {
        FakeUiDriverFactory factory = challengedFactory();
        // MFA, which RecordingChallengeHandler does not support, while it sits on the very same classpath.
        assertThatThrownBy(() -> execute(factory, UiLoginChallenge.MFA, step()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("MFA")
                .hasMessageContaining("G-1")
                .hasMessageContaining("storage-state");
        assertThat(RecordingChallengeHandler.requests()).as("the unsupporting handler must not have been called").isEmpty();
        assertThat(factory.only().calls()).as("and the refusal still precedes any credential").noneMatch(call -> call.startsWith("fill:"));
    }

    @Test
    @DisplayName("the SDK itself registers no challenge handler: the only provider on this classpath is the test's own")
    void theSdkShipsNoChallengeHandlerOfItsOwn() {
        List<UiLoginChallengeHandler> discovered = new java.util.ArrayList<>();
        ServiceLoader.load(UiLoginChallengeHandler.class, getClass().getClassLoader()).forEach(discovered::add);

        // stand-test-ui's own main resources are on this classpath too, so a bypass shipped with the SDK —
        // the thing gate G-1 exists to prevent — would show up here as a second provider.
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0)).isInstanceOf(RecordingChallengeHandler.class);
    }

    private ScenarioStep step() {
        return UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").within(STEP_TIMEOUT).build();
    }

    private FakeUiDriverFactory challengedFactory() {
        return new FakeUiDriverFactory(driver -> driver
                .signsInOn(RecordingChallengeHandler.PASSED, UiLoginTestSupport.SIGNED_IN)
                .costingPerNavigation(NAVIGATION_COST));
    }

    private StepResult execute(FakeUiDriverFactory factory, UiLoginChallenge challenge, ScenarioStep step) {
        UiApplicationDefinition application = UiLoginTestSupport.application(
                UiAuthScheme.FORM, UiLoginTestSupport.FORM, challenge, List.of("client"));
        UiStepExecutor executor = new UiStepExecutor(
                factory,
                new EnvironmentUiApplicationResolver(UiLoginTestSupport.variables()),
                Awaiter.create(),
                () -> UiLoginTestSupport.settings(this.artifacts),
                UiLoginTestSupport.variables(),
                this.pools);
        ResourceScope scope = new ResourceScope();
        try {
            return executor.execute(step, UiTestSupport.context(UiTestSupport.registry(application), scope));
        } finally {
            scope.closeAll();
        }
    }
}
