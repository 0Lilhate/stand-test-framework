package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import ru.alfa.stand.test.await.Awaiter;
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
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.ui.EnvironmentUiApplicationResolver;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiRunSettings;
import ru.alfa.stand.test.ui.UiStep;
import ru.alfa.stand.test.ui.UiStepExecutor;

/**
 * The two claims of S-4.2 against real browsers: a suite run in parallel gives the same result as the same
 * suite run sequentially (BR-25), and nothing survives it.
 *
 * <p>{@link Isolated} rather than merely {@code @Tag("browser")}: the leak assertion counts this JVM's live
 * descendant processes, and a browser started by another test class at the same moment would be
 * indistinguishable from a leaked one. Isolation is what makes the count mean something — and it is the
 * honest cost of measuring a process-wide property.
 *
 * <p>The measurement is a <em>delta</em> against a baseline taken before the class's first browser, not an
 * absolute count: the Gradle worker JVM has descendants of its own, and asserting on their absence would be
 * asserting on Gradle. The baseline is taken in {@code @BeforeAll} rather than inside the leak method,
 * because a baseline captured after a sibling method has already run would contain that method's leaks and
 * filter them out — the check would then certify the very thing it was written to catch. For the same
 * reason the delta is re-checked in {@code @AfterAll}: "after the suite" means after every method, not
 * after one of them. To keep the delta from passing vacuously, a sampler records the peak while the runs
 * are in flight — a suite that started no browser at all would fail on the peak rather than pass on it.
 *
 * <p>Two families of process are counted, because they leak differently. The JVM's live <em>descendants</em>
 * catch a driver (and the browser under it) that was never closed. But Playwright's Chromium is a child of
 * the node driver, so a browser that outlives its driver is re-parented to init and leaves the descendant
 * set entirely; the second family — any process on the machine whose executable lives in a Playwright
 * directory and that did not exist at the baseline — is what catches that one.
 */
@Tag("browser")
@Isolated
class UiParallelSuiteBrowserTest {

    private static final String ENVIRONMENT = "ift";

    // A distinct alias and a distinct roster from UiLoginBrowserTest: both classes use the JVM-wide pool
    // registry, and one application must have exactly one roster in a process — sharing the alias would be
    // the very configuration UiAccountPools now refuses.
    private static final String APPLICATION = "client-portal-parallel";

    private static final int RUNS = 2;

    private static final UiLocator USER_MENU = UiLocator.testId("user-menu");

    private static final UiLoginFormConfig FORM = new UiLoginFormConfig(
            "/login", "testId=login-username", "testId=login-password", "testId=login-submit", "testId=user-menu");

    /** Live descendants of this JVM before the class started any browser. */
    private static Set<Long> descendantBaseline;

    /** Playwright-owned processes anywhere on the machine before the class started any browser. */
    private static Set<Long> playwrightBaseline;

    @TempDir
    private Path artifacts;

    private LocalUiTestApplication application;

    @BeforeAll
    static void recordBaseline() {
        descendantBaseline = descendants().keySet();
        playwrightBaseline = playwrightProcesses().keySet();
    }

    @AfterAll
    static void nothingLeakedAcrossTheWholeClass() throws InterruptedException {
        Map<Long, String> survivors = awaitTeardown();
        assertThat(survivors)
                .as("browser or driver processes outlived the class that started them: %s", survivors)
                .isEmpty();
    }

    @BeforeEach
    void startApplication() {
        this.application = new LocalUiTestApplication();
    }

    @AfterEach
    void stopApplication() {
        this.application.close();
    }

    @Test
    @DisplayName("a suite run in parallel gives the same result as the same suite run sequentially, on real browsers")
    void parallelIsTheSameAsSequentialAgainstRealBrowsers() throws Exception {
        List<ScenarioResult> sequential = runSequentially();
        List<ScenarioResult> parallel = runInParallel(new AtomicInteger());

        assertThat(sequential).allSatisfy(result -> assertThat(result.isSuccessful()).isTrue());
        assertThat(parallel).allSatisfy(result -> assertThat(result.isSuccessful()).isTrue());
        assertThat(statuses(parallel)).isEqualTo(statuses(sequential));
        assertThat(accounts(parallel))
                .as("two concurrent runs hold two different accounts — the pool is exclusive against a real stand too")
                .hasSize(RUNS);
        assertThat(this.application.signIns()).as("every run signed in for itself").hasSize(RUNS * 2);
    }

    @Test
    @DisplayName("nothing survives the suite: every browser and driver process this JVM started is gone when the runs are over")
    void noBrowserProcessOutlivesTheSuite() throws Exception {
        AtomicInteger peak = new AtomicInteger();

        List<ScenarioResult> results = runInParallel(peak);

        assertThat(results).allSatisfy(result -> assertThat(result.isSuccessful()).isTrue());
        assertThat(peak.get())
                .as("no process was ever started — the leak check would have passed without a browser, which proves nothing")
                .isGreaterThan(descendantBaseline.size());

        Map<Long, String> survivors = awaitTeardown();
        assertThat(survivors)
                .as("browser or driver processes outlived the runs that started them: %s", survivors)
                .isEmpty();
    }

