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
 * Finding 18: the failure that stopped being reported.
 *
 * <p>The other seventeen read one artifact and decide. This one cannot — a deleted assertion is not
 * in the file, and an inflated timeout looks exactly like a timeout — so it shipped declared and
 * switched off, and "17 of 18" was the honest number in every report the kit produced.
 *
 * <p>Both versions exist in two places, and it now runs in both. The write hook holds the file on
 * disk and the content about to replace it, so a red test being quietly made green is refused while
 * it happens. In CI the previous version comes from the base branch through {@code --against}.
 *
 * <p>Half of these cases are about SILENCE, and they are the ones that matter. Adding a step with its
 * own timeout, adding an assertion, disabling a test with a ticket — ordinary work that must not be
 * called concealment. A finding that fires on ordinary work gets switched off, and takes the four
 * certain signals with it.
 */
class GuardConcealmentTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the scanner did not finish in 60s");
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

    /** Scans `after.java` as the change to `before.java`, the way CI hands over a base version. */
    private static Answer compare(Path project, String before, String after) {
        write(project, "before.java", before);
        write(project, "after.java", after);
        return run(project, "", List.of("scan", "after.java", "--against", "before.java", "--json"));
    }

    private static List<String> findings(Answer answer) {
        List<String> ids = new ArrayList<>();
        try {
            MAPPER.readTree(answer.output()).path("findings").forEach(item -> ids.add(item.path("ruleId").asText()));
        } catch (IOException e) {
            throw new IllegalStateException("not JSON:\n" + answer.output(), e);
        }
        return ids;
    }

    private static JsonNode report(Answer answer) {
        try {
            return MAPPER.readTree(answer.output());
        } catch (IOException e) {
            throw new IllegalStateException("not JSON:\n" + answer.output(), e);
        }
    }

    /**
     * Coverage is counted against the artifact, so the assertions are about THIS finding rather than
     * about a total. The totals moved when the summary line stopped reporting the table's size and
     * started reporting how much of it a Java file is the subject of; what has not moved, and is the
     * point of the test, is that finding 18 is missing for one nameable reason and present the moment
     * that reason is answered.
     */
    @Test
    @DisplayName("the eighteenth finding is reported as waiting for the other version, and runs the moment it has one")
    void coverage_dependsOnHavingBothVersions(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "after.java", TWO_ASSERTIONS);

        JsonNode alone = report(run(project, "", List.of("scan", "after.java", "--json")));
        assertThat(ids(alone.path("gatesRun"))).doesNotContain("FAILURE_CONCEALMENT");
        assertThat(ids(alone.path("gatesNotRun"))).contains("FAILURE_CONCEALMENT");
        assertThat(alone.path("gatesNotRunReasons").path("FAILURE_CONCEALMENT").asText())
                .as("not run for want of the other version — not because a java file is outside its subject")
                .isEqualTo("previousVersion");

        JsonNode compared = report(compare(project, TWO_ASSERTIONS, TWO_ASSERTIONS));
        assertThat(ids(compared.path("gatesRun")))
                .as("the eighteenth check is not absent — it was waiting for the other version")
                .contains("FAILURE_CONCEALMENT");
        assertThat(compared.path("gatesNotRunReasons").path("FAILURE_CONCEALMENT").isMissingNode()).isTrue();
        assertThat(compared.path("findings")).as("the same content twice conceals nothing").isEmpty();
    }

    private static java.util.TreeSet<String> ids(JsonNode array) {
        java.util.TreeSet<String> found = new java.util.TreeSet<>();
        array.forEach(item -> found.add(item.asText()));
        return found;
    }

    @Test
    @DisplayName("a deleted assertion blocks — the system is no healthier, only quieter")
    void deletedAssertion_blocks(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String fewer = TWO_ASSERTIONS.replace("assertThat(result.status()).isEqualTo(200);", "");

        Answer answer = compare(project, TWO_ASSERTIONS, fewer);

        assertThat(findings(answer)).containsExactly("FAILURE_CONCEALMENT");
        assertThat(answer.output()).contains("было 2, стало 1");
        assertThat(report(answer).path("findings").path(0).path("severity").asText()).isEqualTo("BLOCK");
    }

    @Test
    @DisplayName("@Disabled blocks bare and warns with a ticket — one is a disappearance, the other a decision someone can follow")
    void disabled_isJudgedByWhetherItNamesATicket(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        Answer bare = compare(project, TWO_ASSERTIONS, TWO_ASSERTIONS.replace("    @Test", "    @Disabled\n    @Test"));
        assertThat(report(bare).path("findings").path(0).path("severity").asText()).isEqualTo("BLOCK");

        Answer ticketed = compare(project, TWO_ASSERTIONS,
                TWO_ASSERTIONS.replace("    @Test", "    @Disabled(\"ALFA-1234 стенд недоступен\")\n    @Test"));
        assertThat(report(ticketed).path("findings").path(0).path("severity").asText())
                .as("a ticket makes it a decision with an owner; it is still worth seeing")
                .isEqualTo("HIGH");
    }

    @Test
    @DisplayName("a new catch and a grown timeout are heuristics — reported for a person, not blocking")
    void catchAndTimeout_areHeuristics(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        Answer caught = compare(project, TWO_ASSERTIONS,
                TWO_ASSERTIONS.replace("assertThat(stand.run(scenario).isSuccessful()).isTrue();",
                        "try { stand.run(scenario); } catch (RuntimeException ignored) { }\n            assertThat(true).isTrue();"));
        assertThat(report(caught).path("findings").path(0).path("source").asText()).isEqualTo("HEURISTIC");

        String waiting = "class T { void t() { assertThat(a).isTrue(); Duration.ofSeconds(30); } }\n";
        Answer inflated = compare(project, waiting, waiting.replace("ofSeconds(30)", "ofSeconds(300)"));
        assertThat(findings(inflated)).containsExactly("FAILURE_CONCEALMENT");
        assertThat(report(inflated).path("findings").path(0).path("severity").asText())
                .as("a wait may genuinely need to be longer; blocking on that would be a gate people switch off")
                .isEqualTo("HIGH");
        assertThat(inflated.output()).contains("30000ms", "300000ms");
    }

    @Test
    @DisplayName("ordinary work is silent: an added assertion, a new step with its own timeout, a new file")
    void ordinaryWork_isNotConcealment(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String one = "class T { void t() { assertThat(a).isTrue(); Duration.ofSeconds(30); } }\n";
        String two = "class T { void t() { assertThat(a).isTrue(); assertThat(b).isTrue(); Duration.ofSeconds(30); Duration.ofSeconds(60); } }\n";

        assertThat(findings(compare(project, one, two)))
                .as("a step legitimately brings its own longer wait; calling that concealment would fire on ordinary work")
                .isEmpty();
        assertThat(findings(compare(project, "", TWO_ASSERTIONS)))
                .as("a new file has no previous version, and nothing can have been concealed in it")
                .isEmpty();
    }

    @Test
    @DisplayName("an assertion moved into a comment is a deleted assertion — that is the whole of it, in one keystroke")
    void commentingOutAnAssertion_isADeletion(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        Answer lineComment = compare(project, TWO_ASSERTIONS,
                TWO_ASSERTIONS.replace("        assertThat(result.status())", "        // assertThat(result.status())"));
        assertThat(findings(lineComment))
                .as("counting the raw text left the count untouched, so the cheapest way to make a red test green was also the quietest")
                .containsExactly("FAILURE_CONCEALMENT");
        assertThat(lineComment.output()).contains("было 2, стало 1");

        Answer blockComment = compare(project, TWO_ASSERTIONS,
                TWO_ASSERTIONS.replace("        assertThat(result.status()).isEqualTo(200);", "        /* assertThat(result.status()).isEqualTo(200); */"));
        assertThat(findings(blockComment)).containsExactly("FAILURE_CONCEALMENT");
    }

    @Test
    @DisplayName("an assertion ADDED in a comment was never a proof — the count must not rise for it either")
    void anAssertionInAComment_isNotACheck(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String commentary = TWO_ASSERTIONS.replace("    }\n}", "        // assertThat(result.body()).isNotNull(); — оставлено на потом\n    }\n}");

        assertThat(findings(compare(project, TWO_ASSERTIONS, commentary)))
                .as("if a commented assertion counted, the next real deletion would be paid for by it")
                .isEmpty();
        assertThat(findings(compare(project, commentary, TWO_ASSERTIONS)))
                .as("and removing the commentary is not a deletion of anything")
                .isEmpty();
    }

    @Test
    @DisplayName("@Disabled written inside a comment disables nothing")
    void disabledInAComment_isNotADisabledTest(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(findings(compare(project, TWO_ASSERTIONS, TWO_ASSERTIONS.replace("    @Test", "    // @Disabled — так делать нельзя\n    @Test"))))
                .isEmpty();
    }

    @Test
    @DisplayName("the SDK's own assertions are counted — the catalogue named six methods that do not exist and missed the ones that do")
    void theSdkAssertions_areCounted(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String scenario = """
                class OrderScenarioTest {
                    void t() {
                        Scenario scenario = Scenario.builder("order-flow")
                                .step(RestStep.get("orders-service", "/v1/orders/${id}")
                                        .expectStatus(200)
                                        .assertPath("$.status", "DONE")
                                        .assertPathContains("$.tags", "retail")
                                        .build())
                                .step(DbStep.expectEventually("orders-db")
                                        .sql("SELECT status FROM test_data.orders WHERE id = :id")
                                        .expectValue("DONE")
                                        .withinSeconds(10)
                                        .build())
                                .build();
                    }
                }
                """;

        Answer stripped = compare(project, scenario,
                scenario.replace(".assertPath(\"$.status\", \"DONE\")", "")
                        .replace(".expectValue(\"DONE\")", ""));

        assertThat(findings(stripped))
                .as(".assertPath is the most used assertion in the repository and .expectValue is the only one db.expectEventually has; neither was in the catalogue")
                .containsExactly("FAILURE_CONCEALMENT");
        assertThat(stripped.output()).contains("было 4, стало 2");
    }

    @Test
    @DisplayName("a wait spelled the way the DSL spells it counts as a wait")
    void withinSeconds_isATimeout(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String waiting = "class T { void t() { assertThat(a).isTrue(); KafkaStep.expect(\"topic\").withinSeconds(30).build(); } }\n";

        Answer inflated = compare(project, waiting, waiting.replace("withinSeconds(30)", "withinSeconds(300)"));

        assertThat(findings(inflated)).containsExactly("FAILURE_CONCEALMENT");
        assertThat(inflated.output()).contains("30000ms", "300000ms");
    }

    @Test
    @DisplayName("in the executable AI format the count follows the assertions the schema actually has")
    void theDocumentFormat_countsItsOwnAssertions(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String document = """
                {
                  "scenarioId": "order-flow",
                  "environment": "ift",
                  "steps": [
                    { "id": "create", "type": "rest.post", "service": "orders-service", "path": "/v1/orders", "expect": { "status": 201 } },
                    { "id": "await", "type": "rest.expectEventually", "service": "orders-service", "path": "/v1/orders/${orderId}", "timeout": "30s",
                      "expect": { "assert": [ { "path": "$.status", "equals": "DONE" }, { "path": "$.id", "notNull": true } ] } }
                  ]
                }
                """;
        write(project, "before.json", document);
        write(project, "after.json", document.replace(", { \"path\": \"$.id\", \"notNull\": true }", ""));

        Answer answer = run(project, "", List.of("scan", "after.json", "--against", "before.json", "--json"));

        assertThat(findings(answer))
                .as("the catalogue looked for `assertions:`/`matcher:`, which the executable schema does not have — so the branch was dead on every scenario the kit produces")
                .containsExactly("FAILURE_CONCEALMENT");
        assertThat(answer.output()).contains("было 2, стало 1");
    }

    @Test
    @DisplayName("a document that never calls itself a scenario is not word-searched for assertion keys")
    void documentThatIsNotAScenario_isNotCounted(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        String fixture = "{\n  \"externalId\": \"order-${testRunId}\",\n  \"status\": \"NEW\",\n  \"customer\": { \"status\": \"ACTIVE\" }\n}\n";
        write(project, "before.json", fixture);
        write(project, "after.json", fixture.replace("  \"status\": \"NEW\",\n", ""));
        assertThat(findings(run(project, "", List.of("scan", "after.json", "--against", "before.json", "--json"))))
                .as("`status` in a REST fixture is a field of the body; counting it made stand-test-fixture-authoring unable to edit its own output")
                .isEmpty();

        String candidate = "documentId: fs-lgoty\nentries:\n  - id: orders-db\n    status: needs-review\n  - id: clients-db\n    status: needs-review\n";
        write(project, "before.yml", candidate);
        write(project, "after.yml", candidate.replace("  - id: clients-db\n    status: needs-review\n", ""));
        assertThat(findings(run(project, "", List.of("scan", "after.yml", "--against", "before.yml", "--json"))))
                .as("promoting a KB candidate removes entries — the ordinary work of the job, and the false BLOCK this branch existed to stop causing")
                .isEmpty();
    }

    @Test
    @DisplayName("the write hook refuses the deletion as it happens, comparing the file on disk with what would replace it")
    void preWrite_refusesTheConcealment(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/OrderScenarioTest.java";
        write(project, artifact, TWO_ASSERTIONS);

        String payload = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", project.resolve(artifact).toString())
                        .put("content", TWO_ASSERTIONS.replace("assertThat(result.status()).isEqualTo(200);", "")))
                .toString();

        Answer answer = run(project, payload, List.of("pre-write"));

        assertThat(answer.exitCode()).as("the cheapest moment to act on it is before it is written").isEqualTo(2);
        assertThat(answer.output()).contains("FAILURE_CONCEALMENT", "было 2, стало 1");
    }

    @Test
    @DisplayName("in CI the finding is a rule that ran, and its SARIF rule is enabled only when it did")
    void sarif_marksTheRuleByWhatActuallyRan(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "before.java", TWO_ASSERTIONS);
        write(project, "after.java", TWO_ASSERTIONS.replace("assertThat(result.status()).isEqualTo(200);", ""));

        JsonNode log = report(run(project, "", List.of("scan", "after.java", "--against", "before.java", "--format", "sarif")));
        JsonNode rules = log.path("runs").path(0).path("tool").path("driver").path("rules");

        List<String> disabled = new ArrayList<>();
        rules.forEach(rule -> {
            if (!rule.path("defaultConfiguration").path("enabled").asBoolean(true)) {
                disabled.add(rule.path("id").asText());
            }
        });
        assertThat(disabled)
                .as("with a previous version supplied, finding 18 is on; what stays off is only what a java file is "
                        + "not the subject of, and declaring that as a gap would be the opposite overstatement")
                .doesNotContain("FAILURE_CONCEALMENT");
        assertThat(log.path("runs").path(0).path("results").path(0).path("ruleId").asText()).isEqualTo("FAILURE_CONCEALMENT");
        assertThat(log.path("runs").path(0).path("results").path(0).path("level").asText()).isEqualTo("error");
    }

    /** What the scanner answered: the exit code is the contract, the text is the report. */
    private record Answer(int exitCode, String output) {
    }
}
