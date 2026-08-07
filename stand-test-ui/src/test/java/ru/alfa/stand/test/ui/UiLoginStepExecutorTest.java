package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.execution.ResourceScope;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

class UiLoginStepExecutorTest {

    private static final String CLIENT_USERNAME = "portal.client.one";

    private static final String CLIENT_PASSWORD = "s3cret-one-!";

    private static final String MANAGER_PASSWORD = "s3cret-manager-!";

    @TempDir
    private Path artifacts;

    /**
     * Every test gets its OWN pool registry rather than the JVM-wide one. That is what lets this class run
     * concurrently with the rest of the suite: a shared registry would make one test's lease another test's
     * exhausted pool, and the suite proving that parallel runs do not interfere would interfere with itself.
     */
    private final UiAccountPools pools = new UiAccountPools();

    @Test
    @DisplayName("a FORM sign-in opens the login page, types the credentials of the leased account and waits for the signed-in marker")
    void formLoginFillsTheFormAsTheLeasedAccount() {
        FakeUiDriverFactory factory = signingInFactory();
        ResourceScope scope = new ResourceScope();

        StepResult result = execute(factory, scope, UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(factory.only().calls()).containsSubsequence(
                "navigate:/login",
                "fill:" + UiLoginTestSupport.USERNAME.describe() + "=" + CLIENT_USERNAME,
                "fill:" + UiLoginTestSupport.PASSWORD.describe() + "=" + CLIENT_PASSWORD,
                "click:" + UiLoginTestSupport.SUBMIT.describe());
        assertThat(result.diagnostics())
                .containsEntry("ui.login.scheme", "FORM")
                .containsEntry("ui.login.role", "client")
                .containsEntry("ui.login.sessionReused", false)
                .containsKey("ui.account.waitMillis");
        assertThat(result.diagnostics().get("ui.login.account")).isIn("portal-client-1", "portal-client-2");
        assertThat(factory.restoredFrom()).containsExactly((Path) null);
        assertThat(factory.only().savedStorageState()).as("a FORM sign-in keeps no session on disk").isNull();
    }

    @Test
    @DisplayName("neither the login nor the password reaches the step diagnostics — the report shows the account, never its credentials")
    void credentialsNeverReachTheDiagnostics() {
        FakeUiDriverFactory factory = signingInFactory();

        StepResult result = execute(factory, new ResourceScope(), UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build());

        String rendered = result.diagnostics().toString();
        assertThat(rendered).doesNotContain(CLIENT_USERNAME, CLIENT_PASSWORD, MANAGER_PASSWORD);
        assertThat(rendered).contains("portal-client");
    }

    @Test
    @DisplayName("credentials the application rejected fail the test, not break the run, and the message names the variables rather than their values")
    void rejectedCredentialsFailTheTest() {
        // A driver that never shows the signed-in marker is an application that did not sign the account in.
        FakeUiDriverFactory factory = new FakeUiDriverFactory();
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").within(Duration.ofMillis(300)).build();

        assertThatThrownBy(() -> execute(factory, new ResourceScope(), UiAuthScheme.FORM, step))
                .isInstanceOf(StandTestAssertionError.class)
                .hasMessageContaining("did not complete")
                .hasMessageContaining("portal-client")
                .hasMessageContaining("_USERNAME")
                .hasMessageNotContaining(CLIENT_USERNAME)
                .hasMessageNotContaining(CLIENT_PASSWORD);
    }

    @Test
    @DisplayName("a failing ui.login masks the form's credential fields BEFORE the screenshot, so the typed password never reaches the artefact (UITG-S017)")
    void failingLoginMasksTheCredentialFieldsBeforeCapture() {
        // A driver that never shows the signed-in marker: the form is filled, the credentials typed, then
        // the sign-in waits and fails. The screenshot of exactly that moment must not expose the password.
        FakeUiDriverFactory factory = new FakeUiDriverFactory();
        assertThatThrownBy(() -> execute(factory, new ResourceScope(), UiAuthScheme.FORM,
                UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").within(Duration.ofMillis(300)).build()))
                .isInstanceOf(StandTestAssertionError.class);

        FakeUiDriver driver = factory.only();
        // The form's username and password fields are sensitive zones of the failing login step, so the
        // capture must mask both before taking the screenshot (SEC-05, acceptance "поле пароля закрашенным").
        assertThat(driver.maskCalls()).as("the login step's credential fields must be masked before capture").isNotEmpty();
        assertThat(driver.maskCalls().get(0)).contains(UiLoginTestSupport.USERNAME.asSensitive(), UiLoginTestSupport.PASSWORD.asSensitive());
        assertThat(driver.calls()).containsSubsequence("maskSensitive:2", "captureScreenshot");
        assertThat(driver.screenshotFile()).as("the artefact is still produced after the fields were masked").isNotNull();
    }

    @Test
    @DisplayName("a STORAGE_STATE application with no saved session signs in by form and saves the session for the next run")
    void storageStateBootstrapsItselfThroughTheForm() {
        FakeUiDriverFactory factory = signingInFactory();

        StepResult result = execute(factory, new ResourceScope(), UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        Path expected = new StorageStateStore(this.artifacts).pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");
        assertThat(result.diagnostics()).containsEntry("ui.login.sessionReused", false).containsEntry("ui.login.account", "portal-manager-1");
        assertThat(factory.only().savedStorageState()).isEqualTo(expected);
        assertThat(expected).exists();
    }

    @Test
    @DisplayName("a later run with the same account restores the saved session, finds it alive and never touches the login form")
    void validSavedSessionIsReusedWithoutSigningInAgain() {
        FakeUiDriverFactory first = signingInFactory();
        executeCompleteRun(first, UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());
        Path saved = new StorageStateStore(this.artifacts).pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");

        FakeUiDriverFactory second = new FakeUiDriverFactory(driver -> driver.present(UiLoginTestSupport.SIGNED_IN, "Signed in"));
        StepResult result = executeCompleteRun(second, UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        assertThat(second.restoredFrom()).containsExactly(saved);
        assertThat(result.diagnostics()).containsEntry("ui.login.sessionReused", true);
        assertThat(second.only().calls()).noneMatch(call -> call.startsWith("fill:"));
        assertThat(second.only().calls()).noneMatch(call -> call.startsWith("click:"));
        assertThat(saved).exists();
    }

    @Test
    @DisplayName("an expired saved session is discarded and the form is used again — the stale file does not make every later run pay for it")
    void anExpiredSavedSessionIsDiscardedAndReplaced() {
        FakeUiDriverFactory first = signingInFactory();
        executeCompleteRun(first, UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());
        Path saved = new StorageStateStore(this.artifacts).pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");
        assertThat(saved).exists();

        // The restored session shows no signed-in marker until the form is submitted: an expired session.
        FakeUiDriverFactory second = signingInFactory();
        StepResult result = executeCompleteRun(
                second,
                UiAuthScheme.STORAGE_STATE,
                UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").within(Duration.ofMillis(400)).build());

        assertThat(second.restoredFrom()).containsExactly(saved);
        assertThat(result.diagnostics()).containsEntry("ui.login.sessionReused", false);
        assertThat(second.only().calls()).anyMatch(call -> call.startsWith("fill:"));
        assertThat(second.only().savedStorageState()).isEqualTo(saved);
        assertThat(saved).as("the replacement session was written after the fallback sign-in").exists();
    }

    @Test
    @DisplayName("a corrupted saved session is deleted and not offered to the browser at all")
    void corruptedSavedSessionIsNeverRestored() throws Exception {
        StorageStateStore store = new StorageStateStore(this.artifacts);
        Path saved = store.pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");
        store.prepareFor(saved);
        java.nio.file.Files.writeString(saved, "this is not a browser session");

        FakeUiDriverFactory factory = signingInFactory();
        execute(factory, new ResourceScope(), UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        assertThat(factory.restoredFrom()).as("a file that is not a session is never handed to the browser").containsExactly((Path) null);
        assertThat(saved).as("and it was replaced by the one the fallback sign-in produced").exists();
    }

    @Test
    @DisplayName("a saved session the browser itself refuses is discarded and the run signs in anyway — the store can only check the shape of a file, so the browser is the last word on it")
    void stateFileTheBrowserRefusesIsDiscardedRatherThanBreakingEveryLaterRun() throws Exception {
        StorageStateStore store = new StorageStateStore(this.artifacts);
        Path saved = store.pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");
        store.prepareFor(saved);
        // Passes usable(): a JSON object as far as the store can tell, and truncated where it matters. This is
        // what a half-written save looks like, and nothing on the failure path used to remove it — one account
        // would break identically on every future run until somebody deleted the file by hand.
        java.nio.file.Files.writeString(saved, "{\"cookies\":[{\"name\":\"session\"");
        assertThat(store.usable(saved)).as("the premise: the store cannot tell this file is broken").isTrue();

        FakeUiDriverFactory factory = signingInFactory().refusingRestoredStateOnce(new StandTestException("Could not parse storage state"));
        StepResult result = execute(factory, new ResourceScope(), UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        assertThat(result.status()).isEqualTo(StepStatus.SUCCESS);
        assertThat(factory.restoredFrom()).as("one refused attempt with the state, then one clean session without it").containsExactly(saved, null);
        assertThat(factory.only().calls()).as("the run signed in by form instead of breaking").anyMatch(call -> call.startsWith("fill:"));
        assertThat(factory.only().savedStorageState()).as("and the unusable file was replaced by a session that works").isEqualTo(saved);
    }

    @Test
    @DisplayName("an expired session is deleted, not merely bypassed — proven where the fallback cannot rewrite it: the form is gone, so the run breaks and the file must be absent afterwards")
    void anExpiredSessionFileIsActuallyDeleted() throws Exception {
        // The state exists and is well-formed, the driver never shows the signed-in marker (expired), and the
        // application declares no form to fall back to. Nothing can rewrite the file, so its absence at the
        // end is evidence of the delete itself rather than of a later save.
        UiApplicationDefinition reuseOnly = UiLoginTestSupport.application(
                UiAuthScheme.STORAGE_STATE, UiLoginTestSupport.REUSE_ONLY_FORM, UiLoginChallenge.NONE, List.of("manager"));
        StorageStateStore store = new StorageStateStore(this.artifacts);
        Path saved = store.pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");
        store.prepareFor(saved);
        java.nio.file.Files.writeString(saved, "{\"cookies\":[],\"origins\":[]}");
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").within(Duration.ofMillis(300)).build();

        assertThatThrownBy(() -> execute(new FakeUiDriverFactory(), new ResourceScope(), reuseOnly, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("no usable saved session");

        assertThat(saved).as("a session proven dead must not make every later run pay for the same doomed attempt").doesNotExist();
    }

    @Test
    @DisplayName("a second sign-in as a different account never inherits the first one's live session, and is refused rather than saved into the wrong file")
    void secondAccountCannotInheritTheFirstOnesSession() {
        ResourceScope scope = new ResourceScope();
        EnvironmentRegistry registry = UiTestSupport.registry(UiLoginTestSupport.application(UiAuthScheme.STORAGE_STATE));
        StepExecutionContext context = UiTestSupport.context(registry, scope);
        UiStepExecutor executor = executor(signingInFactory());

        executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login-client").role("client").build(), context);

        assertThatThrownBy(() -> executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login-manager").role("manager").build(), context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("already signed in as account")
                .hasMessageContaining("both identities");
        scope.closeAll();
    }

    @Test
    // The capture raises the module logger's level for as long as it is attached — process-wide state, and
    // the only piece this class has. Declaring it keeps two captures from restoring each other's level once
    // the suite runs classes concurrently.
    @ResourceLock("ru.alfa.stand.test.ui.logger")
    @DisplayName("neither the login nor the password reaches the log, on the green path or on the failing one")
    void credentialsNeverReachTheLog() {
        try (LogCapture log = LogCapture.attached()) {
            execute(signingInFactory(), new ResourceScope(), UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build());
            assertThatThrownBy(() -> execute(
                    new FakeUiDriverFactory(),
                    new ResourceScope(),
                    UiAuthScheme.FORM,
                    UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").within(Duration.ofMillis(300)).build()))
                    .isInstanceOf(StandTestAssertionError.class);

            assertThat(log.text()).as("the log must name the account, never its credentials").doesNotContain(CLIENT_USERNAME, CLIENT_PASSWORD, MANAGER_PASSWORD);
            assertThat(log.text()).as("the capture must not be vacuous — the sign-in does log something").contains("portal-client");
        }
    }

    @Test
    @DisplayName("the sign-in diagnostics keep the page but drop its query string — a GET login form or a one-time token in the redirect must not be published with every run")
    void loginDiagnosticsDropTheQueryString() {
        FakeUiDriverFactory factory = new FakeUiDriverFactory(driver -> driver
                .signsInOn(UiLoginTestSupport.SUBMIT, UiLoginTestSupport.SIGNED_IN)
                .reportingUrl("http://localhost/portal?username=portal.client.one&password=" + CLIENT_PASSWORD));

        StepResult result = execute(factory, new ResourceScope(), UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build());

        assertThat(result.diagnostics()).containsEntry("ui.url", "http://localhost/portal");
        assertThat(result.diagnostics().toString()).doesNotContain(CLIENT_PASSWORD, CLIENT_USERNAME);
    }

    @Test
    @DisplayName("ui.fill on a locator marked sensitive gets the same leak guard as the sign-in: asSensitive() is a declaration, not decoration")
    void fillOnASensitiveLocatorCannotLeakTheTypedValue() {
        String secret = "0000-1111-2222-3333";
        UiLocator card = UiLocator.testId("card-number").asSensitive();
        FakeUiDriverFactory factory = new FakeUiDriverFactory(driver -> driver
                .failFillWith(new IllegalStateException("Playwright: could not type \"" + secret + "\" into #card-number")));
        UiApplicationDefinition application = new UiApplicationDefinition(UiTestSupport.APPLICATION, UiTestSupport.BASE_URL_REF);

        assertThatThrownBy(() -> execute(factory, new ResourceScope(), application, UiStep.fill(UiTestSupport.APPLICATION, card, secret).id("fill").build()))
                .hasMessageNotContaining(secret)
                .hasMessageContaining("withheld");
    }

    @Test
    @DisplayName("two accounts of one application keep two session files: reuse and run isolation do not contradict each other")
    void differentAccountsGetDifferentSessionFiles() {
        executeCompleteRun(signingInFactory(), UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());
        executeCompleteRun(signingInFactory(), UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build());

        StorageStateStore store = new StorageStateStore(this.artifacts);
        Path manager = store.pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-manager-1");
        Path client = store.pathFor(UiTestSupport.ENVIRONMENT, UiTestSupport.APPLICATION, "portal-client-1");

        assertThat(manager).isNotEqualTo(client);
        assertThat(manager).exists();
        assertThat(client).exists();
    }

    @Test
    @DisplayName("a STORAGE_STATE application with no login form and no usable session says what to prepare and where — the MFA case, refused honestly")
    void reuseOnlyApplicationWithoutASessionFailsClearly() {
        UiApplicationDefinition application = UiLoginTestSupport.application(
                UiAuthScheme.STORAGE_STATE, UiLoginTestSupport.REUSE_ONLY_FORM, UiLoginChallenge.NONE, List.of("manager"));
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build();

        assertThatThrownBy(() -> execute(new FakeUiDriverFactory(), new ResourceScope(), application, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("no usable saved session")
                .hasMessageContaining("portal-manager-1.json")
                .hasMessageContaining("username-locator");
    }

    @Test
    @DisplayName("a declared MFA challenge with no handler refuses before a credential is even resolved, naming the gate and the alternative")
    void declaredChallengeWithoutAHandlerIsRefused() {
        UiApplicationDefinition application = UiLoginTestSupport.application(
                UiAuthScheme.FORM, UiLoginTestSupport.FORM, UiLoginChallenge.MFA, List.of("client"));
        FakeUiDriverFactory factory = signingInFactory();
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build();

        assertThatThrownBy(() -> execute(factory, new ResourceScope(), application, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("MFA")
                .hasMessageContaining("G-1")
                .hasMessageContaining("storage-state")
                .hasMessageContaining(UiLoginChallengeHandler.class.getName());
        assertThat(factory.only().calls()).as("nothing was typed: the refusal comes before any credential is resolved").noneMatch(call -> call.startsWith("fill:"));
    }

    @Test
    @DisplayName("SSO is a spelling the registry may use and this version does not implement — a speaking refusal beats a missing enum value")
    void ssoSchemeFailsWithNotImplementedMessage() {
        UiApplicationDefinition application = UiLoginTestSupport.application(
                UiAuthScheme.SSO, UiLoginTestSupport.FORM, UiLoginChallenge.NONE, List.of("client"));
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build();

        assertThatThrownBy(() -> execute(new FakeUiDriverFactory(), new ResourceScope(), application, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("SSO")
                .hasMessageContaining("does not implement");
    }

    @Test
    @DisplayName("an application that declares no sign-in refuses the step instead of quietly doing nothing")
    void applicationWithoutSignInRefusesTheStep() {
        UiApplicationDefinition application = new UiApplicationDefinition(UiTestSupport.APPLICATION, UiTestSupport.BASE_URL_REF);
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").build();

        assertThatThrownBy(() -> execute(new FakeUiDriverFactory(), new ResourceScope(), application, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("declares no sign-in");
    }

    @Test
    @DisplayName("a browser that never starts still returns the account: in the window before the lease reaches the resource scope, the step's own finally is the only thing that can")
    void browserThatWillNotStartStillReturnsTheAccount() {
        // The account is leased BEFORE the session opens, so that an exhausted pool costs no browser. The
        // price of that order is a window in which the lease is owned by nothing the runner can see: it is not
        // yet in the ResourceScope, so closeAll cannot reach it. Only the step's finally closes it, and since
        // UiAccountPools never evicts a pool, one leak here would permanently shrink the roster for the whole
        // JVM — every later run in the suite queueing for an account nobody holds.
        FakeUiDriverFactory broken = new FakeUiDriverFactory().failingToOpenWith(new StandTestException("Could not start the 'chromium' browser"));
        ResourceScope scope = new ResourceScope();

        assertThatThrownBy(() -> execute(broken, scope, UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build()))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("browser");
        scope.closeAll();

        assertTheManagerAccountIsFree();
    }

    @Test
    @DisplayName("a sign-in refused because the session belongs to another account returns the account too — the refusal happens inside the same unregistered window")
    void refusedSignInReturnsTheAccountItHadAlreadyLeased() {
        ResourceScope scope = new ResourceScope();
        EnvironmentRegistry registry = UiTestSupport.registry(UiLoginTestSupport.application(UiAuthScheme.STORAGE_STATE));
        StepExecutionContext context = UiTestSupport.context(registry, scope);
        UiStepExecutor executor = executor(signingInFactory());
        executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login-client").role("client").build(), context);

        assertThatThrownBy(() -> executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login-manager").role("manager").build(), context))
                .isInstanceOf(StandTestException.class);

        // Checked BEFORE the scope is closed: the client lease is still held, so a free manager account can
        // only mean the refused sign-in gave its own back.
        assertTheManagerAccountIsFree();
        scope.closeAll();
    }

    /**
     * Leases the roster's only manager account from the very pool the executor used. It is exclusive, so this
     * succeeds only if whoever held it last gave it back — and it is bounded, so a leak fails the test in a
     * fifth of a second instead of hanging it.
     */
    private void assertTheManagerAccountIsFree() {
        AccountPool pool = this.pools.forApplication(
                UiTestSupport.ENVIRONMENT,
                UiTestSupport.APPLICATION,
                AccountRoster.parse(UiLoginTestSupport.ROSTER, UiTestSupport.APPLICATION, UiLoginTestSupport.POOL_REF, UiLoginTestSupport.DISCOVERY_REF));
        try (LeasedAccount free = pool.lease(UiTestSupport.APPLICATION, "manager", Duration.ofMillis(200))) {
            assertThat(free.accountId()).isEqualTo("portal-manager-1");
        }
    }

    @Test
    @DisplayName("closing the run's resource scope returns the account, so the next run gets it — the same mechanism that closes the browser")
    void closingTheScopeReturnsTheAccount() {
        ResourceScope scope = new ResourceScope();
        execute(signingInFactory(), scope, UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        // Only one manager account exists: a second run can only succeed once the first has returned it.
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").accountTimeout(Duration.ofMillis(150)).build();
        assertThatThrownBy(() -> execute(signingInFactory(), new ResourceScope(), UiAuthScheme.FORM, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("became free");

        scope.closeAll();

        StepResult afterRelease = execute(signingInFactory(), new ResourceScope(), UiAuthScheme.FORM, step);
        assertThat(afterRelease.diagnostics()).containsEntry("ui.login.account", "portal-manager-1");
    }

    @Test
    @DisplayName("signing in twice as the same role keeps the account the run already holds, instead of queueing behind the pool for it")
    void secondLoginOfTheSameRoleReusesTheLease() {
        FakeUiDriverFactory factory = signingInFactory();
        ResourceScope scope = new ResourceScope();
        EnvironmentRegistry registry = UiTestSupport.registry(UiLoginTestSupport.application(UiAuthScheme.FORM));
        StepExecutionContext context = UiTestSupport.context(registry, scope);
        UiStepExecutor executor = executor(factory);

        StepResult first = executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login-1").role("manager").build(), context);
        StepResult second = executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login-2").role("manager").accountTimeout(Duration.ofMillis(150)).build(), context);

        assertThat(second.diagnostics().get("ui.login.account")).isEqualTo(first.diagnostics().get("ui.login.account"));
    }

    @Test
    @DisplayName("an exhausted pool costs no browser at all: the account is leased before anything is started")
    void anExhaustedPoolStartsNoBrowser() {
        ResourceScope held = new ResourceScope();
        execute(signingInFactory(), held, UiAuthScheme.FORM, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        FakeUiDriverFactory factory = signingInFactory();
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").accountTimeout(Duration.ofMillis(120)).build();

        assertThatThrownBy(() -> execute(factory, new ResourceScope(), UiAuthScheme.FORM, step)).isInstanceOf(StandTestException.class);

        assertThat(factory.opened()).isEmpty();
        held.closeAll();
    }

    @Test
    @DisplayName("a sign-in that arrives after the application's session is already open cannot restore a saved one, and says so instead of silently signing in the slow way")
    void lateSignInCannotRestoreASavedSession() {
        executeCompleteRun(signingInFactory(), UiAuthScheme.STORAGE_STATE, UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build());

        FakeUiDriverFactory factory = signingInFactory();
        ResourceScope scope = new ResourceScope();
        EnvironmentRegistry registry = UiTestSupport.registry(UiLoginTestSupport.application(UiAuthScheme.STORAGE_STATE));
        StepExecutionContext context = UiTestSupport.context(registry, scope);
        UiStepExecutor executor = executor(factory);
        executor.execute(UiStep.open(UiTestSupport.APPLICATION, "/dashboard").id("open").build(), context);

        assertThatThrownBy(() -> executor.execute(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build(), context))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Move the ui.login step before");
    }

    @Test
    @DisplayName("a driver that echoes the typed credential in its own error cannot publish it: the exception is replaced, not re-thrown")
    void driverEchoingACredentialCannotLeakIt() {
        FakeUiDriverFactory factory = new FakeUiDriverFactory(driver -> driver
                .signsInOn(UiLoginTestSupport.SUBMIT, UiLoginTestSupport.SIGNED_IN)
                .failFillWith(new IllegalStateException("Timeout filling input with value \"" + CLIENT_PASSWORD + "\"")));
        ScenarioStep step = UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build();

        assertThatThrownBy(() -> execute(factory, new ResourceScope(), UiAuthScheme.FORM, step))
                .isInstanceOf(StandTestException.class)
                .hasMessageNotContaining(CLIENT_PASSWORD)
                .hasMessageContaining("withheld");
    }

    @Test
    @DisplayName("an unresolvable credential variable is a configuration failure naming the variable, never a hint of its value")
    void anUnresolvedCredentialVariableIsAConfigurationFailure() {
        FakeUiDriverFactory factory = signingInFactory();
        // Everything resolves except the manager's password.
        Map<String, String> broken = new java.util.LinkedHashMap<>();
        broken.put(UiTestSupport.BASE_URL_REF, "http://localhost:8080");
        broken.put(UiLoginTestSupport.POOL_REF, "portal-manager-1:manager");
        broken.put("PORTAL_MANAGER_1_USERNAME", "portal.manager.one");
        EnvironmentRegistry registry = UiTestSupport.registry(UiLoginTestSupport.application(UiAuthScheme.FORM));
        UiStepExecutor executor = new UiStepExecutor(
                factory,
                new EnvironmentUiApplicationResolver(broken::get),
                Awaiter.create(),
                () -> UiLoginTestSupport.settings(this.artifacts),
                broken::get,
                this.pools);

        assertThatThrownBy(() -> executor.execute(
                UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build(),
                UiTestSupport.context(registry, new ResourceScope())))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("PORTAL_MANAGER_1_PASSWORD")
                .hasMessageContaining("did not resolve");
    }

    /**
     * A complete earlier run: it executes the step and then closes its resource scope, exactly as the runner
     * does in its {@code finally}. Without the close the account stays leased — which is the pool working as
     * designed, and would make the next "run" in the same test wait out its whole timeout.
     */
    private StepResult executeCompleteRun(FakeUiDriverFactory factory, UiAuthScheme scheme, ScenarioStep step) {
        ResourceScope scope = new ResourceScope();
        try {
            return execute(factory, scope, scheme, step);
        } finally {
            scope.closeAll();
        }
    }

    private StepResult execute(FakeUiDriverFactory factory, ResourceScope scope, UiAuthScheme scheme, ScenarioStep step) {
        return execute(factory, scope, UiLoginTestSupport.application(scheme), step);
    }

    private StepResult execute(FakeUiDriverFactory factory, ResourceScope scope, UiApplicationDefinition application, ScenarioStep step) {
        return executor(factory).execute(step, UiTestSupport.context(UiTestSupport.registry(application), scope));
    }

    private UiStepExecutor executor(FakeUiDriverFactory factory) {
        return new UiStepExecutor(
                factory,
                new EnvironmentUiApplicationResolver(UiLoginTestSupport.variables()),
                Awaiter.create(),
                () -> UiLoginTestSupport.settings(this.artifacts),
                UiLoginTestSupport.variables(),
                this.pools);
    }

    private static FakeUiDriverFactory signingInFactory() {
        return new FakeUiDriverFactory(driver -> driver.signsInOn(UiLoginTestSupport.SUBMIT, UiLoginTestSupport.SIGNED_IN));
    }
}
