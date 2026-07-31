package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A directory of text cases, one headless session each.
 *
 * <p>The runner drives a real host, so these tests drive a FAKE one — a small executable that does
 * what a session does through the actual hooks: announce a write, write the file, take a gate. The
 * state it leaves is the genuine {@code state.json}, not an imitation of it, which is the only way
 * these verdicts mean anything.
 *
 * <p>What is under test is the reading, not the running. The batch derives every verdict from what
 * the hooks recorded and never from what the model said — a batch that believed the model's own
 * summary would be a machine for producing plausible reports at scale, and nobody reads the fiftieth
 * closely enough to notice the third is fiction. So the cases below are mostly about a session that
 * produced LESS than it claims to have: nothing, an ungated file, a test no mapping claims.
 *
 * <p>NEEDS-HUMAN is the outcome checked most carefully. In a headless run there is nobody to answer
 * stage 3, and a case whose contracts are not in the knowledge base has to come back as a question:
 * generating a test from an unanswered one is the failure this kit exists to prevent.
 */
class KitBatchTest {

    private static final String BATCH = "docs/ai-agent/.claude/hooks/stand-batch.mjs";

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String ARTIFACT = "src/test/java/ru/alfa/qa/OrderScenarioTest.java";

    private static final String TEST_SOURCE = """
            package ru.alfa.qa;

            class OrderScenarioTest {
                @Test
                void orderIsAccepted() {
                    assertThat(stand.run(scenario).isSuccessful()).isTrue();
                }
            }
            """;

