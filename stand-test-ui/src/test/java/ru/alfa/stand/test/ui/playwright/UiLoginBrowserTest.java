package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.ui.EnvironmentUiApplicationResolver;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiRunSettings;
import ru.alfa.stand.test.ui.UiStep;
import ru.alfa.stand.test.ui.UiStepExecutor;

/**
 * The sign-in against a real Chromium and an application that really issues a session cookie.
 *
 * <p>The unit tests prove the machinery — leasing, keying, discarding — against a fake driver. What only a
 * browser can prove is that the thing the SDK saves and restores <em>is</em> a session: that a restored
 * context is already signed in, that a context restored from a stale file is not, and that two contexts
 * carrying two accounts' cookies see two different users. Tagged {@code browser}.
 */
@Tag("browser")
class UiLoginBrowserTest {

    private static final String ENVIRONMENT = "ift";

    private static final String APPLICATION = "client-portal";

    private static final String POOL_REF = "CLIENT_PORTAL_ACCOUNTS";

    private static final UiLocator USER_MENU = UiLocator.testId("user-menu");

    private static final UiLocator LOGIN_TITLE = UiLocator.testId("login-title");

    private static final UiLoginFormConfig FORM = new UiLoginFormConfig(
            "/login", "testId=login-username", "testId=login-password", "testId=login-submit", "testId=user-menu");

    @TempDir
    private Path artifacts;

    private LocalUiTestApplication application;

    @BeforeEach
    void startApplication() {
        this.application = new LocalUiTestApplication();
    }

    @AfterEach
    void stopApplication() {
        this.application.close();
    }

    @Test
    @DisplayName("a FORM sign-in really signs in: the form is filled, the application issues a session and the signed-in marker is on the screen")
    void formLoginSignsInAgainstARealBrowser() {
        ScenarioResult result = runner(UiAuthScheme.FORM).run(Scenario.builder("ui-login-form")
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("client").withinSeconds(20).build())
                .step(UiStep.expect(APPLICATION, USER_MENU).id("check").assertVisible().assertTextContains("Signed in as").build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(this.application.signIns()).containsExactly("portal.client.one");
    }

    @Test
    @DisplayName("wrong credentials are a statement about the screen: the run fails as an assertion, not as broken infrastructure")
    void wrongCredentialsFailTheTest() {
        UnaryOperator<String> wrongPassword = variables(Map.of("PORTAL_CLIENT_1_PASSWORD", "definitely-not-it"));

        assertThatThrownBy(() -> runner(UiAuthScheme.FORM, wrongPassword).run(Scenario.builder("ui-login-rejected")
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("client").within(Duration.ofSeconds(3)).build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class)
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("definitely-not-it");
        assertThat(this.application.signIns()).isEmpty();
    }

    @Test
    @DisplayName("a saved session is a real session: the second run restores it, is already signed in and never sees the login form")
    void savedSessionIsRestoredAndTheFormIsNotShownAgain() {
        ScenarioResult first = runner(UiAuthScheme.STORAGE_STATE).run(signInScenario("ui-login-save"));
        assertThat(first.isSuccessful()).isTrue();
        assertThat(this.application.signIns()).hasSize(1);
        int loginPageViewsAfterFirstRun = this.application.loginPageViews();

        ScenarioResult second = runner(UiAuthScheme.STORAGE_STATE).run(signInScenario("ui-login-reuse"));

        assertThat(second.isSuccessful()).isTrue();
        assertThat(second.stepResults().get(0).diagnostics()).containsEntry("ui.login.sessionReused", true);
        assertThat(this.application.signIns()).as("the form was not filled a second time").hasSize(1);
        assertThat(this.application.loginPageViews()).as("the login page was not even opened").isEqualTo(loginPageViewsAfterFirstRun);
    }

    @Test
    @DisplayName("an expired session falls back to the form and replaces the saved file — the case ADR-UI-006 names as the likely one")
    void anExpiredSessionFallsBackToTheForm() throws IOException {
        assertThat(runner(UiAuthScheme.STORAGE_STATE).run(signInScenario("ui-login-save")).isSuccessful()).isTrue();
        Path saved = this.artifacts.resolve("storage-state").resolve(ENVIRONMENT).resolve(APPLICATION).resolve("portal-client-1.json");
        assertThat(saved).exists();
        // A well-formed state whose cookie the application no longer accepts: exactly an expired session.
        Files.writeString(saved, expiredSession(), StandardCharsets.UTF_8);

        ScenarioResult result = runner(UiAuthScheme.STORAGE_STATE).run(Scenario.builder("ui-login-expired")
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("client").within(Duration.ofSeconds(10)).build())
                .step(UiStep.expect(APPLICATION, USER_MENU).id("check").assertVisible().build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults().get(0).diagnostics()).containsEntry("ui.login.sessionReused", false);
        assertThat(this.application.signIns()).as("the form was used again after the session turned out to be dead").hasSize(2);
        assertThat(Files.readString(saved, StandardCharsets.UTF_8)).as("and the dead session was replaced").doesNotContain("expired-session");
    }

    @Test
    @DisplayName("a fresh context is signed out: the application redirects to its login form, which is what makes the signed-in marker evidence of anything")
    void freshContextIsSignedOutAndRedirectsToTheLoginForm() {
        ScenarioResult result = runner(UiAuthScheme.FORM).run(Scenario.builder("ui-signed-out")
                .environment(ENVIRONMENT)
                .step(UiStep.open(APPLICATION, "/").id("open").build())
                .step(UiStep.expect(APPLICATION, LOGIN_TITLE).id("check").assertVisible().build())
                .build());

        assertThat(result.isSuccessful()).as("an unauthenticated request is redirected to the login form").isTrue();
        assertThat(this.application.signIns()).isEmpty();
    }

    @Test
    @DisplayName("two runs holding two accounts see two different users: the saved sessions belong to accounts, not to the suite")
    void twoAccountsDoNotSeeEachOthersSession() {
        ScenarioResult firstAccount = runner(UiAuthScheme.STORAGE_STATE).run(Scenario.builder("ui-login-account-1")
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("client").withinSeconds(20).build())
                .step(UiStep.expect(APPLICATION, USER_MENU).id("check").assertText("Signed in as portal.client.one").build())
                .build());
        ScenarioResult secondAccount = runner(UiAuthScheme.STORAGE_STATE).run(Scenario.builder("ui-login-account-2")
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("manager").withinSeconds(20).build())
                .step(UiStep.expect(APPLICATION, USER_MENU).id("check").assertText("Signed in as portal.client.two").build())
                .build());

        assertThat(firstAccount.isSuccessful()).isTrue();
        assertThat(secondAccount.isSuccessful()).isTrue();
        assertThat(this.artifacts.resolve("storage-state").resolve(ENVIRONMENT).resolve(APPLICATION).resolve("portal-client-1.json")).exists();
        assertThat(this.artifacts.resolve("storage-state").resolve(ENVIRONMENT).resolve(APPLICATION).resolve("portal-client-2.json")).exists();
        assertThat(this.application.signIns()).containsExactly("portal.client.one", "portal.client.two");
    }

