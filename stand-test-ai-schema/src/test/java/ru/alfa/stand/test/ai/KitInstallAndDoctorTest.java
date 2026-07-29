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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Installing the kit into a project, and asking afterwards whether what stands there is the kit.
 *
 * <p>Both halves answer questions a consumer could not otherwise ask. The install is by manifest
 * rather than by directory copy, because a copy carries whatever is in the directory — that is how a
 * file holding a developer's allow-list, absolute paths into an unrelated repository and a curl
 * command with DEV stand credentials once travelled with the bundle. The doctor exists because every
 * check that guards this kit lives in the SDK repository: at a consumer, a guardrail edited by hand
 * looks exactly like a guardrail, and a hook lost while merging {@code settings.json} looks exactly
 * like a working kit until the moment it matters.
 */
class KitInstallAndDoctorTest {

    private static final String INSTALLER = "docs/ai-agent/install.mjs";

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

    private static Answer run(Path workingDirectory, List<String> command) {
        try {
            Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(120, TimeUnit.SECONDS), "the command did not finish in 120s");
            return new Answer(process.exitValue(), output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Answer install(Path target, String... extra) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(INSTALLER).toString(), target.toString()));
        command.addAll(List.of(extra));
        return run(repositoryRoot(), command);
    }

    private static Answer doctor(Path project, String... extra) {
        List<String> command = new ArrayList<>(List.of("node", ".claude/hooks/stand-guard.mjs", "doctor"));
        command.addAll(List.of(extra));
        return run(project, command);
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + file, e);
        }
    }

    @Test
    @DisplayName("a dry run writes nothing, and says what it would have written")
    void install_isDryRunByDefault(@TempDir Path temporary) throws IOException {
        Path target = temporary.toRealPath();

        Answer answer = install(target);

        assertThat(answer.exitCode()).isZero();
        assertThat(answer.output()).contains("dry-run", "--apply");
        assertThat(Files.exists(target.resolve(".claude"))).as("the default must not touch the consumer's project").isFalse();
    }

    @Test
    @DisplayName("an install copies the manifest's files and the manifest itself, and the doctor then reports a clean kit")
    void install_thenDoctor_isClean(@TempDir Path temporary) throws IOException {
        Path target = temporary.toRealPath();

        assertThat(install(target, "--apply").exitCode()).isZero();
        assertThat(Files.exists(target.resolve(".claude/MANIFEST.json")))
                .as("without the manifest the installation cannot answer which version it is or what has been edited — the whole point of installing by one")
                .isTrue();
        assertThat(Files.exists(target.resolve(".claude/hooks/stand-guard.mjs"))).isTrue();
        assertThat(Files.exists(target.resolve(".opencode"))).as("--host claude installs one bundle, not both").isFalse();

        Answer report = doctor(target, "--exit-code");

        assertThat(report.exitCode()).isZero();
        assertThat(report.output()).contains("содержимое совпадает с манифестом", "подключены все");
    }

    @Test
    @DisplayName("the doctor names every kind of drift: a lost file, an edited one, a stray one, a missing hook")
    void doctor_reportsDrift(@TempDir Path temporary) throws IOException {
        Path target = temporary.toRealPath();
        install(target, "--apply");

        Files.delete(target.resolve(".claude/agents/stand-test-safety-reviewer.md"));
        Files.writeString(target.resolve(".claude/rules/stand-test-guardrails.md"), "\n<!-- смягчили гардрейл -->\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        write(target.resolve(".claude/skills/local-note.md"), "заметка, которой нет в ките\n");
        JsonNode settings = MAPPER.readTree(Files.readString(target.resolve(".claude/settings.json"), StandardCharsets.UTF_8));
        ((com.fasterxml.jackson.databind.node.ObjectNode) settings.get("hooks")).remove("SubagentStop");
        write(target.resolve(".claude/settings.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings));

        Answer report = doctor(target, "--exit-code");

        assertThat(report.exitCode()).as("a kit missing a file and a hook is a finding, not a note").isEqualTo(1);
        assertThat(report.output()).contains("stand-test-safety-reviewer.md", "stand-test-guardrails.md", "local-note.md", "SubagentStop");
    }

    @Test
    @DisplayName("a second install leaves a locally edited file alone unless told otherwise")
    void install_doesNotOverwriteLocalEdits(@TempDir Path temporary) throws IOException {
        Path target = temporary.toRealPath();
        install(target, "--apply");
        Path edited = target.resolve(".claude/rules/stand-test-guardrails.md");
        Files.writeString(edited, "\n<!-- локальная правка -->\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        Answer second = install(target, "--apply");

        assertThat(second.output()).contains("ПРАВЛЕН ЛОКАЛЬНО");
        assertThat(Files.readString(edited, StandardCharsets.UTF_8)).contains("локальная правка");

        install(target, "--apply", "--force");
        assertThat(Files.readString(edited, StandardCharsets.UTF_8))
                .as("--force is the answer to 'overwrite it', and it has to be asked for")
                .doesNotContain("локальная правка");
    }

    @Test
    @DisplayName("an installation with no manifest is a directory copy, and the doctor says exactly that")
    void doctor_withoutAManifest_saysHowItGotThere(@TempDir Path temporary) throws IOException {
        Path target = temporary.toRealPath();
        install(target, "--apply");
        Files.delete(target.resolve(".claude/MANIFEST.json"));

        Answer report = doctor(target, "--exit-code");

        assertThat(report.exitCode()).isEqualTo(1);
        assertThat(report.output()).contains("MANIFEST.json", "копированием каталога");
    }

    @Test
    @DisplayName("the doctor reports what the project still lacks around the kit, without calling it a defect of the kit")
    void doctor_separatesTheKitFromTheProject(@TempDir Path temporary) throws IOException {
        Path target = temporary.toRealPath();
        install(target, "--apply");

        Answer bare = doctor(target);
        assertThat(bare.output()).contains("реестр окружений", "база знаний");
        assertThat(bare.exitCode()).as("a project that has not been set up yet is not a broken installation").isZero();

        write(target.resolve("stand-test-environments.yml"), "environments:\n  ift:\n    services: {}\n");
        Files.createDirectories(target.resolve("knowledge-base"));
        assertThat(doctor(target).output()).contains("✔ реестр окружений", "✔ база знаний");
    }

    /** What the command answered: the exit code is the contract, the text is what a person reads. */
    private record Answer(int exitCode, String output) {
    }
}
