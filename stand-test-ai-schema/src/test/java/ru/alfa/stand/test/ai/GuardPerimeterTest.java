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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two ways a run could take the guard apart from inside.
 *
 * <p><b>Its own bookkeeping.</b> Several subcommands exist for the HOST to call, and each writes what
 * a gate later reads. {@code subagent-stop} is the expensive one: it is the entire evidence that stage
 * 8 ran in a separate context, and the safety gate refuses a {@code PASS} without it. While the guard
 * sat in the allow-list as one prefix — {@code Bash(node .claude/hooks/stand-guard.mjs:*)} — a session
 * could type that subcommand itself and then record its own verdict. The gate read the result as
 * delegation, which is the one thing it exists to prove.
 *
 * <p><b>Its own files.</b> {@code settings.json} denied {@code Edit} on the bundle and nothing else,
 * so the same write through {@code Write} went past: permission rules name a tool, and the tool is the
 * agent's choice. The scan could not stand in — a {@code .mjs} is neither Java, document nor build
 * file, and {@code NOT_AN_ARTIFACT} sends every dot-directory past the artifact ledger by design.
 *
 * <p>Both are checked here at the hook rather than in {@code settings.json}, because a permission rule
 * is a list of spellings and this is a decision. The settings file is still expected to carry the
 * matching denials — {@link ClaudeSettingsSafetyTest} owns that half — but the run must be refused
 * even where the host's own rules are merged away or spelled around.
 */
class GuardPerimeterTest {

    private static final String BUNDLE = "docs/ai-agent/.claude";

    private static final String GUARD = BUNDLE + "/hooks/stand-guard.mjs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Subcommands the host calls. Typed by hand they do not observe a fact, they manufacture one. */
    private static final List<String> HOST_ONLY =
            List.of("subagent-stop", "post-run", "stop", "pre-write", "post-write", "pre-bash", "status");

