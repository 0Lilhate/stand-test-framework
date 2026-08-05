package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * KPI-9 counted by a tool rather than by a person reading Page Objects (task UITG-S024).
 *
 * <p>The metric pulls a trigger: above 50% the BRD escalates the {@code data-testid} policy to the
 * architecture committee (D-5, RISK-01). That is why the BRD forbids reading it off the generation
 * report — a number that judges the agent's output may not be sourced from the agent's own account
 * of it — and why it is asserted here against the kit's own reference Page Object, whose answer is
 * known: three of its five locators sit below rung 1.
 *
 * <p>The reference file is COPIED from the shipped bundle rather than restated as a fixture. A fixture
 * would keep passing while the artifact it claims to measure drifted, which is the failure mode of
 * every metric that measures a copy of the thing.
 */
class KpiLocatorCountTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String REFERENCE_PAGE_OBJECT =
            "docs/ai-agent/.claude/skills/stand-test-ui-page-object-design/example-page-object.java";

    /** The five factories of {@code UiLocator}, as this test expects the tool to know them. */
    private static final Set<String> KNOWN_STRATEGIES = Set.of("testId", "role", "label", "text", "css");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve(GUARD))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static Answer run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repositoryRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the counter did not finish in 60s");
            return new Answer(process.exitValue(), output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Answer count(Path directory, String... extra) {
        Path root = repositoryRoot();
        java.util.ArrayList<String> command = new java.util.ArrayList<>(
                List.of("node", root.resolve(GUARD).toString(), "kpi-locators", directory.toString()));
        command.addAll(List.of(extra));
        return run(command);
    }

    private static JsonNode json(Answer answer) {
        try {
            return MAPPER.readTree(answer.output());
        } catch (IOException e) {
            throw new UncheckedIOException("not JSON: " + answer.output(), e);
        }
    }

    /** The kit's reference Page Object, copied so the measurement is taken on the shipped bytes. */
    private static Path referenceDirectory(Path target) {
        try {
            Path source = repositoryRoot().resolve(REFERENCE_PAGE_OBJECT);
            Files.copy(source, target.resolve("ExamplePageObject.java"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("the kit's reference Page Object measures 3 of 5")
    void referencePageObjectMeasuresThreeOfFive(@TempDir Path directory) {
        JsonNode result = json(count(referenceDirectory(directory), "--json"));

        assertThat(result.path("denominator").asInt()).as("five locators, and the denominator is printed").isEqualTo(5);
        assertThat(result.path("numerator").asInt()).as("two test ids, three below rung 1").isEqualTo(3);
        assertThat(result.path("ratio").asDouble()).isEqualTo(0.6);
        assertThat(result.path("byStrategy").path("testId").asInt()).isEqualTo(2);
        assertThat(result.path("byStrategy").path("label").asInt()).isEqualTo(2);
        assertThat(result.path("byStrategy").path("role").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the output names numerator, denominator and every fragile locator")
    void outputCarriesTheEvidence(@TempDir Path directory) {
        Answer answer = count(referenceDirectory(directory));

        assertThat(answer.output())
                .as("a bare percentage cannot be acted on: the escalation needs the list")
                .contains("3 из 5")
                .contains("[label]")
                .contains("[role]")
                .contains("UiLocator.label(\"Сумма\")");
        assertThat(answer.output()).doesNotContain("[testId]");
    }

    @Test
    @DisplayName("fragile locators are reported at the line they are really on")
    void fragileLocatorsCarryTheirRealLine(@TempDir Path directory) throws IOException {
        JsonNode result = json(count(referenceDirectory(directory), "--json"));
        List<String> lines = Files.readAllLines(directory.resolve("ExamplePageObject.java"), StandardCharsets.UTF_8);

        // Every reported line must actually hold the locator. The projection that hides comments blanks
        // the newlines inside them too, so a line number counted in it drifts by one per javadoc line —
        // and the first version of this tool reported the closing `*/` of a javadoc as a label locator.
        for (JsonNode fragile : result.path("fragile")) {
            int line = fragile.path("line").asInt();
            assertThat(lines.get(line - 1))
                    .as("line %s of the reference Page Object must hold a %s locator", line, fragile.path("strategy").asText())
                    .contains("UiLocator." + fragile.path("strategy").asText() + "(");
        }
    }

    @Test
    @DisplayName("exceeding the threshold is a trigger, not a failure")
    void exceedingTheThresholdDoesNotBlock(@TempDir Path directory) {
        Answer answer = count(referenceDirectory(directory));

        assertThat(answer.exitCode())
                .as("KPI-9 is a metric, not a gate: a measurement that fails a build is one people stop taking")
                .isZero();
        assertThat(answer.output()).contains("триггер эскалации");
        assertThat(json(count(referenceDirectory(directory), "--json")).path("escalation").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a Page Object built entirely on test ids is below the threshold")
    void allTestIdsAreBelowTheThreshold(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("CleanPage.java"), """
                package example.qa.ui.pages;

                final class CleanPage {
                    private static final UiLocator ONE = UiLocator.testId("one");
                    private static final UiLocator TWO = UiLocator.testId("two");
                }
                """, StandardCharsets.UTF_8);

        JsonNode result = json(count(directory, "--json"));

        assertThat(result.path("denominator").asInt()).isEqualTo(2);
        assertThat(result.path("numerator").asInt()).isZero();
        assertThat(result.path("escalation").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an empty directory reports a denominator of zero, never 0%")
    void emptyDirectoryReportsNoDenominator(@TempDir Path directory) {
        Answer answer = count(directory);

        assertThat(answer.exitCode()).isZero();
        assertThat(answer.output())
                .as("0%% there would be the best possible KPI-9 awarded for measuring nothing")
                .contains("знаменатель 0")
                .doesNotContain("0.0%");
        assertThat(json(count(directory, "--json")).path("ratio").isNull()).isTrue();
    }

    @Test
    @DisplayName("a locator named in a comment is prose about a locator, not one")
    void commentedLocatorsAreNotCounted(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("CommentedPage.java"), """
                package example.qa.ui.pages;

                /** Never write UiLocator.css(".form__submit") here — it is the last rung. */
                final class CommentedPage {
                    // UiLocator.text("Отправить") was removed in favour of the test id below.
                    private static final UiLocator ONE = UiLocator.testId("one");
                }
                """, StandardCharsets.UTF_8);

        JsonNode result = json(count(directory, "--json"));

        assertThat(result.path("denominator").asInt())
                .as("the rule that forbids a strategy must not be counted as a use of it")
                .isEqualTo(1);
        assertThat(result.path("numerator").asInt()).isZero();
    }

    @Test
    @DisplayName("every UiLocator factory the SDK offers is known to the counter")
    void everyFactoryIsKnownToTheCounter() throws IOException {
        Path source = repositoryRoot().resolve("stand-test-ui/src/main/java/ru/alfa/stand/test/ui/UiLocator.java");
        Assumptions.assumeTrue(Files.isRegularFile(source), "stand-test-ui is not in this checkout");

        Matcher matcher = Pattern.compile("public static UiLocator (\\w+)\\(")
                .matcher(Files.readString(source, StandardCharsets.UTF_8));
        Set<String> factories = new TreeSet<>();
        while (matcher.find()) {
            factories.add(matcher.group(1));
        }

        assertThat(factories).as("the SDK must still declare the factories this metric counts").isNotEmpty();
        assertThat(factories)
                .as("a strategy the counter does not know is counted as no locator at all — a silent improvement of the number KPI-9 exists to worsen. Add it to STRATEGIES in hooks/lib/locators.mjs")
                .isSubsetOf(KNOWN_STRATEGIES);
    }

    /** The tool's answer as the shell sees it: what it printed, and whether it blocked. */
    private record Answer(int exitCode, String output) {
    }
}
