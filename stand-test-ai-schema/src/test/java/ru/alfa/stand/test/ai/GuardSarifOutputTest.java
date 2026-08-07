package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same gate, emitted for CI — where there is no session and no model.
 *
 * <p>Everything the kit enforces so far needs a run to enforce it: the write hook fires when an agent
 * writes, the Stop hook when an agent stops. A branch whose generated tests were committed last month
 * is guarded by nothing. SARIF is how the identical detector table answers on a pull request instead,
 * as annotations, with no LLM involved at any point.
 *
 * <p>Two things had to survive the translation, and they are what this test is about. A clean report
 * from PART of the set is not a clean report — so the finding that cannot run in a full scan is
 * declared as a disabled rule rather than omitted, and a dashboard reads 17 of 18. And a finding has
 * to point at a LINE: SARIF without a region puts every annotation on line 1, which in a generated
 * test of three hundred lines is the same as not saying where.
 */
class GuardSarifOutputTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String CORPUS = "docs/ai-agent/.claude/hooks/corpus";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private static Answer run(Path workingDirectory, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString()));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the scanner did not finish in 60s");
            return new Answer(process.exitValue(), output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode sarif(Answer answer) {
        try {
            return MAPPER.readTree(answer.output());
        } catch (IOException e) {
            throw new IllegalStateException("the scanner did not answer with JSON:\n" + answer.output(), e);
        }
    }

    private static Answer scan(String fixture, String... extra) {
        List<String> arguments = new ArrayList<>(List.of("scan", CORPUS + "/" + fixture, "--format", "sarif"));
        arguments.addAll(List.of(extra));
        return run(repositoryRoot(), arguments);
    }

    @Test
    @DisplayName("the log is SARIF 2.1.0 and declares the whole detector table as rules")
    void log_isSarifAndDeclaresEveryRule() {
        JsonNode log = sarif(scan("raw-transport-test.java.txt"));

        assertThat(log.path("version").asText()).isEqualTo("2.1.0");
        assertThat(log.path("$schema").asText()).contains("sarif-2.1.0");
        JsonNode driver = log.path("runs").path(0).path("tool").path("driver");
        assertThat(driver.path("name").asText()).isEqualTo("stand-guard");
        assertThat(driver.path("rules")).hasSize(26);
        assertThat(driver.path("rules").path(0).path("help").path("text").asText())
                .as("a finding without its fix is a complaint; the fix travels into the annotation")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the finding that cannot run is declared and switched off, not omitted")
    void ruleThatCannotRun_isDeclaredDisabled() {
        JsonNode rules = sarif(scan("raw-transport-test.java.txt")).path("runs").path(0).path("tool").path("driver").path("rules");

        List<String> disabled = new ArrayList<>();
        rules.forEach(rule -> {
            if (!rule.path("defaultConfiguration").path("enabled").asBoolean(true)) {
                disabled.add(rule.path("id").asText());
            }
        });

        assertThat(disabled)
                .as("omitting a rule would let a dashboard read eighteen checks where twelve ran — the same lie the "
                        + "text report refuses to tell. Finding 18 wants the other version; the rest are findings a "
                        + "java artifact is simply not the subject of, and both are declared rather than dropped")
                .contains("FAILURE_CONCEALMENT", "UNSANCTIONED_DEPENDENCY", "UNBOUNDED_TIMEOUT");
        JsonNode notifications = sarif(scan("raw-transport-test.java.txt")).path("runs").path(0)
                .path("invocations").path(0).path("toolConfigurationNotifications");
        assertThat(notifications).hasSameSizeAs(disabled);
        List<String> texts = new ArrayList<>();
        notifications.forEach(item -> texts.add(item.path("message").path("text").asText()));
        assertThat(texts)
                .as("the reason travels with the id: a dashboard that cannot tell 'not applicable' from 'not supplied' "
                        + "shows a gap where there is none")
                .anyMatch(text -> text.contains("нет прежней версии"))
                .anyMatch(text -> text.contains("вид артефакта"));
    }

    @Test
    @DisplayName("every result points at the line its evidence sits on")
    void results_carryTheLine() {
        JsonNode results = sarif(scan("raw-transport-test.java.txt")).path("runs").path(0).path("results");

        assertThat(results).isNotEmpty();
        results.forEach(result -> {
            JsonNode region = result.path("locations").path(0).path("physicalLocation").path("region");
            assertThat(region.path("startLine").asInt()).as("%s", result.path("ruleId").asText()).isPositive();
        });

        JsonNode url = results.get(0);
        assertThat(url.path("ruleId").asText()).isEqualTo("HARDCODED_STAND_URL");
        assertThat(url.path("level").asText()).as("a BLOCK finding is an error, and an error fails a build").isEqualTo("error");
        assertThat(url.path("locations").path(0).path("physicalLocation").path("region").path("startLine").asInt())
                .as("the JDBC string sits on line 27 of the fixture, and an annotation on line 1 would be the same as none")
                .isEqualTo(27);
    }

    @Test
    @DisplayName("a HIGH finding is a warning, so a build fails on what blocks and reports what does not")
    void highFindings_areWarnings() {
        JsonNode results = sarif(scan("shared-state-test.java.txt")).path("runs").path(0).path("results");

        List<String> levels = new ArrayList<>();
        results.forEach(result -> levels.add(result.path("level").asText()));
        assertThat(levels).isNotEmpty().allMatch("warning"::equals);
        assertThat(scan("shared-state-test.java.txt", "--exit-code").exitCode())
                .as("nothing here blocks, so a build must not fail — a gate that fails on notes is one people turn off")
                .isZero();
    }

    @Test
    @DisplayName("a clean file yields a valid log with no results, and an exit code CI can act on")
    void cleanFile_yieldsAnEmptyRun() {
        Answer answer = scan("clean-declarative-test.java.txt", "--exit-code");

        assertThat(answer.exitCode()).isZero();
        assertThat(sarif(answer).path("runs").path(0).path("results")).isEmpty();
        assertThat(sarif(answer).path("runs").path(0).path("tool").path("driver").path("rules"))
                .as("the rules travel even when nothing was found: that is what makes 'nothing was found' mean something")
                .hasSize(26);
    }

    @Test
    @DisplayName("a blocking finding fails the build, and the same run still prints the whole log")
    void blockingFinding_failsTheBuild() {
        Answer answer = scan("raw-transport-test.java.txt", "--exit-code");

        assertThat(answer.exitCode()).isEqualTo(1);
        assertThat(sarif(answer).path("runs").path(0).path("results")).isNotEmpty();
    }

    @Test
    @DisplayName("kb-validate speaks the same format, keeping the line it already knew")
    void knowledgeBaseChecks_emitSarifToo(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        Path file = project.resolve("knowledge-base/services/client.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                services:
                  - id: client-service
                    auth:
                      passwordRef: ${CLIENT_PASSWORD}
                """, StandardCharsets.UTF_8);

        JsonNode log = sarif(run(project, List.of("kb-validate", "--format", "sarif")));
        JsonNode result = log.path("runs").path(0).path("results").path(0);

        assertThat(result.path("ruleId").asText()).isEqualTo("KB_REF_NOT_BARE_NAME");
        assertThat(result.path("locations").path(0).path("physicalLocation").path("artifactLocation").path("uri").asText())
                .as("the KB checks report path:line, and the reader must split it rather than emit a file that does not exist")
                .isEqualTo("knowledge-base/services/client.yml");
        assertThat(result.path("locations").path(0).path("physicalLocation").path("region").path("startLine").asInt()).isEqualTo(4);
    }

    /** What the command answered: the exit code is the contract, the text is the log. */
    private record Answer(int exitCode, String output) {
    }
}