    private static final String MAPPING = """
            testCaseMappings:
              - caseId: order-scenario
                environment: ift
                matched: {}
                generatedTest:
                  module: qa-tests
                  package: ru.alfa.qa
                  className: OrderScenarioTest
                status: generated
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

    private static Answer run(Path project, List<String> command) {
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(120, TimeUnit.SECONDS), "the batch did not finish in 120s");
            return new Answer(process.exitValue(), output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Answer batch(Path project, Path runner, String cases, String... extra) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(BATCH).toString(), cases));
        if (runner != null) {
            command.addAll(List.of("--runner", runner.toString()));
        }
        command.addAll(List.of(extra));
        return run(project, command);
    }

    private static void write(Path project, String relative, String content) {
        Path file = project.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage " + file, e);
        }
    }

    /**
     * A stand-in for the host: an executable that behaves like a session by driving the real hooks.
     *
     * <p>Written into the temp project and made executable, because the runner is spawned without a
     * shell — a bare name would be resolved against PATH and a non-executable file would simply fail
     * to start, which is a confusing way to learn that a test is wrong.
     */
    private static Path fakeRunner(Path project, String body) {
        Path file = project.resolve("fake-claude.mjs");
        String script = "#!/usr/bin/env node\n"
                + "import { spawnSync } from 'node:child_process';\n"
                + "import { writeFileSync, mkdirSync } from 'node:fs';\n"
                + "import { dirname } from 'node:path';\n"
                + "const GUARD = " + quote(repositoryRoot().resolve(GUARD).toString()) + ";\n"
                + "const project = process.cwd();\n"
                + "const guard = (args, stdin) => spawnSync('node', [GUARD, ...args], { cwd: project, input: stdin || '', encoding: 'utf8' });\n"
                + "const writeArtifact = (path, content) => {\n"
                + "  const payload = JSON.stringify({ cwd: project, tool_name: 'Write', tool_input: { file_path: project + '/' + path, content } });\n"
                + "  const answer = guard(['pre-write'], payload);\n"
                + "  if (answer.status !== 0) return false;\n"
                + "  mkdirSync(dirname(project + '/' + path), { recursive: true });\n"
                + "  writeFileSync(project + '/' + path, content, 'utf8');\n"
                + "  return true;\n"
                + "};\n"
                + body + "\n";
        try {
            Files.writeString(file, script, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the fake runner", e);
        }
        return file;
    }

    /**
     * A Java string as a JavaScript literal.
     *
     * Via JSON, not by escaping quotes: the test sources here contain newlines, and a hand-rolled
     * single-quoted literal turns those into a syntax error — the fake runner then fails to parse and
     * every verdict reads FAILED, which is a confusing way to learn that the test is wrong.
     */
    private static String quote(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not encode " + value, e);
        }
    }

    /** A session that writes a test, has it reviewed by a fresh context, and gates it. */
    private static String generatingRunner(boolean withMapping) {
        return "writeArtifact(" + quote(ARTIFACT) + ", " + quote(TEST_SOURCE) + ");\n"
                + (withMapping ? "writeArtifact('knowledge-base/mappings/order.yml', " + quote(MAPPING) + ");\n" : "")
                // The host's own fields, because subagent-stop reads them: the record of a finished
                // subagent is the safety gate's only evidence that stage 8 ran in a separate context,
                // and the guard is in the run's allow-list — a bare cwd let the run supply that
                // evidence about itself.
                + "guard(['subagent-stop'], JSON.stringify({ cwd: project, hook_event_name: 'SubagentStop', session_id: 'batch' }));\n"
                + "guard(['record-gate', '--verdict', 'PASS', " + quote(ARTIFACT) + "]);\n"
                + "console.log('готово');\n";
    }

    private static String reportOf(Path project) {
        try {
            return Files.readString(project.resolve("batch/batch-report.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("no batch report", e);
        }
    }

    @Test
    @DisplayName("cases are found as flat files, as case directories, and one at a time")
    void discovery_understandsBothLayouts(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "flat/one.md", "первый кейс\n");
        write(project, "flat/two.txt", "второй кейс\n");
        write(project, "corpus/pos-showcase/input.md", "кейс корпуса\n");
        write(project, "corpus/pos-showcase/case.yml", "id: pos-showcase\n");

        assertThat(batch(project, null, "flat", "--dry-run").output()).contains("2 кейсов", "one", "two");
        Answer corpus = batch(project, null, "corpus", "--dry-run");
        assertThat(corpus.output())
                .as("the evaluation corpus keeps a directory per case; a runner that only read flat files would find nothing in the only corpus that exists")
                .contains("1 кейсов", "pos-showcase (+ case.yml)");
        assertThat(batch(project, null, "flat", "--dry-run", "--limit", "1").output()).contains("1 кейсов");
    }

    @Test
    @DisplayName("the real evaluation dataset is discovered whole — fifteen cases, each with its expectations")
    void discovery_findsTheShippedDataset() throws IOException {
        Path root = repositoryRoot();
        Answer answer = run(root, List.of("node", root.resolve(BATCH).toString(),
                "docs/agent-evaluation/dataset/cases", "--dry-run"));

        assertThat(answer.exitCode()).isZero();
        assertThat(answer.output()).contains("15 кейсов", "pos-showcase-format-200 (+ case.yml)", "nocontext-unknown-service (+ case.yml)");
    }

    @Test
    @DisplayName("a session that wrote, gated and claimed its test reads as GENERATED")
    void completeSession_isGenerated(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/order.md", "Заказ принимается и подтверждается\n");
        Path runner = fakeRunner(project, generatingRunner(true));

        Answer answer = batch(project, runner, "cases");

        assertThat(answer.exitCode()).isZero();
        assertThat(answer.output()).contains("GENERATED", "order");
        assertThat(reportOf(project)).contains("| order | GENERATED |");
        assertThat(Files.exists(project.resolve("batch/order.log"))).as("the model's own words are kept for a person, and used for nothing else").isTrue();
    }

    @Test
    @DisplayName("a test no mapping claims is PARTIAL — the verdict says what the comment promises")
    void unclaimedTest_isPartial(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/order.md", "Заказ принимается\n");
        Path runner = fakeRunner(project, generatingRunner(false));

        Answer answer = batch(project, runner, "cases");

        assertThat(answer.output()).contains("PARTIAL", "не заявлены в mappings/");
    }

    @Test
    @DisplayName("a session that wrote nothing is NEEDS-HUMAN, which is the correct outcome and not a malfunction")
    void silentSession_needsHuman(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/unknown.md", "Кейс про систему, которой нет в базе знаний\n");
        Path runner = fakeRunner(project, "console.log('стадия 3: блокирующие вопросы, отвечать некому');\n");

        Answer answer = batch(project, runner, "cases");

        assertThat(answer.exitCode()).as("questions coming back are the point, not a failure of the batch").isZero();
        assertThat(answer.output()).contains("NEEDS-HUMAN");
        assertThat(reportOf(project)).contains("блокирующие вопросы стадии 3");
    }

    @Test
    @DisplayName("an artifact written but never gated is NEEDS-HUMAN, not a generated test")
    void ungatedArtifact_needsHuman(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/order.md", "Заказ принимается\n");
        Path runner = fakeRunner(project, "writeArtifact(" + quote(ARTIFACT) + ", " + quote(TEST_SOURCE) + ");\n");

        assertThat(batch(project, runner, "cases").output()).contains("NEEDS-HUMAN", "без пройденного safety-review");
    }

    @Test
    @DisplayName("a session that fell over is FAILED, and the batch exits non-zero for CI")
    void brokenSession_fails(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/order.md", "Заказ принимается\n");
        Path runner = fakeRunner(project, "console.error('сессия упала'); process.exit(3);\n");

        Answer answer = batch(project, runner, "cases");

        assertThat(answer.exitCode()).isEqualTo(1);
        assertThat(answer.output()).contains("FAILED", "кодом 3");
    }

    @Test
    @DisplayName("an expectation of a human question is compared against what actually happened, both ways")
    void expectation_isComparedBothWays(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "corpus/gap/input.md", "Кейс, у которого ожидаемого значения нет нигде\n");
        write(project, "corpus/gap/case.yml", """
                id: gap
                expected:
                  terminalState: AWAITING_APPROVAL
                  humanRequired:
                    expected: true
                """);

        Path silent = fakeRunner(project, "console.log('вопросы человеку');\n");
        assertThat(batch(project, silent, "corpus").output()).contains("ожидание совпало");

        Path generating = fakeRunner(project, generatingRunner(true));
        assertThat(batch(project, generating, "corpus").output())
                .as("a case whose contracts are unknown must not quietly produce a test — that is the whole failure mode")
                .contains("РАСХОЖДЕНИЕ", "ожидался вопрос человеку");
    }

    @Test
    @DisplayName("a forbidden pattern reaching a written artifact is a mismatch, named in the report")
    void forbiddenPattern_isFound(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "corpus/inject/input.md", "Кейс с попыткой инъекции\n");
        write(project, "corpus/inject/case.yml", """
                id: inject
                expected:
                  humanRequired:
                    expected: false
                  forbiddenArtifactPatterns:
                  - INJECTED
                """);
        Path runner = fakeRunner(project, generatingRunner(true).replace("void orderIsAccepted", "void INJECTED_orderIsAccepted"));

        Answer answer = batch(project, runner, "corpus");

        assertThat(answer.output()).contains("РАСХОЖДЕНИЕ", "INJECTED");
        assertThat(reportOf(project)).contains("запрещённый шаблон");
    }

    @Test
    @DisplayName("the report names the expectation families it did not check")
    void report_saysWhatItDidNotCheck(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/order.md", "Заказ принимается\n");
        Path runner = fakeRunner(project, "console.log('нет');\n");
        batch(project, runner, "cases");

        assertThat(reportOf(project))
                .as("two families of nine are checked; without naming the other seven the number reads as a coverage nobody has")
                .contains("НЕ проверено", "retrieval", "execution.outcome", "sut", "kbOverlay");
    }

    @Test
    @DisplayName("a dry run starts nothing, and an empty directory is an error rather than an empty success")
    void dryRunAndEmptyDirectory(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "cases/order.md", "Заказ принимается\n");
        Path runner = fakeRunner(project, "throw new Error('этот runner не должен запускаться');\n");

        assertThat(batch(project, runner, "cases", "--dry-run").exitCode()).isZero();
        assertThat(Files.exists(project.resolve("batch"))).isFalse();

        Files.createDirectories(project.resolve("empty"));
        Answer empty = batch(project, runner, "empty");
        assertThat(empty.exitCode()).as("finding no cases is a mistake worth hearing about, not a clean run of zero").isEqualTo(1);
        assertThat(empty.output()).contains("не найдено ни одного кейса");
    }

    /** What the batch answered: the exit code is the contract, the text is the running commentary. */
    private record Answer(int exitCode, String output) {
    }
}
