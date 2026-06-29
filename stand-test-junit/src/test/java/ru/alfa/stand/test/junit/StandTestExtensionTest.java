package ru.alfa.stand.test.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
}