    private static Scenario signInScenario(String id) {
        return Scenario.builder(id)
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("client").withinSeconds(20).build())
                .step(UiStep.expect(APPLICATION, USER_MENU).id("check").assertVisible().build())
                .build();
    }

    /** A well-formed storage state carrying a session cookie the application will not accept. */
    private static String expiredSession() {
        return "{\"cookies\":[{\"name\":\"portal_session\",\"value\":\"expired-session\",\"domain\":\"127.0.0.1\",\"path\":\"/\",\"expires\":-1,\"httpOnly\":false,\"secure\":false,\"sameSite\":\"Lax\"}],\"origins\":[]}";
    }

    private DefaultScenarioRunner runner(UiAuthScheme scheme) {
        return runner(scheme, variables(Map.of()));
    }

    private DefaultScenarioRunner runner(UiAuthScheme scheme, UnaryOperator<String> variables) {
        UiAuthConfig auth = new UiAuthConfig(scheme, POOL_REF, List.of("client", "manager"), null, FORM, UiLoginChallenge.NONE);
        UiApplicationDefinition portal = new UiApplicationDefinition(APPLICATION, "CLIENT_PORTAL_URL", null, Map.of(), UiTraceMode.OFF, auth);
        EnvironmentDefinition environment = new EnvironmentDefinition(
                ENVIRONMENT, Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of(APPLICATION, portal));
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
        UiStepExecutor executor = new UiStepExecutor(
                new PlaywrightDriverFactory(),
                new EnvironmentUiApplicationResolver(variables),
                Awaiter.create(),
                () -> UiRunSettings.fromProperties(this::settingsProperty),
                variables);
        return new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
    }

    private String settingsProperty(String property) {
        if (UiRunSettings.ARTIFACTS_DIRECTORY_PROPERTY.equals(property)) {
            return this.artifacts.toString();
        }
        if (UiRunSettings.ACTION_TIMEOUT_PROPERTY.equals(property)) {
            // Keeps the "is this restored session still alive?" probe short, so an expired one is found in
            // seconds and the form fallback still fits inside the step's budget.
            return "3000";
        }
        return System.getProperty(property);
    }

    /**
     * The environment the sign-in resolves: the base URL, the roster and the credentials. The overrides let
     * a test break exactly one of them — a wrong password, say — without restating the rest.
     */
    private UnaryOperator<String> variables(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("CLIENT_PORTAL_URL", this.application.baseUrl());
        values.put(POOL_REF, "portal-client-1:client;portal-client-2:manager");
        values.put("PORTAL_CLIENT_1_USERNAME", "portal.client.one");
        values.put("PORTAL_CLIENT_1_PASSWORD", "s3cret-one-!");
        values.put("PORTAL_CLIENT_2_USERNAME", "portal.client.two");
        values.put("PORTAL_CLIENT_2_PASSWORD", "s3cret-two-!");
        values.putAll(overrides);
        return values::get;
    }
}
