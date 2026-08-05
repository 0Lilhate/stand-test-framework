package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.result.StepStatus;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

/**
 * The sign-in driven by the real {@code DefaultScenarioRunner} — validator, resource scope and all.
 *
 * <p>This is where the ownership claim of ADR-UI-006 is actually proven: the account comes back to the
 * pool through the runner's {@code finally}, on a green run and on a failed one alike, by the same
 * mechanism that closes the browser. Proving it here rather than in the executor's own test is the point —
 * the executor never releases anything, and a release path that only the executor's tests exercise would
 * be a release path that production does not have.
 */
class UiLoginScenarioIntegrationTest {

    private static final UiLocator STATUS = UiLocator.testId("status");

    @TempDir
    private Path artifacts;

    /** Own registry per test: see {@link UiLoginStepExecutorTest} — a shared one would make the suite race itself. */
    private final UiAccountPools pools = new UiAccountPools();

    @Test
    @DisplayName("a green run returns its account to the pool, so the next run gets it — through the runner's finally, not through the step")
    void accountIsReturnedOnAGreenRun() {
        FakeUiDriverFactory factory = signingInFactory();

        ScenarioResult result = runner(factory).run(Scenario.builder("ui-login-green")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(StepStatus.SUCCESS);
            assertThat(step.stepType()).isEqualTo("ui.login");
            assertThat(step.diagnostics()).containsEntry("ui.login.account", "portal-manager-1");
        });
        assertThat(factory.only().closed()).as("the browser is closed by the same finally").isTrue();
        assertThatTheOnlyManagerAccountIsFree();
    }

    @Test
    @DisplayName("a failed run returns its account too: an assertion failure after the sign-in must not consume an account for the rest of the suite")
    void accountIsReturnedOnAFailedRun() {
        FakeUiDriverFactory factory = signingInFactory();

        assertThatThrownBy(() -> runner(factory).run(Scenario.builder("ui-login-failing")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build())
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check").assertText("Accepted").build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThat(factory.only().closed()).isTrue();
        assertThatTheOnlyManagerAccountIsFree();
    }

    @Test
    @DisplayName("a run that breaks inside the sign-in returns the account as well — the lease is registered before anything can throw")
    void accountIsReturnedWhenTheSignInItselfFails() {
        // The signed-in marker never appears: the application rejected the credentials.
        FakeUiDriverFactory factory = new FakeUiDriverFactory();

        assertThatThrownBy(() -> runner(factory).run(Scenario.builder("ui-login-rejected")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").within(Duration.ofMillis(300)).build())
                .build()))
                .isInstanceOf(StandTestAssertionError.class);

        assertThatTheOnlyManagerAccountIsFree();
    }

    @Test
    @DisplayName("a sign-in that names no role on an application declaring roles is refused before the run starts — no account is leased and no browser is opened")
    void loginWithoutARoleIsRefusedPreFlight() {
        FakeUiDriverFactory factory = signingInFactory();

        assertThatThrownBy(() -> runner(factory).run(Scenario.builder("ui-login-no-role")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").build())
                .build()))
                .hasMessageContaining("UI_LOGIN_ROLE_REQUIRED");

        assertThat(factory.opened()).as("the guardrail fires before any browser or account work").isEmpty();
        assertThatTheOnlyManagerAccountIsFree();
    }

    @Test
    @DisplayName("a role the application does not declare is refused pre-flight, naming the roles that exist")
    void unknownRoleIsRefusedPreFlight() {
        FakeUiDriverFactory factory = signingInFactory();

        assertThatThrownBy(() -> runner(factory).run(Scenario.builder("ui-login-bad-role")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("auditor").build())
                .build()))
                .hasMessageContaining("UI_LOGIN_ROLE_UNKNOWN")
                .hasMessageContaining("client");

        assertThat(factory.opened()).isEmpty();
    }

    @Test
    @DisplayName("the wait for an account is bounded by the same validator that bounds every other wait")
    void accountTimeoutIsBoundedByTheValidator() {
        assertThatThrownBy(() -> runner(signingInFactory()).run(Scenario.builder("ui-login-unbounded")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").accountTimeout(Duration.ofHours(2)).build())
                .build()))
                .hasMessageContaining("accountTimeoutMillis")
                .hasMessageContaining("UNBOUNDED_TIMEOUT");
    }

    @Test
    @DisplayName("the account becomes free only after the browser is closed — the next run must not sign in as an account whose previous browser is still alive")
    void theAccountIsReturnedOnlyAfterTheBrowserIsClosed() {
        // The pool is the JVM-wide one this run will lease from: the same key, so the same instance.
        AccountPool pool = this.pools.forApplication(
                UiTestSupport.ENVIRONMENT,
                UiTestSupport.APPLICATION,
                AccountRoster.parse(UiLoginTestSupport.ROSTER, UiTestSupport.APPLICATION, UiLoginTestSupport.POOL_REF, UiLoginTestSupport.DISCOVERY_REF));
        AtomicBoolean freeWhileClosing = new AtomicBoolean();
        FakeUiDriverFactory factory = new FakeUiDriverFactory(driver -> driver
                .signsInOn(UiLoginTestSupport.SUBMIT, UiLoginTestSupport.SIGNED_IN)
                .present(STATUS, "Rejected")
                .onClose(() -> {
                    try (LeasedAccount stolen = pool.lease(UiTestSupport.APPLICATION, "manager", Duration.ofMillis(50))) {
                        freeWhileClosing.set(stolen != null);
                    } catch (RuntimeException exhausted) {
                        freeWhileClosing.set(false);
                    }
                }));

        ScenarioResult result = runner(factory).run(Scenario.builder("ui-login-close-order")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(freeWhileClosing).as("the only manager account was already back in the pool while its browser was still closing").isFalse();
        assertThatTheOnlyManagerAccountIsFree();
    }

    @Test
    @DisplayName("two runs signing in at the same time get two different accounts and two different saved sessions — reuse and isolation do not collide")
    void twoParallelRunsGetDifferentAccountsAndDifferentSessions() throws InterruptedException {
        int runs = 2;
        List<FakeUiDriverFactory> factories = List.of(signingInFactory(), signingInFactory());
        List<ScenarioResult> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(runs);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(runs);

        try {
            for (int index = 0; index < runs; index++) {
                FakeUiDriverFactory factory = factories.get(index);
                String scenarioId = "ui-login-parallel-" + index;
                threads.execute(() -> {
                    try {
                        start.await();
                        results.add(storageStateRunner(factory).run(Scenario.builder(scenarioId)
                                .environment(UiTestSupport.ENVIRONMENT)
                                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").build())
                                .build()));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }

        assertThat(results).hasSize(runs).allSatisfy(result -> assertThat(result.isSuccessful()).isTrue());
        assertThat(results).extracting(result -> result.stepResults().get(0).diagnostics().get("ui.login.account"))
                .as("the pool is exclusive: two concurrent runs cannot hold one account")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("portal-client-1", "portal-client-2");
        assertThat(factories).extracting(factory -> factory.only().savedStorageState())
                .as("session state belongs to an account, so two accounts keep two files and neither run sees the other's session")
                .doesNotHaveDuplicates();
    }

    /**
     * Proves the account is free by leasing it with a short timeout: if the previous run had not returned
     * it, the only manager account would still be held and this would fail with the exhaustion message.
     */
    private void assertThatTheOnlyManagerAccountIsFree() {
        ScenarioResult result = runner(signingInFactory()).run(Scenario.builder("ui-login-after")
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("manager").accountTimeout(Duration.ofMillis(200)).build())
                .build());

        assertThat(result.isSuccessful()).isTrue();
    }

    private DefaultScenarioRunner runner(FakeUiDriverFactory factory) {
        return runner(factory, UiAuthScheme.FORM);
    }

    private DefaultScenarioRunner storageStateRunner(FakeUiDriverFactory factory) {
        return runner(factory, UiAuthScheme.STORAGE_STATE);
    }

    private DefaultScenarioRunner runner(FakeUiDriverFactory factory, UiAuthScheme scheme) {
        UiApplicationDefinition application = UiLoginTestSupport.application(scheme);
        EnvironmentRegistry registry = UiTestSupport.registry(application);
        UiStepExecutor executor = new UiStepExecutor(
                factory,
                new EnvironmentUiApplicationResolver(UiLoginTestSupport.variables()),
                Awaiter.create(),
                () -> UiLoginTestSupport.settings(this.artifacts),
                UiLoginTestSupport.variables(),
                this.pools);
        return new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
    }

    private static FakeUiDriverFactory signingInFactory() {
        return new FakeUiDriverFactory(driver -> driver
                .signsInOn(UiLoginTestSupport.SUBMIT, UiLoginTestSupport.SIGNED_IN)
                .present(STATUS, "Rejected"));
    }
}
