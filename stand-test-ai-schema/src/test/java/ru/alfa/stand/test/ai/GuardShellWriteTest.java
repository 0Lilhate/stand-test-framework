package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hole the write hook could never see: a file written by the shell.
 *
 * <p>{@code pre-write} scans what {@code Write}/{@code Edit}/{@code MultiEdit} are about to leave
 * behind, refuses a blocking finding at the moment the line is typed, and records the artifact so the
 * safety gate can hold it at the end of the session. None of that reaches {@code cat > Test.java},
 * {@code sed -i}, {@code tee} or {@code cp /tmp/x.java src/} — the same content arrives through a tool
 * the scanner never runs on, and the artifact is not in the ledger either, so the Stop gate ends the
 * session reporting nothing to review.
 *
 * <p>So the perimeter is the ROUTE rather than the content: a shell write into the working tree is
 * refused and the sanctioned tool is named. What stays allowed is what a session genuinely needs and
 * what carries no artifact — a temporary file ({@code git show origin/main:path > /tmp/prev} is the
 * kit's own documented step), a build directory, {@code /dev/null}.
 *
 * <p>Half of these cases are about SILENCE, and they are the load-bearing half. A redirect inside a
 * quoted string, {@code 2>&1}, a pipe into {@code tail} — ordinary commands that must not be called a
 * write. A refusal on those is how a perimeter gets switched off, and it takes the real refusals
 * with it.
 */
class GuardShellWriteTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

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

    /** The hook as the host runs it: the tool call on stdin, the verdict in the exit code. */
    private static Answer preBash(Path project, String command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", "Bash");
        payload.put("tool_input", Map.of("command", command));
        payload.put("cwd", project.toString());

        List<String> line = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString(), "pre-bash"));
        try {
            Process process = new ProcessBuilder(line).directory(project.toFile()).redirectErrorStream(true).start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
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

    private static void assertRefused(Path project, String command) {
        Answer answer = preBash(project, command);
        assertThat(answer.exitCode())
                .as("this writes a file the scanner never sees, and the guard let it through:\n  %s\n%s", command, answer.output())
                .isEqualTo(2);
    }

    private static void assertAllowed(Path project, String command) {
        Answer answer = preBash(project, command);
        assertThat(answer.exitCode())
                .as("ordinary work refused as a write — a perimeter that fires on this is one people switch off:\n  %s\n%s", command, answer.output())
                .isZero();
    }

    @Test
    @DisplayName("a heredoc into the working tree is refused — this is the whole hole, in its commonest spelling")
    void heredocIntoTheWorkingTreeIsRefused(@TempDir Path project) {
        assertRefused(project, """
                cat > src/test/java/ru/alfa/qa/OrderScenarioTest.java <<'EOF'
                class OrderScenarioTest { }
                EOF""");
    }

    @Test
    @DisplayName("every redirect that creates or grows a file in the tree is refused")
    void redirectsIntoTheWorkingTreeAreRefused(@TempDir Path project) {
        assertRefused(project, "echo 'class A {}' > src/test/java/A.java");
        assertRefused(project, "echo 'more' >> src/test/resources/fixtures/order.json");
        assertRefused(project, "printf '%s' x >| src/test/java/A.java");
        assertRefused(project, "./gradlew test > test-output.txt");
    }

    @Test
    @DisplayName("in-place editors are refused: the content never passes through a scanned tool")
    void inPlaceEditorsAreRefused(@TempDir Path project) {
        assertRefused(project, "sed -i 's/assertThat/assumeThat/' src/test/java/A.java");
        assertRefused(project, "sed -i '' 's/30s/30m/' src/test/resources/ai/order.json");
        assertRefused(project, "perl -i -pe 's/a/b/' src/test/java/A.java");
    }

    @Test
    @DisplayName("tee is a write however it is reached, including through a pipe")
    void teeIsRefused(@TempDir Path project) {
        assertRefused(project, "echo 'class A {}' | tee src/test/java/A.java");
        assertRefused(project, "cat /tmp/body.json | tee -a src/test/resources/fixtures/order.json");
    }

    @Test
    @DisplayName("copying or moving content into the tree is the same bypass with a longer path")
    void copyAndMoveIntoTheWorkingTreeAreRefused(@TempDir Path project) {
        assertRefused(project, "cp /tmp/OrderScenarioTest.java src/test/java/ru/alfa/qa/OrderScenarioTest.java");
        assertRefused(project, "mv /tmp/order.json src/test/resources/ai/order.json");
    }

    @Test
    @DisplayName("a nested shell does not launder the write")
    void nestedShellIsRefused(@TempDir Path project) {
        assertRefused(project, "bash -c \"echo 'class A {}' > src/test/java/A.java\"");
        assertRefused(project, "sh -c 'sed -i s/a/b/ src/test/java/A.java'");
    }

    @Test
    @DisplayName("an interpreter told to write a file inline is refused")
    void inlineInterpreterWriteIsRefused(@TempDir Path project) {
        assertRefused(project, "python3 -c \"open('src/test/java/A.java','w').write('class A {}')\"");
        assertRefused(project, "node -e \"require('fs').writeFileSync('src/test/java/A.java','class A {}')\"");
    }

    @Test
    @DisplayName("an interpreter fed its script through a heredoc is the same inline write, spelled shorter")
    void heredocScriptWriteIsRefused(@TempDir Path project) {
        assertRefused(project, """
                python3 - <<'PY'
                open('src/test/java/A.java', 'w').write('class A {}')
                PY""");
    }

    @Test
    @DisplayName("unpacking an archive lands files the command line never names")
    void archiveExtractionIsRefused(@TempDir Path project) {
        assertRefused(project, "tar -xzf /tmp/tests.tgz -C src/test/java");
        assertRefused(project, "unzip /tmp/tests.zip -d src/test/resources");
    }

    @Test
    @DisplayName("reading an archive is not writing one, and packing into /tmp lands nothing in the tree")
    void archiveReadingIsAllowed(@TempDir Path project) {
        assertAllowed(project, "tar -czf /tmp/sources.tgz src/test/java");
        assertAllowed(project, "tar -czf /tmp/sources.tgz --exclude build src");
        assertAllowed(project, "unzip -l /tmp/tests.zip");
        assertAllowed(project, "python3 - <<'PY'\nprint(open('src/test/java/A.java').read())\nPY");
    }

    @Test
    @DisplayName("a target the hook cannot resolve is refused rather than assumed harmless")
    void unresolvableTargetIsRefused(@TempDir Path project) {
        assertRefused(project, "echo 'class A {}' > \"$TARGET\"");
        assertRefused(project, "echo 'class A {}' > $HOME/A.java");
    }

    @Test
    @DisplayName("a write outside the working tree is refused too — the guard cannot answer for what it never sees")
    void writeOutsideTheWorkingTreeIsRefused(@TempDir Path project) {
        assertRefused(project, "echo 'class A {}' > ../other-repo/A.java");
        assertRefused(project, "cp /tmp/A.java /etc/stand-test.conf");
    }

    @Test
    @DisplayName("a patch applies content nobody scanned, at a target the command line does not name")
    void patchIsRefused(@TempDir Path project) {
        assertRefused(project, "patch -p1 < /tmp/change.diff");
        assertRefused(project, "git apply /tmp/change.diff");
    }

    @Test
    @DisplayName("the refusal names the sanctioned route instead of only saying no")
    void theRefusalNamesTheSanctionedRoute(@TempDir Path project) {
        Answer answer = preBash(project, "echo 'class A {}' > src/test/java/A.java");

        assertThat(answer.output())
                .as("a refusal without the way forward is how a perimeter becomes something to work around")
                .contains("Write")
                .contains("Edit");
        assertThat(answer.output()).contains("src/test/java/A.java");
    }

    @Test
    @DisplayName("a temporary file stays allowed — the kit's own base-version step is exactly this")
    void temporaryFilesAreAllowed(@TempDir Path project) {
        assertAllowed(project, "git show origin/main:src/test/java/A.java > /tmp/prev.java");
        assertAllowed(project, "node .claude/hooks/stand-guard.mjs scan A.java --format sarif > /tmp/scan.sarif");
    }

    @Test
    @DisplayName("build output is not an artifact: a directory Gradle owns stays writable")
    void buildOutputIsAllowed(@TempDir Path project) {
        assertAllowed(project, "./gradlew test > build/reports/run.log");
        assertAllowed(project, "echo x > .claude/.stand-test/scratch.json");
    }

    @Test
    @DisplayName("a file descriptor is not a file, and neither is /dev/null")
    void descriptorsAndDevNullAreAllowed(@TempDir Path project) {
        assertAllowed(project, "./gradlew compileTestJava 2>&1 | tail -50");
        assertAllowed(project, "find . -name '*.java' 2>/dev/null | head");
        assertAllowed(project, "ls -la > /dev/null");
        assertAllowed(project, "echo problem >&2");
    }

    @Test
    @DisplayName("an angle bracket inside a quoted argument is not a redirect")
    void quotedAngleBracketsAreNotRedirects(@TempDir Path project) {
        assertAllowed(project, "grep -rn \"a > b\" src/test/java");
        assertAllowed(project, "rg 'timeout >= 30s' src");
        assertAllowed(project, "git log --format='%h -> %s' -5");
    }

    @Test
    @DisplayName("the commands a session actually runs stay untouched")
    void ordinaryCommandsAreAllowed(@TempDir Path project) {
        assertAllowed(project, "./gradlew compileTestJava checkstyleTest");
        assertAllowed(project, "git diff --stat");
        assertAllowed(project, "node .claude/hooks/stand-guard.mjs scan src/test/java/A.java");
        assertAllowed(project, "ls -la src/test/java && cat src/test/java/A.java");
    }

    @Test
    @DisplayName("the four commands the perimeter already refused are still refused")
    void theOriginalPerimeterSurvives(@TempDir Path project) {
        assertRefused(project, "rm -rf build");
        assertRefused(project, "git push origin HEAD");
        assertRefused(project, "curl -u user:secret https://stand/api");
        assertRefused(project, "psql -c 'delete from orders'");
    }

    private record Answer(int exitCode, String output) {
    }
}