    /**
     * Waits, bounded, for the descendants started by the runs to be reaped. The teardown is synchronous in
     * {@code PlaywrightUiDriver.close()}, but the operating system reaps at its own pace, and a leak test
     * that failed on scheduling would be worse than none.
     */
    private static Map<Long, String> awaitTeardown() throws InterruptedException {
        Map<Long, String> survivors = Map.of();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            survivors = new LinkedHashMap<>();
            survivors.putAll(newSince(descendantBaseline, descendants()));
            survivors.putAll(newSince(playwrightBaseline, playwrightProcesses()));
            if (survivors.isEmpty()) {
                return survivors;
            }
            Thread.sleep(250);
        }
        return survivors;
    }

    private static Map<Long, String> newSince(Set<Long> baseline, Map<Long, String> live) {
        return live.entrySet().stream()
                .filter(entry -> !baseline.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first, LinkedHashMap::new));
    }

    private static Map<Long, String> descendants() {
        Map<Long, String> live = new LinkedHashMap<>();
        ProcessHandle.current().descendants().forEach(handle -> live.put(handle.pid(), handle.info().command().orElse("<unknown>")));
        return live;
    }

    /**
     * Playwright-owned processes anywhere on the machine: the browser builds live under the Playwright
     * browsers cache, the node driver under the extracted driver bundle. Scanning beyond this JVM's subtree
     * is what makes a browser re-parented to init after its driver died still visible.
     *
     * <p>It can in principle see another user's Playwright on a shared machine. The baseline makes that
     * harmless for anything already running; a Playwright started by somebody else DURING these few seconds
     * would be a false positive, which on a CI agent does not happen and on a laptop is worth the coverage.
     */
    private static Map<Long, String> playwrightProcesses() {
        Map<Long, String> live = new LinkedHashMap<>();
        ProcessHandle.allProcesses().forEach(handle -> {
            String command = handle.info().command().orElse("");
            if (command.contains("ms-playwright") || command.contains("playwright-java")) {
                live.put(handle.pid(), command);
            }
        });
        return live;
    }

    private List<ScenarioResult> runSequentially() {
        DefaultScenarioRunner runner = runner();
        List<ScenarioResult> results = new ArrayList<>();
        for (int index = 0; index < RUNS; index++) {
            results.add(runner.run(scenario("ui-browser-sequential-" + index)));
        }
        return results;
    }

    /**
     * Runs the batch concurrently while a sampler records the peak number of live descendants, so the leak
     * assertion can prove that browsers were really started before proving that they are gone.
     */
    private List<ScenarioResult> runInParallel(AtomicInteger peak) throws Exception {
        DefaultScenarioRunner runner = runner();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                peak.accumulateAndGet(descendants().size(), Math::max);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "descendant-sampler");
        sampler.setDaemon(true);
        sampler.start();

        ExecutorService threads = Executors.newFixedThreadPool(RUNS);
        List<ScenarioResult> results = new ArrayList<>();
        try {
            List<Future<ScenarioResult>> futures = new ArrayList<>();
            for (int index = 0; index < RUNS; index++) {
                String id = "ui-browser-concurrent-" + index;
                futures.add(threads.submit(() -> runner.run(scenario(id))));
            }
            for (Future<ScenarioResult> future : futures) {
                results.add(future.get(180, TimeUnit.SECONDS));
            }
        } finally {
            threads.shutdownNow();
            sampling.set(false);
            sampler.join(2_000);
        }
        return results;
    }

    private static Scenario scenario(String id) {
        return Scenario.builder(id)
                .environment(ENVIRONMENT)
                .step(UiStep.login(APPLICATION).id("login").role("client").withinSeconds(30).accountTimeout(Duration.ofSeconds(60)).build())
                .step(UiStep.expect(APPLICATION, USER_MENU).id("check").assertVisible().assertTextContains("Signed in as").build())
                .build();
    }

    private static Set<String> statuses(List<ScenarioResult> results) {
        return results.stream().map(result -> result.status().name()).collect(Collectors.toSet());
    }

    private static Set<String> accounts(List<ScenarioResult> results) {
        return results.stream().map(result -> String.valueOf(result.stepResults().get(0).diagnostics().get("ui.login.account"))).collect(Collectors.toSet());
    }

    private DefaultScenarioRunner runner() {
        UnaryOperator<String> variables = variables();
        // Two accounts of the SAME role: two concurrent runs must each get one of their own.
        UiAuthConfig auth = new UiAuthConfig(UiAuthScheme.FORM, "CLIENT_PORTAL_ACCOUNTS", List.of("client"), null, FORM, UiLoginChallenge.NONE);
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
        return UiRunSettings.ARTIFACTS_DIRECTORY_PROPERTY.equals(property) ? this.artifacts.toString() : System.getProperty(property);
    }

    private UnaryOperator<String> variables() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("CLIENT_PORTAL_URL", this.application.baseUrl());
        values.put("CLIENT_PORTAL_ACCOUNTS", "parallel-client-1:client;parallel-client-2:client");
        values.put("PARALLEL_CLIENT_1_USERNAME", "portal.client.one");
        values.put("PARALLEL_CLIENT_1_PASSWORD", "s3cret-one-!");
        values.put("PARALLEL_CLIENT_2_USERNAME", "portal.client.two");
        values.put("PARALLEL_CLIENT_2_PASSWORD", "s3cret-two-!");
        return values::get;
    }
}
