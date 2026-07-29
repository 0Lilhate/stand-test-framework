package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
 * The gate bookkeeping of {@code stand-guard.mjs}, exercised as a process against a scratch project.
 *
 * <p>{@link GuardrailScannerParityTest} proves the scanner classifies content correctly. This one
 * proves the OTHER half — that a verdict can only be recorded about content that was actually
 * re-scanned. The two are separable and the second is the load-bearing one: every stronger claim the
 * kit makes ("the gate is tied to the content hash", "a PASS over a blocking finding is not
 * written", "a session cannot end with an unreviewed artifact") is enforced through this command, so
 * a way around it is a way around all of them at once.
 *
 * <p>There was one. {@code record-gate --gate safety-review --verdict PASS} with no file list
 * re-scanned nothing (the loop had nothing to iterate) and recorded a verdict covering every
 * artifact of the session (an empty list meant "all"). One command certified everything and verified
 * nothing. The first test below is that hole, pinned.
 *
 * <p>Runs in a temp directory rather than the repository: the command writes
 * {@code .claude/.stand-test/state.json} into its working directory, and a test that leaves state in
 * the developer's own harness would be a test people delete.
 */
class GuardGateRecordingTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String CLEAN_FIXTURE = "docs/ai-agent/.claude/hooks/corpus/clean-declarative-test.java.txt";

    private static final String DIRTY_FIXTURE = "docs/ai-agent/.claude/hooks/corpus/raw-transport-test.java.txt";

    private static final String ARTIFACT = "src/test/java/ru/alfa/qa/test/GeneratedScenarioTest.java";

    private static final String STATE = ".claude/.stand-test/state.json";

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

    private static String read(String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + relative, e);
        }
    }

    /**
     * Runs one subcommand of the guard in {@code project}, feeding it {@code stdin}.
     *
     * <p>Skipped rather than failed when node is absent, for the reason
     * {@link GuardrailScannerParityTest} states: this repository has decided not to depend on a
     * JavaScript runtime, and the absence is reported instead of hidden.
     */
    private static Answer run(Path project, String stdin, String... arguments) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString()));
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the guard did not finish in 60s");
            return new Answer(process.exitValue(), output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine, so the guard could not be executed: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Writes the fixture into the scratch project and announces it the way a real Write would. */
    private static Answer writeArtifact(Path project, String fixture) {
        return preWrite(project, ARTIFACT, read(fixture));
    }

    /** Stages content at a path and puts it through the hook that guards writes. */
    private static Answer preWrite(Path project, String relative, String content) {
        Path file = project.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage " + file, e);
        }
        String payload = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", file.toString())
                        .put("content", content))
                .toString();
        return run(project, payload, "pre-write");
    }

    private static JsonNode state(Path project) {
        Path file = project.resolve(STATE);
        if (!Files.exists(file)) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    private static String stopPayload(Path project) {
        return MAPPER.createObjectNode().put("cwd", project.toString()).toString();
    }

    @Test
    @DisplayName("a verdict that names no file is refused — it used to cover the whole session without re-scanning anything")
    void recordGate_withoutFiles_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        assertThat(writeArtifact(project, CLEAN_FIXTURE).exitCode()).as("a clean artifact is written, so the session now has something to answer for").isZero();

        Answer answer = run(project, "", "record-gate", "--gate", "safety-review", "--verdict", "PASS");

        assertThat(answer.exitCode()).as("exit 2 is what the host reads as a refusal; anything else lets the verdict through").isEqualTo(2);
        assertThat(answer.output()).contains("не перечислены файлы");
        assertThat(state(project).path("gates").isEmpty()).as("a refused verdict must leave no record — a BLOCK written here would still be a gate that ran").isTrue();
        assertThat(run(project, stopPayload(project), "stop").exitCode())
                .as("and the session must still be held: the artifact is no more reviewed than before the command")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a verdict that names the artifact covers exactly it, and the session may then end")
    void recordGate_namingTheArtifact_coversIt(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        writeArtifact(project, CLEAN_FIXTURE);

        Answer answer = run(project, "", "record-gate", "--gate", "safety-review", "--verdict", "PASS", ARTIFACT);

        assertThat(answer.exitCode()).isZero();
        JsonNode gate = state(project).path("gates").path("safety-review");
        assertThat(gate.path("verdict").asText()).isEqualTo("PASS");
        assertThat(gate.path("covers").fieldNames()).toIterable().containsExactly(ARTIFACT);
        assertThat(run(project, stopPayload(project), "stop").exitCode()).as("the one artifact of the session is covered, so nothing is stale").isZero();
    }

    @Test
    @DisplayName("a verdict about a file that is not on disk is refused rather than silently skipped")
    void recordGate_withAMissingFile_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        writeArtifact(project, CLEAN_FIXTURE);

        Answer answer = run(project, "", "record-gate", "--verdict", "PASS", "src/test/java/Typo.java");

        assertThat(answer.exitCode()).isEqualTo(2);
        assertThat(answer.output()).contains("src/test/java/Typo.java");
        assertThat(state(project).path("gates").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("PASS over an artifact the re-scan condemns is still refused, and what is recorded is the BLOCK")
    void recordGate_overABlockingFinding_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        // The write itself is refused first — which is the point of pre-write, and the reason this
        // file reaches the gate as something on disk that the session never registered as its own.
        assertThat(writeArtifact(project, DIRTY_FIXTURE).exitCode()).isEqualTo(2);

        Answer answer = run(project, "", "record-gate", "--verdict", "PASS", ARTIFACT);

        assertThat(answer.exitCode()).isEqualTo(2);
        assertThat(answer.output()).contains("PASS не записан");
        assertThat(state(project).path("gates").path("safety-review").path("verdict").asText())
                .as("the judgement of the scan is what survives, not the claim about it")
                .isEqualTo("BLOCK");
        assertThat(state(project).path("gates").path("safety-review").path("covers").isEmpty())
                .as("and it covers nothing: a write the guard rejected never became an artifact of this session")
                .isTrue();
    }

    @Test
    @DisplayName("a lowercase --verdict is not mistaken for a file name")
    void recordGate_parsesFlagValuesRatherThanGuessingThem(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        writeArtifact(project, CLEAN_FIXTURE);

        Answer answer = run(project, "", "record-gate", "--verdict", "pass", ARTIFACT);

        assertThat(answer.exitCode()).as("'pass' is the value of a flag; treating it as a path would fail the existence check for the wrong reason").isZero();
        assertThat(state(project).path("gates").path("safety-review").path("verdict").asText()).isEqualTo("PASS");
    }

    @Test
    @DisplayName("the design and the report do not hold the session — a gate is a gate on what the stand executes")
    void preWrite_ofProseDoesNotMakeAnArtifact(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(preWrite(project, "docs/case-analysis.md", "# Анализ кейса\n\nЗаказ создаётся, статус ждём в БД.\n").exitCode()).isZero();
        assertThat(preWrite(project, "docs/readiness-report.md", "# Отчёт\n\nСтадии 1-11 пройдены.\n").exitCode()).isZero();

        assertThat(state(project).path("artifacts").isEmpty())
                .as("stages 1-5 write five such documents, and each one demanding a safety verdict is how a gate becomes the thing people switch off")
                .isTrue();
        assertThat(run(project, stopPayload(project), "stop").exitCode()).isZero();
    }

    @Test
    @DisplayName("a scenario document does hold it — narrowing the bookkeeping must not narrow it past what runs")
    void preWrite_ofAScenarioDocumentMakesAnArtifact(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String scenario = """
                {"scenarioId": "order-accepted", "environment": "ift", "steps": [
                  {"id": "await-order", "type": "db.expectEventually", "timeout": "30s"}]}
                """;

        assertThat(preWrite(project, "src/test/resources/scenarios/order.json", scenario).exitCode()).isZero();

        assertThat(state(project).path("artifacts").fieldNames()).toIterable().containsExactly("src/test/resources/scenarios/order.json");
        assertThat(run(project, stopPayload(project), "stop").exitCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("a knowledge-base mapping does not hold it either — that channel answers to the promotion gate, not to this one")
    void preWrite_ofAKnowledgeBaseMappingMakesNoArtifact(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(preWrite(project, "knowledge-base/mappings/order-accepted.yml", "caseId: order-accepted\nscenarioId: order-accepted\n").exitCode())
                .as("mappings/ is the one channel the agent may write, so the write itself must go through")
                .isZero();

        assertThat(state(project).path("artifacts").isEmpty()).isTrue();
        assertThat(run(project, stopPayload(project), "stop").exitCode()).isZero();
    }

    @Test
    @DisplayName("prose is still scanned — a credential quoted in a report is refused at the moment of writing")
    void preWrite_ofProseIsStillScanned(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        Answer answer = preWrite(project, "docs/readiness-report.md",
                "# Отчёт\n\nЗапрос уходил с заголовком `Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature`.\n");

        assertThat(answer.exitCode()).as("what narrowed is the bookkeeping, not the scan; a secret in a markdown file is committed the moment it is written").isEqualTo(2);
        assertThat(answer.output()).contains("SECRET_IN_SOURCE");
    }

    /** What the hook answered: the exit code is the contract, the text is what the model is shown. */
    private record Answer(int exitCode, String output) {
    }
}
