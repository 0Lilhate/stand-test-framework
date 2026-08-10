package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard refuses the same writes under opencode as under Claude Code (task UITG-S022, SEC-06).
 *
 * <p>Both bundle copies always carried the same {@code stand-guard.mjs}; what did not travel was the
 * binding to host events, which {@code settings.json} supplies and opencode has no equivalent of. A
 * gate that depends on which host a run uses is a gate the choice of host switches off, so this test
 * drives the opencode plugin exactly the way the host does — a function export, awaited before the
 * tool runs — and asserts that a {@code Thread.sleep} write is refused on this route too.
 *
 * <p>What it does NOT prove, and what no test here can: that opencode surfaces the thrown error as a
 * refused tool call. That is read from the runtime — the host awaits the trigger before calling the
 * tool — and was verified once by hand against opencode 1.18.12, whose session loader really does
 * discover and initialise this plugin.
 */
class OpencodeGuardPluginTest {

    private static final String PLUGIN = "docs/ai-agent/.opencode/plugin/stand-guard.js";

    /** A write the guard must refuse on any host: the manual wait its rules exist to forbid. */
    private static final String FORBIDDEN_SOURCE = "class Bad { void a() { Thread.sleep(5000); } }";

    private static final String CLEAN_SOURCE = "class Ok { void a() { assertThat(x).isTrue(); } }";

    /**
     * The driver, in the host's own shape: import the module, call it as a function, take the hooks
     * it returns, and invoke {@code tool.execute.before} with {@code (input, output)}.
     *
     * <p>Written as a script rather than mimicked in Java on purpose — a Java re-implementation of
     * the contract would pass while the real one drifted.
     */
    private static final String DRIVER = """
            import plugin from '%s';
            const cwd = process.argv[2];
            const hooks = await plugin({ directory: cwd });
            const answers = { exported: typeof plugin, hooks: Object.keys(hooks) };
            async function attempt(label, tool, args) {
              try {
                await hooks['tool.execute.before']({ tool, sessionID: 's', callID: 'c' }, { args });
                answers[label] = 'ALLOWED';
              } catch (error) {
                answers[label] = 'REFUSED: ' + String(error.message).split('\\n')[0];
              }
            }
            await attempt('writeForbidden', 'write', { filePath: cwd + '/src/test/java/Bad.java', content: %s });
            await attempt('writeClean', 'write', { filePath: cwd + '/src/test/java/Ok.java', content: %s });
            await attempt('editForbidden', 'edit', { filePath: cwd + '/src/test/java/New.java', oldString: '', newString: %s });
            await attempt('bashDangerous', 'bash', { command: 'rm -rf /' });
            await attempt('bashOrdinary', 'bash', { command: './gradlew build' });
            console.log(JSON.stringify(answers));
            """;

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve(PLUGIN))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static String quote(String source) {
        return "'" + source.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static String drive(Path project) {
        try {
            Path plugin = repositoryRoot().resolve(PLUGIN);
            Path driver = project.resolve("drive.mjs");
            Files.createDirectories(project.resolve("src/test/java"));
            Files.writeString(driver, DRIVER.formatted(
                    plugin.toUri(), quote(FORBIDDEN_SOURCE), quote(CLEAN_SOURCE), quote(FORBIDDEN_SOURCE)), StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(List.of("node", driver.toString(), project.toString()))
                    .directory(project.toFile())
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(120, TimeUnit.SECONDS), "the plugin driver did not finish in 120s");
            assertThat(output).as("the driver printed nothing; stderr was: %s", errors).isNotBlank();
            return output;
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the plugin is shaped the way opencode loads plugins")
    void pluginIsShapedForAutoDiscovery(@TempDir Path project) {
        Path plugin = repositoryRoot().resolve(PLUGIN);

        assertThat(plugin)
                .as("auto-discovery reads `.opencode/plugin/*.{js,ts}`; a file anywhere else needs a config entry that the kit does not ship")
                .isRegularFile();
        String answers = drive(project);
        assertThat(answers)
                .as("the export must be a FUNCTION returning hooks — an object literal is silently ignored by the loader")
                .contains("\"exported\":\"function\"")
                .contains("tool.execute.before")
                .contains("tool.execute.after");
    }

    @Test
    @DisplayName("a write carrying Thread.sleep is refused under opencode, as it is under Claude Code")
    void forbiddenWriteIsRefused(@TempDir Path project) {
        String answers = drive(project);

        assertThat(answers)
                .as("SEC-06: if the write passes here, the choice of host is a way around the gate")
                .contains("\"writeForbidden\":\"REFUSED")
                .contains("\"editForbidden\":\"REFUSED");
    }

    @Test
    @DisplayName("an ordinary write is not refused — the gate must not be a wall")
    void cleanWriteProceeds(@TempDir Path project) {
        assertThat(drive(project))
                .as("a check that refuses everything is indistinguishable from a broken one")
                .contains("\"writeClean\":\"ALLOWED\"");
    }

    @Test
    @DisplayName("the shell route is guarded too, and only where it should be")
    void dangerousCommandIsRefusedAndOrdinaryOneIsNot(@TempDir Path project) {
        String answers = drive(project);

        assertThat(answers).contains("\"bashDangerous\":\"REFUSED");
        assertThat(answers).contains("\"bashOrdinary\":\"ALLOWED\"");
    }

    @Test
    @DisplayName("the plugin states what it does NOT bind, rather than implying full parity")
    void pluginNamesTheGatesItDoesNotWire() {
        String source = read(repositoryRoot().resolve(PLUGIN));

        // The session-end gate cannot be expressed on this host: `event` returns void, so it can
        // report but not hold. Claiming parity would be the exact reporting the kit's rules forbid.
        assertThat(source)
                .as("an unwired gate that is not named reads as a wired one")
                .containsIgnoringCase("session-end")
                .contains("subagent-stop");
    }

    @Test
    @DisplayName("«opencode has no subagents» is true only while the bundle really declares none — the sentence dies with the gap")
    void theMissingSubagentsClaimMatchesTheBundle() {
        Path opencodeAgents = repositoryRoot().resolve("docs/ai-agent/.opencode/agents");
        boolean declaresSubagents = Files.isDirectory(opencodeAgents) && !listing(opencodeAgents).isEmpty();

        String plugin = read(repositoryRoot().resolve(PLUGIN));
        String rule = read(repositoryRoot().resolve("docs/ai-agent/.opencode/rules/stand-test-pipeline.md"));
        boolean saidByPlugin = plugin.contains("declares no subagents");
        boolean saidByRule = rule.contains("вторая копия их не объявляет");

        assertThat(saidByPlugin && saidByRule)
                .as("two documents tell a reader that stages 2, 4, 8 and 11 run in the main context under opencode, and the whole "
                        + "weight of the safety-review gate on that host rests on it. The claim is derivable — it is simply whether "
                        + "%s holds an agent — so it must not outlive the gap: porting the three subagents is the ONE takeable item of "
                        + "the kit plan, and the day it lands both sentences become false. Update them in the same change (and give "
                        + "`agents/` an opencode twin in BundleParityTest while you are there)", opencodeAgents)
                .isEqualTo(!declaresSubagents);
    }

    private static List<Path> listing(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
