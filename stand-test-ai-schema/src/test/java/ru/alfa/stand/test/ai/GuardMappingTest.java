package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The traceability record, which used to depend on the model remembering to write it.
 *
 * <p>{@code knowledge-base/mappings/} is the one collection an agent may write, and whether it did
 * was good faith. A generated test with no entry is a file whose reason for existing lives in a chat
 * transcript: months later nobody can say which case it covers, whether that case still holds, or
 * what was assumed while writing it — and no other part of the base can answer, because the mapping
 * is the only place the join is recorded.
 *
 * <p>What is enforced is that the record EXISTS. Half the entry is interpretation — {@code matched},
 * {@code missing}, {@code assumptions}, the title — so a hook that authored it would be inventing
 * knowledge, and one that rewrote the file to add the machine half would delete the human half. The
 * session simply does not end while a reviewed test is unclaimed, and the refusal carries the entry
 * to paste.
 */
class GuardMappingTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String ARTIFACT = "src/test/java/ru/alfa/qa/OrderScenarioTest.java";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                title: Заказ принят и подтверждён
                environment: ift
                matched: {}
                missing: []
                assumptions: []
                generatedTest:
                  module: qa-tests
                  package: ru.alfa.qa
                  className: OrderScenarioTest
                status: generated
                updated: "2026-07-29"
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
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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

    private static String payload(Path project) {
        return MAPPER.createObjectNode().put("cwd", project.toString()).toString();
    }

    /** A test written, reviewed by a fresh context and gated — everything but the traceability record. */
    private static Path reviewedTest(Path temporary, String relative, String source) throws IOException {
        Path project = temporary.toRealPath();
        write(project, relative, source);
        String write = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", project.resolve(relative).toString())
                        .put("content", source))
                .toString();
        assertThat(run(project, write, "pre-write").exitCode()).isZero();
        run(project, payload(project), "subagent-stop");
        assertThat(run(project, "", "record-gate", "--verdict", "PASS", relative).exitCode()).isZero();
        return project;
    }

    @Test
    @DisplayName("a reviewed test that no mapping claims holds the session, and the refusal carries the entry to paste")
    void unclaimedTest_holdsTheSession(@TempDir Path temporary) throws IOException {
        Path project = reviewedTest(temporary, ARTIFACT, TEST_SOURCE);

        Answer answer = run(project, payload(project), "stop");

        assertThat(answer.exitCode()).isEqualTo(2);
        assertThat(answer.output()).contains(ARTIFACT, "testCaseMappings", "className: OrderScenarioTest", "caseId: order-scenario");
        assertThat(answer.output())
                .as("the package is derived from the path, so the entry can be pasted rather than assembled")
                .contains("package: ru.alfa.qa");
    }

    @Test
    @DisplayName("with the entry present the session ends — and the entry may live in any file of the collection")
    void claimedTest_endsTheSession(@TempDir Path temporary) throws IOException {
        Path project = reviewedTest(temporary, ARTIFACT, TEST_SOURCE);
        write(project, "knowledge-base/mappings/whatever-the-file-is-called.yml", MAPPING);

        assertThat(run(project, payload(project), "stop").exitCode())
                .as("a collection spans files; reading one chosen file is how two different ift environments once coexisted here")
                .isZero();
    }

    @Test
    @DisplayName("a mapping for a different class does not claim this test")
    void mappingForAnotherClass_doesNotCount(@TempDir Path temporary) throws IOException {
        Path project = reviewedTest(temporary, ARTIFACT, TEST_SOURCE);
        write(project, "knowledge-base/mappings/other.yml", MAPPING.replace("OrderScenarioTest", "PaymentScenarioTest"));

        assertThat(run(project, payload(project), "stop").exitCode())
                .as("the join is by class name, and a near-miss is a miss — otherwise the record would claim coverage it does not have")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the second pass reports rather than loops — a gate that cannot be satisfied must still be reportable")
    void secondPass_reportsNotReady(@TempDir Path temporary) throws IOException {
        Path project = reviewedTest(temporary, ARTIFACT, TEST_SOURCE);

        String second = MAPPER.createObjectNode().put("cwd", project.toString()).put("stop_hook_active", true).toString();
        Answer answer = run(project, second, "stop");

        assertThat(answer.exitCode()).isZero();
        assertThat(answer.output()).contains("NOT-READY", ARTIFACT);
    }

    @Test
    @DisplayName("only generated tests are asked for a mapping — a fixture and a scenario document are not")
    void onlyJavaTests_areAsked(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String document = "{\"scenarioId\": \"order\", \"steps\": []}\n";
        String write = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", project.resolve("src/test/resources/fixtures/order.json").toString())
                        .put("content", document))
                .toString();
        write(project, "src/test/resources/fixtures/order.json", document);
        run(project, write, "pre-write");
        run(project, payload(project), "subagent-stop");
        run(project, "", "record-gate", "--verdict", "PASS", "src/test/resources/fixtures/order.json");

        assertThat(run(project, payload(project), "stop").exitCode())
                .as("a fixture has no class name to join on, and a fuzzy match would produce refusals nobody can act on")
                .isZero();
    }

    @Test
    @DisplayName("record-gate --case fails fast on a case the collection has never heard of")
    void recordGate_checksTheCaseIdEarly(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TEST_SOURCE);
        String write = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", project.resolve(ARTIFACT).toString())
                        .put("content", TEST_SOURCE))
                .toString();
        run(project, write, "pre-write");
        run(project, payload(project), "subagent-stop");

        Answer typo = run(project, "", "record-gate", "--verdict", "PASS", "--case", "order-scenari", ARTIFACT);
        assertThat(typo.exitCode()).isEqualTo(2);
        assertThat(typo.output()).contains("order-scenari", "testCaseMappings");

        write(project, "knowledge-base/mappings/order-scenario.yml", MAPPING);
        assertThat(run(project, "", "record-gate", "--verdict", "PASS", "--case", "order-scenario", ARTIFACT).exitCode()).isZero();
    }


    @Test
    @DisplayName("the entry the refusal prints is schema-valid once its placeholders are answered")
    void printedTemplate_satisfiesTheSchema(@TempDir Path temporary) throws IOException {
        Path project = reviewedTest(temporary, ARTIFACT, TEST_SOURCE);
        String output = run(project, payload(project), "stop").output();

        String yaml = output.substring(output.indexOf("testCaseMappings:"), output.indexOf("\n\n  mappings/"))
                .replace("<что проверяет тест — одной строкой>", "Заказ принят и подтверждён")
                .replace("<id окружения из реестра>", "ift")
                .replace("<gradle-модуль>", "qa-tests");

        JsonNode document = MAPPER.valueToTree(new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml));
        // Against the umbrella's own envelope rather than the thin per-entity file: the thin one $refs
        // the umbrella by URI, and resolving that would put a network fetch in a unit test.
        Path schema = repositoryRoot().resolve("docs/ai-agent/knowledge-base/schema/stand-test-knowledge-base.schema.json");
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.set("$defs", MAPPER.readTree(Files.readString(schema, StandardCharsets.UTF_8)).get("$defs"));
        envelope.put("$ref", "#/$defs/testCaseMappingsFile");
        Set<ValidationMessage> messages = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(envelope)
                .validate(document);

        assertThat(messages)
                .as("a hook that prints an entry the KB schema rejects sends the author round a loop it created itself")
                .isEmpty();
    }

    /** What the hook answered: the exit code is the contract, the text is what the model is shown. */
    private record Answer(int exitCode, String output) {
    }
}
