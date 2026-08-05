package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The parallelism model is <em>applied</em>, not merely written down.
 *
 * <p>This repository has already been bitten once by a {@code junit-platform.properties} that sat under
 * {@code src/test/java} and was therefore never read: the file existed, the comment described the model, and
 * every test ran on one thread. Asserting on the file's content would repeat that mistake in test form. What
 * is asserted here is the <strong>effective configuration parameter</strong> — what JUnit resolved for this
 * very run, from wherever it came — read through an extension, which is the only place the platform offers
 * it.
 *
 * <p>Both suites are covered, because they are configured differently and both matter: {@code test} takes
 * the model and the parallelism from the file, {@code browserTest} takes the model from the file and
 * overrides the number downwards from {@code build.gradle.kts}, since a thread there costs a Chromium.
 */
@ExtendWith(UiParallelExecutionConfigTest.ResolvedConfiguration.class)
class UiParallelExecutionConfigTest {

    @Test
    @DisplayName("the fast suite really runs classes concurrently and a class's methods on one thread — the file is applied, not just present")
    void theModelIsAppliedToTheFastSuite() {
        assertModelIsApplied();
        assertThat(parallelism())
                .as("a parallelism of one would make the concurrent path never run, and every test asserting isolation vacuous")
                .isGreaterThan(1);
    }

    @Test
    @Tag("browser")
    @DisplayName("the browser suite runs under the same model, and whatever parallelism the launcher asked for is the parallelism JUnit resolved")
    void theModelIsAppliedToTheBrowserSuite() {
        assertModelIsApplied();
        // The build task passes the number as a JVM system property, which JUnit must prefer over
        // junit-platform.properties. Comparing the two is what proves the override is effective: if the file
        // won, this would read 6 while the task asked for 2, and the browser suite would quietly run at a
        // parallelism nobody chose for it.
        //
        // Conditional on purpose. The property is absent when this class is run from an IDE, and the number
        // is legitimately 1 when a developer passes -Pstand.test.ui.browser.parallelism=1 to serialise the
        // browsers on a small machine. Requiring "> 1" here would fail the very knob the README documents —
        // and it would be requiring nothing useful: BR-25 against a real browser is exercised by
        // UiParallelSuiteBrowserTest, which drives its own threads and does not depend on JUnit's.
        String requested = System.getProperty("junit.jupiter.execution.parallel.config.fixed.parallelism");
        if (requested != null) {
            assertThat(String.valueOf(parallelism()))
                    .as("the parallelism the launcher set must be the parallelism JUnit resolved")
                    .isEqualTo(requested);
        }
    }

    private static void assertModelIsApplied() {
        assertThat(ResolvedConfiguration.parameter("junit.jupiter.execution.parallel.enabled"))
                .as("parallel execution must be enabled for the UI suite")
                .isEqualTo("true");
        assertThat(ResolvedConfiguration.parameter("junit.jupiter.execution.parallel.mode.classes.default"))
                .as("scenarios parallelise: test classes run concurrently")
                .isEqualTo("concurrent");
        assertThat(ResolvedConfiguration.parameter("junit.jupiter.execution.parallel.mode.default"))
                .as("the steps of one scenario never do: methods within a class stay on one thread")
                .isEqualTo("same_thread");
    }

    private static int parallelism() {
        assertThat(ResolvedConfiguration.parameter("junit.jupiter.execution.parallel.config.strategy"))
                .as("a fixed strategy, so the parallel path runs on a small CI agent too")
                .isEqualTo("fixed");
        return Integer.parseInt(ResolvedConfiguration.parameter("junit.jupiter.execution.parallel.config.fixed.parallelism"));
    }

    /** Captures the configuration parameters JUnit actually resolved for this run. */
    static final class ResolvedConfiguration implements BeforeEachCallback {

        private static final Map<String, String> RESOLVED = new LinkedHashMap<>();

        static String parameter(String key) {
            synchronized (RESOLVED) {
                return RESOLVED.get(key);
            }
        }

        @Override
        public void beforeEach(ExtensionContext context) {
            synchronized (RESOLVED) {
                for (String key : new String[] {
                    "junit.jupiter.execution.parallel.enabled",
                    "junit.jupiter.execution.parallel.mode.classes.default",
                    "junit.jupiter.execution.parallel.mode.default",
                    "junit.jupiter.execution.parallel.config.strategy",
                    "junit.jupiter.execution.parallel.config.fixed.parallelism"}) {
                    RESOLVED.put(key, context.getConfigurationParameter(key).orElse(null));
                }
            }
        }
    }
}
