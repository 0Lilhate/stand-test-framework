package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ROUTE a change takes must not decide whether it is checked.
 *
 * <p>The write hook was written against {@code Write}, where the tool call carries the whole file, and
 * everything it does followed from that: it scanned the call's content, compared it with the disk, and
 * recorded the hash of what it had seen. On the other two routes the call carries a FRAGMENT, and each
 * of those three steps quietly meant something else — the scan saw a fragment, the comparison ran
 * against {@code tool_input.old_string} (which {@code MultiEdit} does not have, so against nothing at
 * all), and the ledger recorded the hash of a fragment that describes no file anyone can re-check.
 *
 * <p>So the same deletion was refused as a {@code Write} and accepted as a {@code MultiEdit}, and an
 * {@code Edit} whose {@code new_string} was empty — the canonical way to delete a line — left the hook
 * before it scanned anything at all. "Passed the review, then quietly adjusted it" was closed on one
 * route out of three, which is the same as not closed: the agent picks the route.
 *
 * <p>The fix is to stop reasoning about fragments. The hook reconstructs the file the call would
 * leave behind — disk content with the call's edits applied — and judges THAT, on every route.
 *
 * <p>What that reconstruction must not become is a gate nobody can pass: a consumer's pre-existing
 * test may carry a violation the agent did not write, and refusing every edit to it would leave no way
 * to fix it. The first attempt at that — judge the write by what it INTRODUCES, subtracting the
 * findings already on disk — does not survive the detector table, and the case below says why: for
 * three of the nine blocking detectors a finding's evidence is its CATEGORY, so subtracting by it lets
 * one old {@code Thread.sleep} licence every later one, for the life of the file. What holds instead
 * is narrower and countable: a write that still violates is allowed only when it strictly REDUCES the
 * blocking findings, which is what repairing a file looks like and what adding to one never does.
 */
class GuardEditRouteTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ARTIFACT = "src/test/java/ru/alfa/qa/OrderScenarioTest.java";

    private static final String TWO_ASSERTIONS = """
            class OrderScenarioTest {
                @Test
                void orderIsAccepted() {
                    assertThat(stand.run(scenario).isSuccessful()).isTrue();
                    assertThat(result.status()).isEqualTo(200);
                }
            }
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

    private static Answer run(Path project, String stdin, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString()));
        command.addAll(arguments);
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

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }

    /** A {@code Write} call, as the host delivers it: the whole file in {@code content}. */
    private static String writeCall(Path project, String relative, String content) {
        ObjectNode input = MAPPER.createObjectNode()
                .put("file_path", project.resolve(relative).toString())
                .put("content", content);
        return payload(project, "Write", input);
    }

    /** An {@code Edit} call: one replacement fragment, and nothing about the rest of the file. */
    private static String editCall(Path project, String relative, String from, String to) {
        ObjectNode input = MAPPER.createObjectNode()
                .put("file_path", project.resolve(relative).toString())
                .put("old_string", from)
                .put("new_string", to);
        return payload(project, "Edit", input);
    }

    /** A {@code MultiEdit} call: several fragments, and no top-level {@code old_string} whatsoever. */
    private static String multiEditCall(Path project, String relative, List<String[]> edits) {
        ArrayNode list = MAPPER.createArrayNode();
        edits.forEach(edit -> list.add(MAPPER.createObjectNode().put("old_string", edit[0]).put("new_string", edit[1])));
        ObjectNode input = MAPPER.createObjectNode().put("file_path", project.resolve(relative).toString());
        input.set("edits", list);
        return payload(project, "MultiEdit", input);
    }

    private static String payload(Path project, String tool, ObjectNode input) {
        ObjectNode node = MAPPER.createObjectNode().put("cwd", project.toString()).put("tool_name", tool);
        node.set("tool_input", input);
        return node.toString();
    }

    /** What the artifact ledger believes this path now holds. */
    private static String recordedSha(Path project, String relative) {
        Path state = project.resolve(".claude/.stand-test/state.json");
        if (!Files.exists(state)) {
            return "";
        }
        try {
            return MAPPER.readTree(Files.readString(state, StandardCharsets.UTF_8))
                    .path("artifacts").path(relative).path("sha").asText("");
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the artifact ledger", e);
        }
    }

    @Test
    @DisplayName("an Edit whose new_string is empty is a deletion, not an empty call — the assertion it removes is refused")
    void emptyReplacement_isTheCanonicalDeletion(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TWO_ASSERTIONS);

        Answer answer = run(project, editCall(project, ARTIFACT, "        assertThat(result.status()).isEqualTo(200);\n", ""), List.of("pre-write"));

        assertThat(answer.exitCode())
                .as("an empty replacement was read as 'nothing to check', which made it the one shape that skipped every check")
                .isEqualTo(2);
        assertThat(answer.output()).contains("FAILURE_CONCEALMENT", "было 2, стало 1");
    }

    @Test
    @DisplayName("a MultiEdit is compared with the file on disk, not with a top-level old_string it never carries")
    void multiEdit_isComparedWithTheDisk(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TWO_ASSERTIONS);

        Answer answer = run(project, multiEditCall(project, ARTIFACT, List.of(
                new String[] {"assertThat(stand.run(scenario).isSuccessful()).isTrue();", "// removed"},
                new String[] {"assertThat(result.status()).isEqualTo(200);", "// removed"})), List.of("pre-write"));

        assertThat(answer.exitCode()).as("finding 18 read tool_input.old_string, which MultiEdit does not have — so it compared against nothing").isEqualTo(2);
        assertThat(answer.output()).contains("FAILURE_CONCEALMENT", "было 2, стало 0");
    }

    @Test
    @DisplayName("a Write that empties the file is a deletion of every assertion in it")
    void emptyWrite_isNotSilent(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TWO_ASSERTIONS);

        Answer answer = run(project, writeCall(project, ARTIFACT, ""), List.of("pre-write"));

        assertThat(answer.exitCode()).isEqualTo(2);
        assertThat(answer.output()).contains("FAILURE_CONCEALMENT", "было 2, стало 0");
    }

    @Test
    @DisplayName("the ledger records the hash of the resulting FILE on every route — a gate binds to content someone can re-check")
    void ledger_recordsTheResultingFile(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TWO_ASSERTIONS);
        String expected = TWO_ASSERTIONS.replace("orderIsAccepted", "orderIsAcceptedAndProjected");

        Answer edited = run(project, editCall(project, ARTIFACT, "orderIsAccepted", "orderIsAcceptedAndProjected"), List.of("pre-write"));

        assertThat(edited.exitCode()).as("renaming a method conceals nothing").isZero();
        assertThat(recordedSha(project, ARTIFACT))
                .as("the Edit route recorded the hash of the replacement fragment, which describes no file: the gate then bound to a hash nobody could reproduce")
                .isEqualTo(sha256(expected));

        write(project, ARTIFACT, expected);
        List<String[]> edits = List.<String[]>of(new String[] {"200", "201"});
        Answer multi = run(project, multiEditCall(project, ARTIFACT, edits), List.of("pre-write"));

        assertThat(multi.exitCode()).isZero();
        assertThat(recordedSha(project, ARTIFACT)).isEqualTo(sha256(expected.replace("200", "201")));
    }

    @Test
    @DisplayName("an edited document is scanned as a document — the detectors that need the whole file now get it")
    void documentDetectors_seeTheWholeDocument(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String scenario = "src/test/resources/ai/order-flow.json";
        String bounded = """
                {
                  "scenarioId": "order-flow",
                  "environment": "ift",
                  "steps": [
                    { "id": "await-projection", "type": "db.expectEventually", "datasource": "orders-db", "query": "SELECT status FROM app.orders WHERE id = :id", "timeout": "30s" }
                  ]
                }
                """;
        write(project, scenario, bounded);

        Answer answer = run(project, editCall(project, scenario, "\"timeout\": \"30s\"", "\"timeout\": \"never\""), List.of("pre-write"));

        assertThat(answer.exitCode())
                .as("a document detector parses JSON, and a fragment is not JSON — so every one of them was blind on this route")
                .isEqualTo(2);
        assertThat(answer.output()).contains("UNBOUNDED_TIMEOUT");
    }

    @Test
    @DisplayName("a violation already in the file is not an exemption — four attempts at making it one are recorded in the hook, and this pins the answer")
    void preExistingViolation_isNoExemption(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String legacy = """
                class LegacyOrderTest {
                    @Test
                    void orderIsAccepted() {
                        Thread.sleep(2000);
                        assertThat(result.status()).isEqualTo(200);
                    }
                }
                """;
        write(project, ARTIFACT, legacy);

        Answer unrelated = run(project, editCall(project, ARTIFACT, "isEqualTo(200)", "isEqualTo(201)"), List.of("pre-write"));

        assertThat(unrelated.exitCode())
                .as("the friction is real and deliberate: every exemption tried so far let a live secret or a production URL through")
                .isEqualTo(2);
        assertThat(unrelated.output()).contains("THREAD_SLEEP", "До этой правки файл уже нёс");

        Answer repair = run(project, editCall(project, ARTIFACT, "Thread.sleep(2000);\n        ", ""), List.of("pre-write"));

        assertThat(repair.exitCode()).as("the repair itself passes, so the file is never a dead end").isZero();

        write(project, ARTIFACT, TWO_ASSERTIONS);
        Answer introduced = run(project, editCall(project, ARTIFACT,
                "assertThat(result.status()).isEqualTo(200);",
                "Thread.sleep(2000);\n        assertThat(result.status()).isEqualTo(200);"), List.of("pre-write"));

        assertThat(introduced.exitCode()).as("and into a clean file it is refused outright").isEqualTo(2);
        assertThat(introduced.output()).contains("THREAD_SLEEP");
    }

    @Test
    @DisplayName("a secret that replaces a placeholder is a new secret — the exemption that excused it read the KEY, not the value")
    void replacingAPlaceholderSecret_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String fixture = "src/test/resources/env-fixture.json";
        write(project, fixture, "{\n  \"datasource\": { \"password\": \"changeme\" }\n}\n");

        Answer answer = run(project, writeCall(project, fixture,
                "{\n  \"datasource\": { \"password\": \"Pr0d!Kafka#2026-Xy7\" },\n  \"kafka\": { \"password\": \"Pr0d!Sasl#2026-Zq1\" }\n}\n"), List.of("pre-write"));

        assertThat(answer.exitCode())
                .as("SECRET_IN_SOURCE reports the field name, so subtracting findings by evidence made every password under a `password` key the same one")
                .isEqualTo(2);
        assertThat(answer.output()).contains("SECRET_IN_SOURCE");
    }

    @Test
    @DisplayName("an in-tree file is ledgered whichever way the path is spelled — through a case variant or a symlinked directory")
    void everySpellingOfAnInTreePath_isLedgered(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        Path outside = temporary.resolve("outside");
        Files.createDirectories(outside);
        Files.createSymbolicLink(project.resolve("linked"), outside);

        String clean = "class T { void t() { assertThat(a).isTrue(); } }\n";
        ObjectNode viaSymlink = MAPPER.createObjectNode()
                .put("file_path", project.resolve("linked").resolve("T.java").toString())
                .put("content", clean);

        assertThat(run(project, payload(project, "Write", viaSymlink), List.of("pre-write")).exitCode()).isZero();
        assertThat(recordedSha(project, "linked/T.java"))
                .as("a symlinked directory inside the tree resolves out of it, and deciding by the real path alone dropped the artifact")
                .isNotEmpty();
    }

    @Test
    @DisplayName("an older violation buys nothing: two statements are not currency for one DROP TABLE")
    void anInheritedFinding_isNotABudget(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String twoWrites = """
                class LegacyDbTest {
                    void t() {
                        db.sql("UPDATE public.orders SET status = 'NEW'");
                        db.sql("DELETE FROM public.items");
                    }
                }
                """;
        write(project, ARTIFACT, twoWrites);

        Answer answer = run(project, writeCall(project, ARTIFACT,
                "class LegacyDbTest {\n    void t() {\n        db.sql(\"DROP TABLE public.customers\");\n    }\n}\n"), List.of("pre-write"));

        assertThat(answer.exitCode())
                .as("counting findings per rule made an inherited file a budget: two old statements bought one new DROP TABLE and the total still fell")
                .isEqualTo(2);
        assertThat(answer.output()).contains("DROP TABLE public.customers");
    }

    @Test
    @DisplayName("a tool the hook does not model writes nothing it can vouch for, and is not recorded as an artifact")
    void anUnknownTool_isNotSynthesisedIntoAnEmptyFile(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        ObjectNode input = MAPPER.createObjectNode()
                .put("file_path", project.resolve(ARTIFACT).toString())
                .put("content", "class T { }\n");

        Answer answer = run(project, payload(project, "NotebookEdit", input), List.of("pre-write"));

        assertThat(answer.exitCode()).isZero();
        assertThat(recordedSha(project, ARTIFACT))
                .as("reconstructing it as an empty file recorded sha256(\"\") as a checked artifact — a gate bound to content that never existed")
                .isEmpty();
    }

    @Test
    @DisplayName("an Edit with no old_string CREATES the file — the one call that must not read as 'nothing changed'")
    void editWithoutOldString_isTheCreateRoute(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String created = "class NewTest { void t() throws Exception { Thread.sleep(30000); } }\n";

        Answer answer = run(project, editCall(project, ARTIFACT, "", created), List.of("pre-write"));

        assertThat(answer.exitCode())
                .as("the real tool writes new_string as the whole file here; treating it as a no-op made Edit the route that skipped the scan entirely")
                .isEqualTo(2);
        assertThat(answer.output()).contains("THREAD_SLEEP");
    }

    @Test
    @DisplayName("a path the hook has to resolve is still read from disk — a miss reads as 'no previous version', where nothing can have been concealed")
    void relativeOrOutsidePath_isResolvedBeforeReading(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TWO_ASSERTIONS);

        ObjectNode relative = MAPPER.createObjectNode()
                .put("file_path", ARTIFACT)
                .put("old_string", "        assertThat(result.status()).isEqualTo(200);\n")
                .put("new_string", "");

        Answer answer = run(project, payload(project, "Edit", relative), List.of("pre-write"));

        assertThat(answer.exitCode()).as("joining a relative path onto the workspace happened to work; nothing guaranteed it, and an absolute path outside it did not").isEqualTo(2);
        assertThat(answer.output()).contains("FAILURE_CONCEALMENT");
    }

    @Test
    @DisplayName("an edit the hook cannot apply falls back to scanning the fragments, and says that it did")
    void anUnappliableEdit_failsToTheFragments(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, ARTIFACT, TWO_ASSERTIONS);

        Answer refused = run(project, editCall(project, ARTIFACT, "no such text in the file", "Thread.sleep(9000);"), List.of("pre-write"));

        assertThat(refused.exitCode()).as("a reconstruction that failed is not a reason to wave the content through").isEqualTo(2);
        assertThat(refused.output()).contains("THREAD_SLEEP");

        Answer clean = run(project, editCall(project, ARTIFACT, "no such text in the file", "int x = 1;"), List.of("pre-write"));

        assertThat(clean.exitCode()).isZero();
        assertThat(clean.output())
                .as("a scan of fragments is a scan of less than the file, and the report must not claim more than it checked")
                .contains("сравнение с прежней версией не выполнялось");
    }

    /** What the guard answered: the exit code is the contract, the text is the report. */
    private record Answer(int exitCode, String output) {
    }
}
