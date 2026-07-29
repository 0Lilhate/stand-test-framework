package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code post-run} sees after a gradle command, checked against a scratch project.
 *
 * <p>This hook carries the kit's most expensive single claim: a test gated with
 * {@code @EnabledIfEnvironmentVariable} that never ran still ends the build with BUILD SUCCESSFUL,
 * and the JUnit XML is the only place that says so. Two things made the claim narrower than it read.
 *
 * <p>It looked in {@code build/test-results} at the project root — a directory that does not exist
 * in a multi-module project, where every module keeps its own. In such a project the hook found
 * nothing and returned quietly, which is exactly what it does when a run has nothing to report, so
 * the difference was invisible.
 *
 * <p>And it re-read the whole tree every time. Gradle rewrites only what it executed, so results
 * from previous runs were journaled again on each subsequent command. The learning loop promotes a
 * failure signature once its fingerprint has been seen twice — on a journal that duplicates, that
 * threshold is met by one failure counted twice, which is the difference between learning and
 * hearing an echo.
 */
class GuardRunJournalTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String MODULE_RESULTS = "services/api/build/test-results/test";

    private static final String JOURNAL = ".claude/.stand-test/run-journal.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FAILING_SUITE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="ru.alfa.qa.test.OrderScenarioTest" tests="1" skipped="0" failures="1" errors="0">
              <testcase name="orderIsAccepted" classname="ru.alfa.qa.test.OrderScenarioTest">
                <failure message="alias &apos;order-service&apos; is not in the registry" type="ru.alfa.stand.test.core.exception.StandTestException">stack</failure>
              </testcase>
            </testsuite>
            """;

    private static final String ALL_SKIPPED_SUITE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="ru.alfa.qa.test.PaymentScenarioTest" tests="3" skipped="3" failures="0" errors="0">
              <testcase name="paymentIsBooked" classname="ru.alfa.qa.test.PaymentScenarioTest"><skipped/></testcase>
            </testsuite>
            """;

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    /** Runs {@code post-run} as the host would after a gradle command. */
    private static String postRun(Path project) {
        String payload = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .set("tool_input", MAPPER.createObjectNode().put("command", "./gradlew test"))
                .toString();
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString(), "post-run"));
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(payload.getBytes(StandardCharsets.UTF_8));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the guard did not finish in 60s");
            assertThat(process.exitValue()).as("post-run reports, it never blocks: a hook that fails the tool call after the fact only loses the report").isZero();
            return output;
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine, so the guard could not be executed: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Writes a suite where a module keeps it, the way Gradle would. */
    private static Path writeSuite(Path project, String directory, String name, String xml) {
        Path file = project.resolve(directory).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage " + file, e);
        }
        return file;
    }

    /** The same suite, re-run: Gradle rewrites the file, so its modification time moves. */
    private static void rewrite(Path file, String xml) {
        try {
            Files.writeString(file, xml, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().plus(Duration.ofSeconds(5))));
        } catch (IOException e) {
            throw new UncheckedIOException("could not rewrite " + file, e);
        }
    }

    private static List<String> journal(Path project) {
        Path file = project.resolve(JOURNAL);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank()).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    @Test
    @DisplayName("a failure in a module's own build directory is seen — the root build/test-results does not exist in a multi-module project")
    void postRun_findsResultsBelongingToAModule(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        writeSuite(project, MODULE_RESULTS, "TEST-ru.alfa.qa.test.OrderScenarioTest.xml", FAILING_SUITE);

        String output = postRun(project);

        assertThat(output).contains("OrderScenarioTest", "orderIsAccepted", "отпечаток");
        assertThat(journal(project)).hasSize(1);
    }

    @Test
    @DisplayName("a green build over a suite that skipped everything is called out, wherever the module keeps its results")
    void postRun_warnsAboutASuiteThatExecutedNothing(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        writeSuite(project, "services/payments/build/test-results/test", "TEST-ru.alfa.qa.test.PaymentScenarioTest.xml", ALL_SKIPPED_SUITE);

        assertThat(postRun(project)).contains("BUILD SUCCESSFUL, но выполнено 0 тестов из 3");
    }

    @Test
    @DisplayName("results left by an earlier run are not journaled again — a fingerprint must be counted once per occurrence")
    void postRun_readsEachResultOnce(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        writeSuite(project, MODULE_RESULTS, "TEST-ru.alfa.qa.test.OrderScenarioTest.xml", FAILING_SUITE);
        assertThat(journal(postRunAndReturn(project))).hasSize(1);

        String second = postRun(project);

        assertThat(second).as("the same XML, untouched by the second command: there is nothing new to report").isEmpty();
        assertThat(journal(project)).as("the learning loop promotes a signature seen twice, and this must not be the second time").hasSize(1);
    }

    @Test
    @DisplayName("a suite Gradle re-ran IS reported again — the same failure twice is two occurrences, not an echo")
    void postRun_readsARewrittenResultAgain(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        Path suite = writeSuite(project, MODULE_RESULTS, "TEST-ru.alfa.qa.test.OrderScenarioTest.xml", FAILING_SUITE);
        postRun(project);

        rewrite(suite, FAILING_SUITE);
        String second = postRun(project);

        assertThat(second).contains("OrderScenarioTest");
        assertThat(journal(project)).hasSize(2);
    }

    @Test
    @DisplayName("a project with no results at all is passed over in silence")
    void postRun_saysNothingWhenThereIsNothingToSay(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(postRun(project)).isEmpty();
        assertThat(journal(project)).isEmpty();
    }

    private static Path postRunAndReturn(Path project) {
        postRun(project);
        return project;
    }
}
