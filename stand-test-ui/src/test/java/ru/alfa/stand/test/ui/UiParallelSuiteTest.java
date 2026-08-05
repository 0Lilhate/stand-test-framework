package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;

/**
 * The claim BR-25 actually makes: a suite run in parallel gives the same result as the same suite run
 * sequentially.
 *
 * <p>It is proved the way the SDK is really used under JUnit parallel execution — <strong>one</strong>
 * {@code UiStepExecutor}, one driver factory and one account pool, driven from many threads at once. A test
 * that gave each run its own executor would prove nothing: the interesting state is precisely the state the
 * runs share, and the adapter's design claim is that a run keeps everything of its own (the browsing
 * session, the account lease, the variable store) in the run's {@code ResourceScope} rather than in the
 * executor.
 *
 * <p>Isolation is checked by what reached the page: each run types its own {@code ${testRunId}} into the
 * form, so a variable store shared between runs, or a session shared between runs, would show up as two
 * runs typing the same value or one run's browser receiving two.
 */
class UiParallelSuiteTest {

    private static final int RUNS = 8;

    private static final UiLocator AMOUNT = UiLocator.testId("amount");

    private static final UiLocator STATUS = UiLocator.testId("status");

    @TempDir
    private Path artifacts;

    /** Own registry, so this class can itself run concurrently with the rest of the suite. */
    private final UiAccountPools pools = new UiAccountPools();

    @Test
    @DisplayName("the same scenarios run in parallel and run sequentially give the same result — and no run sees another's data")
    void parallelIsTheSameAsSequential() throws Exception {
        Outcome sequential = runSequentially();
        Outcome parallel = runInParallel();

        // ONE comparison of everything the two batches can be compared by, so the sequential batch is the
        // reference it claims to be. Asserting each quantity against the constant RUNS on the same line — as
        // this test first did — makes the cross-batch equality unfalsifiable: both sides are pinned to the
        // same literal, and the sequential run contributes nothing.
        assertThat(parallel.shape())
                .as("a suite run in parallel must produce what the same suite produced sequentially")
                .isEqualTo(sequential.shape());
        // Separately, and only now: both batches did the work rather than agreeing on having done none.
        assertThat(parallel.succeeded).isEqualTo(RUNS);
        assertThat(parallel.sessions).isEqualTo(RUNS);
        assertThat(parallel.failures).isEmpty();
        assertThat(parallel.leaked).as("every browsing session is closed by the runner's finally, under contention too").isZero();
    }

    @Test
    @DisplayName("each run typed its own testRunId and only its own: the variable store and the browsing session belong to the run, not to the executor")
    void concurrentRunsDoNotSeeEachOthersData() throws Exception {
        Outcome parallel = runInParallel();

        assertThat(parallel.typedValues).as("every run typed a distinct value — no two runs shared a variable store").hasSize(RUNS);
        assertThat(parallel.typedValues).isEqualTo(parallel.testRunIds);
        assertThat(parallel.valuesPerSession)
                .as("no browsing session received two runs' data — a session shared between runs would show up here")
                .containsOnly(1);
    }

    @Test
    @DisplayName("as many concurrent runs as the pool has accounts: each holds a different one, and they really are in flight together")
    void concurrentRunsAtPoolSizeHoldDifferentAccounts() throws Exception {
        // A rendezvous inside the run, after the account has been leased: neither run can finish until both
        // are holding one. Without it the two runs could execute one after the other — and would then get two
        // different accounts anyway, because a returned account goes to the back of the queue, so the
        // assertion would pass while proving nothing about simultaneity.
        CyclicBarrier bothHoldAnAccount = new CyclicBarrier(2);
        Outcome parallel = runInParallel(2, driver -> driver.onNavigate(() -> await(bothHoldAnAccount)));

        assertThat(parallel.succeeded).isEqualTo(2);
        assertThat(parallel.accountsUsed)
                .as("two runs holding an account at the same moment must not be holding the same one")
                .containsExactlyInAnyOrder("portal-client-1", "portal-client-2");
    }

