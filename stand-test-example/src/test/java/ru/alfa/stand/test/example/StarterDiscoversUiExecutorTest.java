package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.starter.StandTestAutoConfiguration;
import ru.alfa.stand.test.starter.StepExecutorDiscovery;
import ru.alfa.stand.test.ui.UiStepExecutor;

/**
 * ADR-UI-008 end to end: a Spring context picks up the UI adapter with no bean of its own.
 *
 * <p>This test lives here rather than in the starter for a reason that is itself the decision. The
 * starter must not depend on {@code stand-test-ui} — that is the architecture rule variant Б was chosen
 * to preserve — so the starter can only test the merge <em>rules</em>, with fakes. This module is the
 * one place where the whole graph is on a single test classpath, so it is the only place the real
 * composition can run: the real {@code META-INF/services} of the real UI adapter, loaded by the real
 * auto-configuration.
 *
 * <p>That split is not bureaucracy. The repository has already paid for the alternative: every link of
 * the attachment channel was covered on its own, the composition had never executed, and the binary
 * channel turned out to be dead from end to end (UITG-F003).
 *
 * <p>No browser is started here. Discovery is about whether the executor reaches the runner's list; the
 * UI steps themselves have their own browser-backed suite.
 *
 * <p><strong>{@code @Isolated}, and not as a precaution.</strong> This module runs its test classes
 * concurrently, and reading a log back requires casting SLF4J's logger to the logback one. SLF4J hands a
 * {@code SubstituteLogger} to every thread that asks while another thread is still initialising it, so
 * the cast throws {@code ClassCastException} — in the full suite only, never when this class runs alone.
 * It did exactly that here. Isolation removes the second thread rather than papering over the cast.
 */
@Isolated
class StarterDiscoversUiExecutorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StandTestAutoConfiguration.class));

    private Logger discoveryLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheDiscoveryLogger() {
        discoveryLogger = (Logger) LoggerFactory.getLogger(StepExecutorDiscovery.class);
        appender = new ListAppender<>();
        appender.start();
        discoveryLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheDiscoveryLogger() {
        discoveryLogger.detachAppender(appender);
        appender.stop();
    }

    private static Set<Class<?>> classesOf(List<StepExecutor> executors) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        executors.forEach(executor -> classes.add(executor.getClass()));
        return classes;
    }

    private List<String> announced() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @DisplayName("the real service registration of stand-test-ui is found by the real loader")
    void theUiAdapterRegistersItselfThroughTheSpi() {
        List<StepExecutor> discovered =
                StepExecutorDiscovery.merge(List.of(), getClass().getClassLoader());

        assertThat(discovered)
                .as("stand-test-ui ships META-INF/services and no bean; if this is empty the whole decision is moot")
                .anyMatch(UiStepExecutor.class::isInstance);
    }

    @Test
    @DisplayName("a Spring context with stand-test-ui on the classpath gets the UI executor without a bean")
    void springContext_discoversTheUiExecutorWithoutABean() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ScenarioRunner.class);
            assertThat(context)
                    .as("the UI adapter deliberately declares no bean — that is the asymmetry this decision removed")
                    .doesNotHaveBean(UiStepExecutor.class);
            assertThat(announced())
                    .as("the auto-configuration must actually call discovery; the announcement is how that is observable")
                    .anySatisfy(message -> assertThat(message).contains(UiStepExecutor.class.getName()));
        });
    }

    @Test
    @DisplayName("a service entry whose class will not load is warned about and skipped, and the search goes on past it")
    void unloadableServiceEntry_doesNotBreakTheContext() {
        List<Class<?>> order = List.copyOf(classesOf(StepExecutorDiscovery.merge(List.of(), getClass().getClassLoader())));
        assertThat(order).as("the probe needs at least two adapters to tell 'skipped' from 'stopped'").hasSizeGreaterThan(1);
        Class<?> first = order.get(0);
        Class<?> last = order.get(order.size() - 1);

        List<StepExecutor> discovered = StepExecutorDiscovery.merge(List.of(), new FilteredClassLoader(first));

        assertThat(classesOf(discovered))
                .as("hiding the FIRST adapter must remove exactly it and leave %s, the last one, still arriving."
                        + " Hiding the last would prove nothing — discovery could stop dead at it and the result would look"
                        + " identical, which is how the two weaker versions of this test let a stop-on-error mutation pass",
                        last.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(order.stream().filter(c -> !c.equals(first)).toList())
                .contains(last);
        assertThat(appender.list)
                .as("the services file is still on the classpath and names a class that no longer loads — exactly the shape that would otherwise throw ServiceConfigurationError out of a @Bean method and stop the context")
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("could not be loaded");
                });
    }

    @Test
    @DisplayName("a bean of the discovered class suppresses the SPI copy, so nothing arrives twice")
    void beanOfTheSameClass_suppressesTheDiscoveredCopy() {
        UiStepExecutor declared = new UiStepExecutor();

        List<StepExecutor> merged = StepExecutorDiscovery.merge(List.of(declared), getClass().getClassLoader());

        assertThat(merged.stream().filter(UiStepExecutor.class::isInstance))
                .as("the real adapter, arriving as a bean and through its real services file, must yield one entry")
                .hasSize(1);
        assertThat(merged.stream().filter(UiStepExecutor.class::isInstance).findFirst().orElseThrow()).isSameAs(declared);
    }
}
