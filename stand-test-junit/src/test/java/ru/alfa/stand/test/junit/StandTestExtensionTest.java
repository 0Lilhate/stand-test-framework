package ru.alfa.stand.test.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.platform.testkit.engine.EngineTestKit;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.exception.StandTestAssertionError;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

class StandTestExtensionTest {

    @Test
    @DisplayName("a passing scenario runs to success and the StandClient is injected")
    void passingScenario_succeeds() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(PassingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
    }

    @Test
    @DisplayName("a failing step surfaces as a StandTestAssertionError test failure")
    void failingScenario_failsWithAssertionError() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(FailingFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(StandTestAssertionError.class)));
    }

    @Test
    @DisplayName("an unknown step type surfaces as a StandTestException test error")
    void unknownStep_failsWithInfraError() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(InfraFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(StandTestException.class)));
    }

    @Test
    @DisplayName("an Awaiter parameter is injected")
    void awaiterParameter_isInjected() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(AwaiterFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("an unsupported parameter type is left to JUnit (ParameterResolutionException)")
    void unsupportedParameter_isLeftToJunit() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(UnsupportedParamFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("the StandClient is built once and cached for the engine run")
    void standClient_isCachedPerEngineRun() {
        CachingFixture.captured.clear();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(CachingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(2).succeeded(2));

        assertThat(CachingFixture.captured).hasSize(2);
        assertThat(CachingFixture.captured.get(0)).isSameAs(CachingFixture.captured.get(1));
    }

    @Test
    @DisplayName("a @StandScenarioId String parameter is injected from the class declaration")
    void scenarioId_injectedFromClass() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(StandScenarioIdFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("a method-level @StandScenarioId overrides the class-level one")
    void scenarioId_methodOverridesClass() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(StandScenarioIdOverrideFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("a missing @StandScenarioId fails resolution with a ParameterResolutionException")
    void scenarioId_missing_failsResolution() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MissingStandScenarioIdFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("a @StandEnv String parameter is injected from the class declaration")
    void environment_injectedFromStandEnv() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(EnvFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("the environment falls back to @StandTest(env) when no @StandEnv is present")
    void environment_fallsBackToStandTestEnv() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(EnvFromStandTestFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("a missing environment fails resolution with a ParameterResolutionException")
    void environment_missing_failsResolution() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(MissingEnvFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("a scenario is built and run from the injected @StandScenarioId and @StandEnv")
    void fullFlow_runsFromInjectedMetadata() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(FullFlowFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("@StandEnv takes precedence over the @StandTest(env) fallback")
    void environment_standEnvOverridesStandTestEnv() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(EnvPrecedenceFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("a value on the parameter annotation overrides method and class declarations")
    void parameterValue_overridesMethodAndClass() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ParameterValueOverrideFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("a parameter carrying both @StandScenarioId and @StandEnv fails resolution")
    void bothAnnotations_failResolution() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(BothAnnotationsFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("@StandScenarioId/@StandEnv on a non-String parameter fails resolution")
    void nonStringAnnotatedParameter_failsResolution() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(NonStringParamFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("the misuse check wins over the injectable types: @StandEnv on a StandClient (or @StandScenarioId on an Awaiter) fails instead of quietly injecting one")
    void annotatedInjectableType_failsResolution() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(AnnotatedInjectableTypeFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(2, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("@StandTest(env) does not leak into @StandScenarioId resolution")
    void standTestEnv_doesNotLeakIntoStandScenarioId() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(StandScenarioIdNoFallbackFixture.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(1, finishedWithFailure(instanceOf(ParameterResolutionException.class)));
    }

    @Test
    @DisplayName("a @Nested test inherits class-level @StandScenarioId/@StandEnv from the enclosing class")
    void nestedTest_inheritsEnclosingClassDeclarations() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(NestedEnclosingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));
    }

    @Test
    @DisplayName("a ReportingEventPublisher discovered via ServiceLoader receives the run's events")
    void reportingPublisher_isDiscoveredAndReceivesEvents() {
        CountingReportingEventPublisher.reset();

        EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(ReportingFixture.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1));

        // The single-step fake.ok run publishes exactly four lifecycle events — ScenarioEvent
        // STARTED/FINISHED plus the step's StepEvent STARTED/FINISHED — so an exact count (rather than
        // just > 0) also catches a dropped or duplicated event, not only a wholesale missing publisher.
        assertThat(CountingReportingEventPublisher.published()).isEqualTo(4);
    }

    private static Scenario scenario(String type) {
        return Scenario.builder("fixture").environment("ift").step(GenericStep.of("s1", type)).build();
    }

    @StandTest
    @Tag("standtest-fixture")
    static class PassingFixture {

        @Test
        void passes(StandClient stand) {
            assertThat(stand).isNotNull();
            stand.run(scenario("fake.ok"));
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class FailingFixture {

        @Test
        void fails(StandClient stand) {
            stand.run(scenario("fake.fail"));
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class InfraFixture {

        @Test
        void infra(StandClient stand) {
            stand.run(scenario("fake.unknown"));
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class AwaiterFixture {

        @Test
        void awaiter(Awaiter awaiter) {
            assertThat(awaiter).isNotNull();
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class UnsupportedParamFixture {

        @Test
        void unsupported(String unsupported) {
            assertThat(unsupported).isNull();
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class CachingFixture {

        static final List<StandClient> captured = new ArrayList<>();

        @Test
        void first(StandClient stand) {
            captured.add(stand);
        }

        @Test
        void second(StandClient stand) {
            captured.add(stand);
        }
    }

    @StandTest
    @StandScenarioId("example-flow")
    @Tag("standtest-fixture")
    static class StandScenarioIdFixture {

        @Test
        void hasId(@StandScenarioId String id) {
            assertThat(id).isEqualTo("example-flow");
        }
    }

    @StandTest
    @StandScenarioId("class-level")
    @Tag("standtest-fixture")
    static class StandScenarioIdOverrideFixture {

        @Test
        @StandScenarioId("method-level")
        void hasId(@StandScenarioId String id) {
            assertThat(id).isEqualTo("method-level");
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class MissingStandScenarioIdFixture {

        @Test
        void hasId(@StandScenarioId String id) {
            assertThat(id).isNull();
        }
    }

    @StandTest
    @StandEnv("ift")
    @Tag("standtest-fixture")
    static class EnvFixture {

        @Test
        void hasEnv(@StandEnv String env) {
            assertThat(env).isEqualTo("ift");
        }
    }

    @StandTest(env = "stage")
    @Tag("standtest-fixture")
    static class EnvFromStandTestFixture {

        @Test
        void hasEnv(@StandEnv String env) {
            assertThat(env).isEqualTo("stage");
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class MissingEnvFixture {

        @Test
        void hasEnv(@StandEnv String env) {
            assertThat(env).isNull();
        }
    }

    @StandTest
    @StandEnv("ift")
    @StandScenarioId("example-flow")
    @Tag("standtest-fixture")
    static class FullFlowFixture {

        @Test
        void flow(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
            stand.run(Scenario.builder(id).environment(env).step(GenericStep.of("s1", "fake.ok")).build());
        }
    }

    @StandTest(env = "stage")
    @StandEnv("ift")
    @Tag("standtest-fixture")
    static class EnvPrecedenceFixture {

        @Test
        void standEnvWins(@StandEnv String env) {
            assertThat(env).isEqualTo("ift");
        }
    }

    @StandTest
    @StandScenarioId("class-id")
    @StandEnv("class-env")
    @Tag("standtest-fixture")
    static class ParameterValueOverrideFixture {

        @Test
        void parameterValuesWin(@StandScenarioId("param-id") String id, @StandEnv("param-env") String env) {
            assertThat(id).isEqualTo("param-id");
            assertThat(env).isEqualTo("param-env");
        }
    }

    @StandTest
    @StandScenarioId("example-flow")
    @Tag("standtest-fixture")
    static class BothAnnotationsFixture {

        @Test
        void rejectsBoth(@StandScenarioId @StandEnv String x) {
            assertThat(x).isNull();
        }
    }

    @StandTest
    @StandEnv("ift")
    @Tag("standtest-fixture")
    static class NonStringParamFixture {

        @Test
        void rejectsNonString(@StandEnv int env) {
            assertThat(env).isZero();
        }
    }

    @StandTest
    @StandEnv("ift")
    @Tag("standtest-fixture")
    static class AnnotatedInjectableTypeFixture {

        @Test
        void rejectsAnnotatedStandClient(@StandEnv StandClient stand) {
            assertThat(stand).isNull();
        }

        @Test
        void rejectsAnnotatedAwaiter(@StandScenarioId Awaiter awaiter) {
            assertThat(awaiter).isNull();
        }
    }

    @StandTest(env = "stage")
    @Tag("standtest-fixture")
    static class StandScenarioIdNoFallbackFixture {

        @Test
        void envDoesNotLeakToId(@StandScenarioId String id) {
            assertThat(id).isNull();
        }
    }

    @StandTest
    @Tag("standtest-fixture")
    static class ReportingFixture {

        @Test
        void publishesEvents(StandClient stand) {
            stand.run(scenario("fake.ok"));
        }
    }

    @StandTest
    @StandScenarioId("outer-id")
    @StandEnv("outer-env")
    @Tag("standtest-fixture")
    static class NestedEnclosingFixture {

        @Nested
        class Inner {

            @Test
            void inheritsFromEnclosingClass(@StandScenarioId String id, @StandEnv String env) {
                assertThat(id).isEqualTo("outer-id");
                assertThat(env).isEqualTo("outer-env");
            }
        }
    }
}