    /**
     * Waits at the rendezvous, once. Later navigations of the same run must not block on an already-tripped
     * barrier, and a run that arrives when the other has gone must not hang: both are failures of the test
     * harness rather than of the code, so they surface as an exception with the barrier's own diagnosis.
     */
    private static void await(CyclicBarrier barrier) {
        if (barrier.getNumberWaiting() == 0 && barrier.isBroken()) {
            return;
        }
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (BrokenBarrierException alreadyTripped) {
            // The other run has passed through: this one is free to continue.
        } catch (TimeoutException notOverlapping) {
            throw new IllegalStateException("the two runs never held an account at the same time", notOverlapping);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    @DisplayName("parallelism above the size of the account pool queues and completes — it must not fail, and it must not hang")
    void parallelismAboveThePoolSizeWaitsInsteadOfFailing() throws Exception {
        // Two accounts of role 'client', eight concurrent runs: six of them must wait for an account.
        Outcome parallel = runInParallel();

        assertThat(parallel.succeeded).as("waiting for a free account is not a failure").isEqualTo(RUNS);
        assertThat(parallel.accountsUsed)
                .as("the pool holds two client accounts and both were put to work")
                .containsExactlyInAnyOrder("portal-client-1", "portal-client-2");
        assertThat(parallel.maxAccountWaitMillis)
                .as("some run really did queue behind the two accounts — otherwise this test proves nothing about contention")
                .isPositive();
        assertThat(parallel.maxAccountWaitMillis)
                .as("and it queued for about as long as a run holds an account, not for a scheduling hiccup")
                .isGreaterThanOrEqualTo(20L);
    }

    private Outcome runSequentially() {
        FakeUiDriverFactory factory = factory();
        DefaultScenarioRunner runner = runner(factory);
        List<Attempt> attempts = new ArrayList<>();
        for (int index = 0; index < RUNS; index++) {
            attempts.add(attempt(runner, scenario("ui-parallel-sequential-" + index)));
        }
        return Outcome.of(attempts, factory);
    }

    private Outcome runInParallel() throws Exception {
        return runInParallel(RUNS);
    }

    private Outcome runInParallel(int runs) throws Exception {
        return runInParallel(runs, driver -> { });
    }

    private Outcome runInParallel(int runs, java.util.function.Consumer<FakeUiDriver> extra) throws Exception {
        FakeUiDriverFactory factory = factory(extra);
        DefaultScenarioRunner runner = runner(factory);
        ExecutorService threads = Executors.newFixedThreadPool(runs);
        List<Attempt> attempts = new ArrayList<>();
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (int index = 0; index < runs; index++) {
                String id = "ui-parallel-concurrent-" + index;
                futures.add(threads.submit(() -> attempt(runner, scenario(id))));
            }
            for (Future<Attempt> future : futures) {
                attempts.add(future.get(60, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
        }
        return Outcome.of(attempts, factory);
    }

    /**
     * Runs one scenario and RECORDS its outcome instead of letting a failure escape. The runner raises rather
     * than returning a failed result, so a batch that propagated would compare only the runs that passed —
     * and "parallel gave the same result as sequential" would be a statement no regression could contradict.
     */
    private static Attempt attempt(DefaultScenarioRunner runner, Scenario scenario) {
        try {
            return new Attempt(runner.run(scenario), null);
        } catch (RuntimeException | AssertionError failure) {
            return new Attempt(null, failure.getClass().getSimpleName());
        }
    }

    private static Scenario scenario(String id) {
        return Scenario.builder(id)
                .environment(UiTestSupport.ENVIRONMENT)
                .step(UiStep.login(UiTestSupport.APPLICATION).id("login").role("client").accountTimeout(Duration.ofSeconds(30)).build())
                .step(UiStep.open(UiTestSupport.APPLICATION, "/applications/new").id("open").build())
                .step(UiStep.fill(UiTestSupport.APPLICATION, AMOUNT, "${testRunId}").id("fill").build())
                .step(UiStep.expect(UiTestSupport.APPLICATION, STATUS).id("check").assertText("Accepted").build())
                .build();
    }

    /**
     * Every navigation costs a few milliseconds. Without it a whole scenario finishes inside one millisecond,
     * and the queueing this class exists to observe would round to zero in the diagnostics — the test would
     * pass or fail on the scheduler's mood rather than on the pool's behaviour.
     */
    private static FakeUiDriverFactory factory() {
        return factory(driver -> { });
    }

    private static FakeUiDriverFactory factory(java.util.function.Consumer<FakeUiDriver> extra) {
        return new FakeUiDriverFactory(driver -> {
            driver.signsInOn(UiLoginTestSupport.SUBMIT, UiLoginTestSupport.SIGNED_IN)
                    .costingPerNavigation(Duration.ofMillis(20))
                    .present(STATUS, "Accepted");
            extra.accept(driver);
        });
    }

    private DefaultScenarioRunner runner(FakeUiDriverFactory factory) {
        EnvironmentRegistry registry = UiTestSupport.registry(UiLoginTestSupport.application(UiAuthScheme.FORM));
        // ONE executor, shared by every thread — the shape the ServiceLoader really produces.
        UiStepExecutor executor = new UiStepExecutor(
                factory,
                new EnvironmentUiApplicationResolver(UiLoginTestSupport.variables()),
                Awaiter.create(),
                () -> UiLoginTestSupport.settings(this.artifacts),
                UiLoginTestSupport.variables(),
                this.pools);
        return new DefaultScenarioRunner(List.of(executor), new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
    }

    /** The part of an outcome two batches must agree on, whichever way they were run. */
    private record Shape(
            int succeeded,
            Set<String> failures,
            Set<String> statuses,
            int sessions,
            long leaked,
            int distinctTypedValues,
            int distinctTestRunIds,
            List<Integer> valuesPerSession,
            Set<String> accountsUsed) {
    }

    /** One run's outcome: its result, or the kind of failure that stopped it. */
    private record Attempt(ScenarioResult result, String failure) {
    }

    /** What a batch of runs produced, in a shape two batches can be compared by. */
    private record Outcome(
            int succeeded,
            Set<String> statuses,
            Set<String> failures,
            int sessions,
            long leaked,
            Set<String> typedValues,
            Set<String> testRunIds,
            List<Integer> valuesPerSession,
            Set<String> accountsUsed,
            long maxAccountWaitMillis) {

        /**
         * Everything two batches of the same scenarios must agree on. Deliberately NOT the testRunIds and not
         * the typed values themselves — those differ by construction, run to run — but their COUNT, which
         * must not: eight runs must produce eight distinct ones in either mode. The wait for an account is
         * excluded too: queueing is what parallel does and sequential does not.
         */
        private Shape shape() {
            return new Shape(
                    succeeded,
                    failures,
                    statuses,
                    sessions,
                    leaked,
                    typedValues.size(),
                    testRunIds.size(),
                    valuesPerSession.stream().sorted().toList(),
                    accountsUsed);
        }

        private static Outcome of(List<Attempt> attempts, FakeUiDriverFactory factory) {
            List<FakeUiDriver> drivers = factory.opened();
            List<ScenarioResult> results = attempts.stream().map(Attempt::result).filter(java.util.Objects::nonNull).toList();
            return new Outcome(
                    (int) results.stream().filter(ScenarioResult::isSuccessful).count(),
                    results.stream().map(result -> result.status().name()).collect(Collectors.toCollection(LinkedHashSet::new)),
                    attempts.stream().map(Attempt::failure).filter(java.util.Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new)),
                    drivers.size(),
                    drivers.stream().filter(driver -> !driver.closed()).count(),
                    drivers.stream().flatMap(driver -> filledValues(driver).stream()).collect(Collectors.toCollection(LinkedHashSet::new)),
                    results.stream().map(result -> result.testRunId().value()).collect(Collectors.toCollection(LinkedHashSet::new)),
                    drivers.stream().map(driver -> filledValues(driver).size()).toList(),
                    results.stream().map(Outcome::account).collect(Collectors.toCollection(LinkedHashSet::new)),
                    results.stream().mapToLong(Outcome::accountWaitMillis).max().orElse(-1L));
        }

        /**
         * The values typed into the amount field of one session. The sign-in's own fills are excluded: they
         * are credentials, identical for every run holding the same account, and would drown the signal.
         */
        private static Set<String> filledValues(FakeUiDriver driver) {
            String prefix = "fill:" + AMOUNT.describe() + "=";
            return driver.calls().stream()
                    .filter(call -> call.startsWith(prefix))
                    .map(call -> call.substring(prefix.length()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private static String account(ScenarioResult result) {
            return String.valueOf(result.stepResults().get(0).diagnostics().get("ui.login.account"));
        }

        private static long accountWaitMillis(ScenarioResult result) {
            return ((Number) result.stepResults().get(0).diagnostics().get("ui.account.waitMillis")).longValue();
        }
    }
}
