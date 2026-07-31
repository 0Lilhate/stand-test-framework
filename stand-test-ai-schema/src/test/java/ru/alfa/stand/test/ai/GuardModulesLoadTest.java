package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard must be loadable, and a guard that is not must not look like a guard that found nothing.
 *
 * <p>The hook's own header promises that "nothing here ever exits non-zero because of its own bug — a
 * broken guard must not become a broken session", and every subcommand is wrapped to honour it. An ESM
 * module that fails to PARSE never reaches that wrapper: the import throws before the first line runs,
 * node prints a stack trace to stderr and exits 1, and Claude Code reads a non-2 exit code as "the
 * hook errored, do not block". The write proceeds. Every one of the eighteen findings is off, the
 * artifact ledger is not written, and the Stop gate ends the session clean because it has nothing
 * recorded to hold.
 *
 * <p>That is not hypothetical and it is not exotic. It happened twice while this file's neighbours
 * were being written, both times from the same slip: a doc comment quoting a path or a SQL comment
 * that contains the two characters which end a block comment. The syntax error was in prose, the
 * consequence was total, and the only outward sign was that the session got quieter.
 *
 * <p>So: every shipped hook module is parsed here, named individually, and the entry point is run for
 * real. A failure names the file, which is the thing the silent version could never do.
 */
class GuardModulesLoadTest {

    private static final String HOOKS = "docs/ai-agent/.claude/hooks";

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

    private static Answer node(List<String> arguments, String stdin) {
        List<String> command = new ArrayList<>(List.of("node"));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).directory(repositoryRoot().toFile()).start();
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish in 60s");
            return new Answer(process.exitValue(), out + err);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> shippedModules() {
        Path hooks = repositoryRoot().resolve(HOOKS);
        try (Stream<Path> tree = Files.walk(hooks)) {
            return tree.filter(path -> path.toString().endsWith(".mjs")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + hooks, e);
        }
    }

    @Test
    @DisplayName("every shipped hook module parses — a syntax error in a comment disables the whole layer, silently")
    void everyModule_parses() {
        List<Path> modules = shippedModules();
        assertThat(modules).as("the hooks directory must hold the modules this test exists to guard").isNotEmpty();

        List<String> broken = new ArrayList<>();
        for (Path module : modules) {
            Answer answer = node(List.of("--check", module.toString()), "");
            if (answer.exitCode() != 0) {
                broken.add(repositoryRoot().relativize(module) + ": " + answer.output().lines().findFirst().orElse(""));
            }
        }
        assertThat(broken).as("a module that does not parse takes every guardrail with it").isEmpty();
    }

    @Test
    @DisplayName("the entry point resolves its whole import graph — `node --check` parses one file and would miss a broken import")
    void theEntryPoint_resolvesItsImports() {
        Answer answer = node(List.of(repositoryRoot().resolve(HOOKS).resolve("stand-guard.mjs").toString(), "status"), "{}");

        assertThat(answer.output())
                .as("an unresolvable or unparsable import throws before the subcommand runs, and the trace is the only sign")
                .doesNotContain("SyntaxError", "ERR_MODULE_NOT_FOUND", "Cannot find module");
        assertThat(answer.exitCode()).as("`status` reports and proceeds; anything else here is the import graph failing").isZero();
    }

    /** What node answered: the exit code is the contract, the text is stdout and stderr together. */
    private record Answer(int exitCode, String output) {
    }
}
