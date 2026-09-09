package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.execution.StepExecutionContext;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.StepResult;
import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * The merge rules of ADR-UI-008, exercised through the seam rather than through a real
 * {@code META-INF/services} file.
 *
 * <p>Registering a fake provider for real would have applied it to every other test in this module —
 * the rules are what need proving here, not the {@link java.util.ServiceLoader} call itself. The
 * companion test that ran the production path with the real UI adapter on one classpath
 * ({@code StarterDiscoversUiExecutorTest}) lived in {@code stand-test-example} and went with that
 * module, so the composition itself is currently unproven — the repository has already been bitten by a
 * chain whose every link was tested and whose composition had never run.
 */
class StepExecutorDiscoveryTest {

    private Logger discoveryLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheDiscoveryLogger() {
        discoveryLogger = (Logger) LoggerFactory.getLogger(StepExecutorDiscovery.class);
        appender = new ListAppender<>();
        appender.start();
        discoveryLogger.setLevel(Level.DEBUG);
        discoveryLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheDiscoveryLogger() {
        discoveryLogger.detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> atLevel(Level level) {
        return appender.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    @Test
    @DisplayName("an executor with no bean of its own is discovered and reaches the runner's list")
    void spiOnlyExecutor_isDiscovered() {
        List<StepExecutor> merged = StepExecutorDiscovery.merge(List.of(new DeclaredRest()), List.of(new DiscoveredUi()));

        assertThat(merged).hasSize(2);
        assertThat(merged.stream().anyMatch(executor -> executor.supports("ui.open")))
                .as("this is the whole point: stand-test-ui ships only an SPI registration")
                .isTrue();
    }

    @Test
    @DisplayName("a declared bean wins over an SPI provider claiming the same step type")
    void declaredBean_winsOverTheSpiProvider() {
        ConsumersOwnRest configured = new ConsumersOwnRest();

        List<StepExecutor> merged = StepExecutorDiscovery.merge(List.of(configured), List.of(new DeclaredRest()));

        StepExecutor first = merged.stream().filter(executor -> executor.supports("rest.get")).findFirst().orElseThrow();
        assertThat(first)
                .as("the runner takes the FIRST executor that supports a type, so ordering is what stops a configured bean being silently replaced by an SPI default")
                .isSameAs(configured);
    }

    @Test
    @DisplayName("the same executor class arriving as both a bean and an SPI provider yields one entry")
    void sameClassFromBothSources_isNotDuplicated() {
        DeclaredRest bean = new DeclaredRest();

        List<StepExecutor> merged = StepExecutorDiscovery.merge(List.of(bean), List.of(new DeclaredRest()));

        assertThat(merged).as("the common case: the starter declares the bean and the adapter also registers it").hasSize(1);
        assertThat(merged.get(0)).isSameAs(bean);
    }

    @Test
    @DisplayName("discovery announces itself once, and says which executors it added")
    void discovery_isAnnouncedOnce() {
        StepExecutorDiscovery.merge(List.of(new DeclaredRest()), List.of(new DiscoveredUi()));

        assertThat(atLevel(Level.INFO)).as("implicit classpath wiring is rare in Spring; one line at context start makes it debuggable").hasSize(1);
        assertThat(atLevel(Level.INFO).get(0).getFormattedMessage()).contains(DiscoveredUi.class.getName());
    }

    @Test
    @DisplayName("nothing discovered means nothing said")
    void nothingToAdd_saysNothing() {
        StepExecutorDiscovery.merge(List.of(new DeclaredRest()), List.of(new DeclaredRest()));

        assertThat(atLevel(Level.INFO)).as("a line printed on every start that adds nothing is the noise this channel must not become").isEmpty();
        assertThat(atLevel(Level.DEBUG)).as("the ignored duplicate is still traceable at DEBUG").hasSize(1);
    }

    @Test
    @DisplayName("no beans at all: the SPI supplies the whole list")
    void noBeans_theSpiSuppliesEverything() {
        List<StepExecutor> merged = StepExecutorDiscovery.merge(List.of(), List.of(new DiscoveredUi()));

        assertThat(merged).hasSize(1);
    }

    /** A minimal executor that claims one step type; identity is its class, so each fake gets its own. */
    private abstract static class FakeExecutor implements StepExecutor {

        private final String type;

        FakeExecutor(String type) {
            this.type = type;
        }

        @Override
        public boolean supports(String stepType) {
            return type.equals(stepType);
        }

        @Override
        public StepResult execute(ScenarioStep step, StepExecutionContext context) {
            throw new UnsupportedOperationException("not executed in this test");
        }
    }

    private static final class DeclaredRest extends FakeExecutor {
        DeclaredRest() {
            super("rest.get");
        }
    }

    private static final class DiscoveredUi extends FakeExecutor {
        DiscoveredUi() {
            super("ui.open");
        }
    }

    private static final class ConsumersOwnRest extends FakeExecutor {
        ConsumersOwnRest() {
            super("rest.get");
        }
    }
}