    /** Subcommands a run legitimately calls, which must keep working. */
    private static final List<String> RUN_CALLABLE =
            List.of("scan", "doctor", "kb-status", "kb-validate", "alias-check", "kb-write-permit", "record-gate");

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
        List<String> line = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString()));
        line.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(line).directory(project.toFile()).redirectErrorStream(true).start();
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

    private static Answer preBash(Path project, String command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", "Bash");
        payload.put("tool_input", Map.of("command", command));
        payload.put("cwd", project.toString());
        try {
            return run(project, MAPPER.writeValueAsString(payload), "pre-bash");
        } catch (IOException e) {
            throw new UncheckedIOException("could not encode the payload", e);
        }
    }

    private static Answer preWrite(Path project, String relative, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", "Write");
        payload.put("tool_input", Map.of("file_path", relative, "content", content));
        payload.put("cwd", project.toString());
        try {
            return run(project, MAPPER.writeValueAsString(payload), "pre-write");
        } catch (IOException e) {
            throw new UncheckedIOException("could not encode the payload", e);
        }
    }

    /**
     * A project with the bundle installed into it, which is the only shape these questions have an
     * answer in: the hook locates its own bundle relative to the working directory.
     */
    private static Path projectWithBundle(Path temporary) {
        Path project;
        try {
            project = temporary.toRealPath();
            Path source = repositoryRoot().resolve(BUNDLE);
            try (Stream<Path> walk = Files.walk(source)) {
                walk.forEach(path -> copyInto(source, path, project.resolve(".claude")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the bundle", e);
        }
        return project;
    }

    private static void copyInto(Path source, Path path, Path target) {
        Path destination = target.resolve(source.relativize(path).toString());
        try {
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not copy " + path, e);
        }
    }

    @Test
    @DisplayName("the run cannot call the subcommands that write the gates' own bookkeeping")
    void hostOnlySubcommands_areRefused(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);

        for (String subcommand : HOST_ONLY) {
            Answer answer = preBash(project, "node .claude/hooks/stand-guard.mjs " + subcommand);
            assertThat(answer.exitCode())
                    .as("'%s' writes what a gate later reads; called by hand it manufactures the fact instead of "
                            + "observing it — and for subagent-stop that fact IS the safety gate's proof of delegation:\n%s",
                            subcommand, answer.output())
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the refusal is by what the command does, not by how it is spelled")
    void hostOnlySubcommands_areRefusedThroughEverySpelling(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);
        String absolute = project.resolve(".claude/hooks/stand-guard.mjs").toString();

        List<String> spellings = List.of(
                "node .claude/hooks/stand-guard.mjs subagent-stop",
                "node ./.claude/hooks/stand-guard.mjs subagent-stop",
                "env node .claude/hooks/stand-guard.mjs subagent-stop",
                "node " + absolute + " subagent-stop",
                "sh -c 'node .claude/hooks/stand-guard.mjs subagent-stop'",
                "cd . && node .claude/hooks/stand-guard.mjs subagent-stop");

        for (String command : spellings) {
            assertThat(preBash(project, command).exitCode())
                    .as("a deny rule is a list of spellings; this refusal has to be a decision, and it missed:\n  %s", command)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the subcommands a run legitimately calls still work — a perimeter that blocks the work is switched off")
    void runCallableSubcommands_stillPass(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);

        for (String subcommand : RUN_CALLABLE) {
            assertThat(preBash(project, "node .claude/hooks/stand-guard.mjs " + subcommand).exitCode())
                    .as("'%s' is how the pipeline runs its own gates; refusing it would take the working checks down "
                            + "with the closed hole", subcommand)
                    .isZero();
        }
    }

    @Test
    @DisplayName("subagent-stop records nothing when it was not the host that called it")
    void subagentStop_recordsNothingWithoutTheHostsEvent(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);

        assertThat(run(project, "{}", "subagent-stop").exitCode())
                .as("a guard that crashes must not block the session; this reports and stands aside").isZero();
        assertThat(subagents(project))
                .as("a hand-typed call left the evidence of delegation behind — the third layer under the deny rule "
                        + "and the pre-bash refusal, for a caller that reaches the guard some other way")
                .isEmpty();

        String hostPayload = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("hook_event_name", "SubagentStop")
                .put("session_id", "test-session")
                .toString();
        assertThat(run(project, hostPayload, "subagent-stop").exitCode()).isZero();
        assertThat(subagents(project))
                .as("the host's own call must still record, or the safety gate becomes unsatisfiable — which is the "
                        + "shape of gate people switch off")
                .isNotEmpty();
    }

    private static List<String> subagents(Path project) {
        Path file = project.resolve(".claude/.stand-test/state.json");
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            JsonNode recorded = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("subagents");
            List<String> found = new ArrayList<>();
            recorded.forEach(at -> found.add(at.asText()));
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the guard's state", e);
        }
    }

    @Test
    @DisplayName("the run does not write the kit that governs it, whichever tool it reaches for")
    void bundleAssets_areNotWritable(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);

        List<String> assets = List.of(
                ".claude/hooks/stand-guard.mjs",
                ".claude/hooks/detectors.json",
                ".claude/settings.json",
                ".claude/rules/stand-test-guardrails.md",
                ".claude/agents/stand-test-safety-reviewer.md",
                ".claude/skills/stand-test-safety-review/safety-checklist.md",
                ".claude/commands/stand-test-java.md",
                ".claude/workflows/ingest-unstructured-spec-to-kb.md");

        for (String asset : assets) {
            Answer answer = preWrite(project, asset, "содержимое, которого там быть не должно\n");
            assertThat(answer.exitCode())
                    .as("one edit here removes the perimeter, after which every remaining gate passes honestly and "
                            + "means nothing:\n  %s\n%s", asset, answer.output())
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the refusal covers the bundle by its real location, not by the string './claude'")
    void bundleAssets_areNotWritableThroughAnAbsolutePath(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);

        assertThat(preWrite(project, project.resolve(".claude/hooks/detectors.json").toString(), "{}").exitCode())
                .as("the same file named absolutely is the same file").isEqualTo(2);
    }

    @Test
    @DisplayName("ordinary work is untouched: a test, a fixture and the knowledge base still write")
    void ordinaryWrites_stillPass(@TempDir Path temporary) {
        Path project = projectWithBundle(temporary);

        assertThat(preWrite(project, "src/test/java/ru/alfa/qa/OrderTest.java",
                "package ru.alfa.qa;\n\nclass OrderTest {\n    void t() {\n        assertThat(1).isEqualTo(1);\n    }\n}\n").exitCode())
                .as("the bundle rule must not reach outside the bundle").isZero();
        assertThat(preWrite(project, "src/test/resources/fixtures/create-order.json",
                "{\"orderId\": \"${testRunId}\"}\n").exitCode()).isZero();
        assertThat(preWrite(project, "knowledge-base/mappings/order.yml",
                "testCaseMappings: []\n").exitCode())
                .as("mappings/ is the one collection the agent may write, and the Stop gate depends on it").isZero();
    }

    private record Answer(int exitCode, String output) {
    }
}
