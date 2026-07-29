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
 * Who may write the curated knowledge base, and what happens after they do.
 *
 * <p>The hook used to refuse every agent write to the curated collections outright. That read as a
 * strong rule and was a broken one: {@code /stand-test-kb-update --apply} and
 * {@code /stand-test-apply-kb-candidates} exist to write exactly those files, so the promote step was
 * documented in four assets and possible in none. A rule nothing can satisfy is not enforcement — the
 * step gets performed outside the tool, where nothing watches at all.
 *
 * <p>What replaced it has three layers and each is honest about its own strength. A permit declares
 * the paths BEFORE the content exists, so a write that strays is refused while it is still
 * recoverable — the model can issue one, so this is scope, not authorisation. The human decision is
 * the host's permission prompt at the write itself, which no test here can exercise. And what
 * actually landed is judged afterwards by the {@code kb-write} gate, which re-runs {@code kb-validate}
 * over the bytes on disk and holds the session until it passes.
 *
 * <p>Half the cases below are negative, and the important ones are the paths that must keep WORKING:
 * an earlier design verified candidate provenance inside pre-write and would have killed
 * {@code kb-update}, whose input is an OpenAPI spec with no candidates anywhere.
 */
class GuardKbWritePermitTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String CURATED = "knowledge-base/services/client.yml";

    private static final String STATE = ".claude/.stand-test/state.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENTRY = """
            services:
              - id: client-service
                name: Client Service
                auth:
                  scheme: BASIC
                  usernameRef: CLIENT_SERVICE_USER
            """;

    private static final String REVIEW_DECISIONS = """
            documentId: spec-alpha
            reviewedOn: "2026-07-29"
            reviewedBy: "maintainer"
            services:
              disposition: promoted
              approved:
                - id: client-service
                  basis: "high confidence, endpoint list attested by the spec"
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
            Assumptions.abort("node is not available on this machine, so the guard could not be executed: " + e.getMessage());
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

    /** The PreToolUse hook, asked whether this write may happen. Nothing is staged: it runs before. */
    private static Answer askToWrite(Path project, String relative, String content) {
        String payload = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", project.resolve(relative).toString())
                        .put("content", content))
                .toString();
        return run(project, payload, "pre-write");
    }

    /** The write actually happening: the host writes the file, then PostToolUse records what landed. */
    private static Answer performWrite(Path project, String relative, String content) {
        Answer allowed = askToWrite(project, relative, content);
        if (allowed.exitCode() == 0) {
            write(project, relative, content);
            String payload = MAPPER.createObjectNode()
                    .put("cwd", project.toString())
                    .put("tool_name", "Write")
                    .set("tool_input", MAPPER.createObjectNode().put("file_path", project.resolve(relative).toString()))
                    .toString();
            run(project, payload, "post-write");
        }
        return allowed;
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

    private static String payload(Path project) {
        return MAPPER.createObjectNode().put("cwd", project.toString()).toString();
    }

    private static void stageReviewedDocument(Path project) {
        write(project, "knowledge-base/candidates/spec-alpha/review-decisions.yml", REVIEW_DECISIONS);
    }

    @Test
    @DisplayName("a curated write with no permit is refused, and the refusal hands back the command that fixes it")
    void curatedWrite_withoutPermit_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        Answer answer = askToWrite(project, CURATED, ENTRY);

        assertThat(answer.exitCode()).isEqualTo(2);
        assertThat(answer.output()).contains("kb-write-permit", CURATED);
    }

    @Test
    @DisplayName("a promote permit needs a review record for the document it names")
    void promotePermit_needsAReviewRecord(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(run(project, "", "kb-write-permit", "--reason", "promote", "--document", "spec-alpha", CURATED).exitCode())
                .as("no candidates/spec-alpha/review-decisions.yml exists — this is a promote of a document nobody reviewed")
                .isEqualTo(2);

        write(project, "knowledge-base/candidates/spec-alpha/review-decisions.yml", REVIEW_DECISIONS.replace("reviewedBy: \"maintainer\"", "reviewedBy:"));
        assertThat(run(project, "", "kb-write-permit", "--reason", "promote", "--document", "spec-alpha", CURATED).output())
                .as("a review record naming no reviewer is not a review record")
                .contains("reviewedBy");

        stageReviewedDocument(project);
        assertThat(run(project, "", "kb-write-permit", "--reason", "promote", "--document", "spec-alpha", CURATED).exitCode()).isZero();
        assertThat(askToWrite(project, CURATED, ENTRY).exitCode()).isZero();
    }

    @Test
    @DisplayName("kb-update keeps working: its input is a spec, and it has no candidates document at all")
    void updatePermit_needsOnlyItsSource(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "specs/orders.openapi.yml", "openapi: 3.0.0\n");

        assertThat(run(project, "", "kb-write-permit", "--reason", "update", "--source", "specs/orders.openapi.yml", CURATED).exitCode())
                .as("an earlier design demanded candidate provenance here and would have killed the command the README advertises")
                .isZero();
        assertThat(askToWrite(project, CURATED, ENTRY).exitCode()).isZero();

        assertThat(run(project, "", "kb-write-permit", "--reason", "update", "--source", "specs/nothing-here.yml", CURATED).exitCode())
                .as("a source that does not exist is a typo, and saying so costs one line")
                .isEqualTo(2);
        assertThat(run(project, "", "kb-write-permit", "--reason", "update", "--source", "pasted", CURATED).exitCode())
                .as("a contract pasted as text is legitimate — it just has to be said out loud")
                .isZero();
    }

    @Test
    @DisplayName("a permit covers exactly the files it names, and does not expire on first use")
    void permit_coversExactlyItsFileSet(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        run(project, "", "kb-write-permit", "--reason", "repair", CURATED, "knowledge-base/endpoints/client.yml");

        assertThat(askToWrite(project, CURATED, ENTRY).exitCode()).isZero();
        assertThat(askToWrite(project, "knowledge-base/endpoints/client.yml", "endpoints:\n  - id: create\n").exitCode()).isZero();
        assertThat(askToWrite(project, CURATED, ENTRY).exitCode())
                .as("one apply touches several files and re-touches the owning service's rollup; a single-use permit would be four refusals")
                .isZero();

        Answer unnamed = askToWrite(project, "knowledge-base/kafka/topics.yml", "kafkaTopics:\n  - id: t\n");
        assertThat(unnamed.exitCode()).isEqualTo(2);
        assertThat(unnamed.output()).contains(CURATED);
    }

    @Test
    @DisplayName("the schema contract is unpermittable — refused at issue AND at the write")
    void schema_isNeverPermitted(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(run(project, "", "kb-write-permit", "--reason", "repair", "knowledge-base/schema/service.schema.json").exitCode())
                .as("two checks for one rule: the contract must be impossible to permit by construction, not merely unreached")
                .isEqualTo(2);

        run(project, "", "kb-write-permit", "--reason", "repair", CURATED);
        assertThat(askToWrite(project, "knowledge-base/schema/service.schema.json", "{}").exitCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("the staging channels still need no permit at all")
    void staging_isUnchanged(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(askToWrite(project, "knowledge-base/mappings/case.yml", "testCaseMappings:\n  - id: c\n").exitCode()).isZero();
        assertThat(askToWrite(project, "knowledge-base/candidates/spec-alpha/services.candidates.yml", "candidates: []\n").exitCode()).isZero();
        assertThat(run(project, "", "kb-write-permit", "--reason", "repair", "knowledge-base/mappings/case.yml").exitCode())
                .as("permitting an already-open channel would make the permit mean less than it says")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an expired permit is refused, and says it expired rather than that none exists")
    void expiredPermit_isRefusedByName(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        run(project, "", "kb-write-permit", "--reason", "repair", CURATED);

        JsonNode current = state(project);
        ((com.fasterxml.jackson.databind.node.ObjectNode) current.get("permit")).put("expiresAt", "2020-01-01T00:00:00.000Z");
        write(project, STATE, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current));

        Answer answer = askToWrite(project, CURATED, ENTRY);
        assertThat(answer.exitCode()).isEqualTo(2);
        assertThat(answer.output()).contains("истёк");
    }

    @Test
    @DisplayName("a new session inherits no permission from an old one")
    void sessionStart_dropsTheStandingPermit(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        run(project, "", "kb-write-permit", "--reason", "repair", CURATED);

        assertThat(run(project, payload(project), "status").output()).contains("пермит");
        assertThat(askToWrite(project, CURATED, ENTRY).exitCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("a written curated file holds the session until the kb-write gate covers it")
    void curatedWrite_isHeldByItsOwnGate(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        run(project, "", "kb-write-permit", "--reason", "repair", CURATED);
        assertThat(performWrite(project, CURATED, ENTRY).exitCode()).isZero();

        assertThat(state(project).path("curated").has(CURATED)).as("recorded after the write, when the bytes on disk are the answer").isTrue();
        assertThat(run(project, payload(project), "stop").exitCode()).isEqualTo(2);

        assertThat(run(project, "", "record-gate", "--gate", "kb-write", "--verdict", "PASS", CURATED).exitCode()).isZero();
        assertThat(run(project, payload(project), "stop").exitCode()).isZero();
    }

    @Test
    @DisplayName("the kb-write gate refuses a PASS over what kb-validate condemns, and needs no subagent")
    void kbWriteGate_reRunsTheChecker(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        run(project, "", "kb-write-permit", "--reason", "repair", CURATED);
        performWrite(project, CURATED, ENTRY.replace("usernameRef: CLIENT_SERVICE_USER", "usernameRef: ${CLIENT_SERVICE_USER}"));

        Answer answer = run(project, "", "record-gate", "--gate", "kb-write", "--verdict", "PASS", CURATED);

        assertThat(answer.exitCode()).as("this is the deterministic half a pre-write rule could never be: before the write there is no file to judge").isEqualTo(2);
        assertThat(answer.output()).contains("KB_REF_NOT_BARE_NAME");
        assertThat(state(project).path("gates").path("kb-write").path("verdict").asText()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("the kb-write gate does not demand a subagent — its reviewer is a human, not a fresh context")
    void kbWriteGate_doesNotRequireDelegation(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        run(project, "", "kb-write-permit", "--reason", "repair", CURATED);
        performWrite(project, CURATED, ENTRY);

        assertThat(run(project, "", "record-gate", "--gate", "kb-write", "--verdict", "PASS", CURATED).exitCode())
                .as("requiring a subagent in a KB-only session would be a refusal with nothing behind it")
                .isZero();
    }

    @Test
    @DisplayName("a state file from before this change, and a corrupt one, both mean no permit rather than a crash")
    void state_failsClosed(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, STATE, "{\"artifacts\":{},\"gates\":{},\"runs\":[],\"results\":{},\"subagents\":[]}\n");
        assertThat(askToWrite(project, CURATED, ENTRY).exitCode()).as("an older state has no permit key at all").isEqualTo(2);

        write(project, STATE, "{ this is not json");
        assertThat(askToWrite(project, CURATED, ENTRY).exitCode()).as("corrupt bookkeeping must not read as a granted write").isEqualTo(2);
    }

    /** What the hook answered: the exit code is the contract, the text is what the model is shown. */
    private record Answer(int exitCode, String output) {
    }
}
