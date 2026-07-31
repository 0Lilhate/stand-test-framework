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
 * Finding 3 must refuse the writes the SDK refuses — and only those.
 *
 * <p>The detector extracted a statement from anything spelled {@code .query("…")} or {@code .sql("…")}
 * and asked whether it read. Three of the four DB shapes the kit itself prescribes cannot answer yes:
 * {@code DbStep.query(alias)} is a FACTORY that takes a datasource alias, so the alias went to the SQL
 * classifier as if it were a statement; {@code db.cleanup} must carry a bare {@code DELETE FROM
 * schema.table} with no WHERE of its own, because the SDK appends {@code WHERE col = :testRunId} and
 * refuses to build the step without {@code whereTestRunId}; and {@code db.write} is an INSERT with no
 * {@code :testRunId} at all, undone afterwards by a primary-key compensation it declares in
 * {@code identifiedBy}. {@code RestStep…query("status","NEW")} was caught by the same anchor.
 *
 * <p>So the kit's own {@code java-test-template.java} could not be written through its own write hook,
 * and the safety gate could not record a PASS over it either — {@code record-gate} re-runs the scan.
 * That is the failure the plan names as the one that ends the whole layer: a gate which is always red
 * gets switched off, and it takes the seventeen accurate findings with it.
 *
 * <p>The sanction is not "an INSERT is fine now". It is the SDK's own condition, read where the SDK
 * reads it — in the step that carries the statement. A DELETE is permitted only in the exact shape
 * {@code db.cleanup} produces (bare, two-part target, {@code whereTestRunId} declared beside it); an
 * INSERT only with {@code identifiedBy} beside it. A DELETE that brings its own WHERE stays a finding
 * even with {@code whereTestRunId} present, because that is a statement the write-guard would refuse.
 */
class GuardSanctionedDbShapesTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final String TEMPLATE = "docs/ai-agent/.claude/skills/stand-test-java-dsl-authoring/java-test-template.java";

    private static final String EXAMPLE = "docs/ai-agent/.claude/skills/stand-test-java-dsl-authoring/example-generated.java";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every DB shape the crib in {@code stand-test-java-dsl-authoring/SKILL.md} prescribes, plus a REST query. */
    private static final String CANONICAL = """
            class OrderScenarioTest {
                @Test
                void orderIsAccepted() {
                    Scenario scenario = Scenario.builder("order-flow")
                            .step(DbStep.seed("orders-db")
                                    .sql("INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)")
                                    .taggedByTestRunId("test_run_id")
                                    .param("id", "order-${testRunId}")
                                    .build())
                            .step(DbStep.write("clients-db")
                                    .sql("INSERT INTO app.clients(client_id, name) VALUES (:client_id, 'ACME')")
                                    .param("client_id", "client-${testRunId}")
                                    .identifiedBy("client_id")
                                    .build())
                            .step(RestStep.get("orders-service", "/v1/orders")
                                    .query("status", "NEW")
                                    .expectStatus(200)
                                    .build())
                            .step(DbStep.query("orders-db")
                                    .sql("SELECT status FROM test_data.orders WHERE id = :id")
                                    .param("id", "order-${testRunId}")
                                    .capture("status", "status")
                                    .build())
                            .step(DbStep.cleanup("orders-db")
                                    .sql("DELETE FROM test_data.orders")
                                    .whereTestRunId("test_run_id")
                                    .build())
                            .build();
                    assertThat(stand.run(scenario).isSuccessful()).isTrue();
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

    private static Answer run(Path directory, String stdin, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString()));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
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

    /** The rule ids the scanner reports for one staged artifact. */
    private static List<String> scan(Path project, String relative, String content) {
        write(project, relative, content);
        Answer answer = run(project, "", List.of("scan", relative, "--json"));
        List<String> ids = new ArrayList<>();
        try {
            MAPPER.readTree(answer.output()).path("findings").forEach(item -> ids.add(item.path("ruleId").asText()));
        } catch (IOException e) {
            throw new IllegalStateException("the scanner did not answer with JSON:\n" + answer.output(), e);
        }
        return ids;
    }

    /** One statement, wrapped in the step shape that is supposed to sanction it (or not). */
    private static String step(String factory, String sql, String companion) {
        return "class T {\n    void t() {\n        Scenario.builder(\"s\")\n"
                + "                .step(DbStep." + factory + "(\"orders-db\")\n"
                + "                        .sql(\"" + sql + "\")\n"
                + (companion.isEmpty() ? "" : "                        ." + companion + "\n")
                + "                        .build())\n                .build();\n    }\n}\n";
    }

    @Test
    @DisplayName("the four DB shapes the kit prescribes are not findings — a datasource alias is not a statement")
    void canonicalShapes_areNotFindings(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        assertThat(scan(project, "src/test/java/ru/alfa/qa/OrderScenarioTest.java", CANONICAL))
                .as("the alias in DbStep.query(\"orders-db\") and the value in RestStep…query(\"status\",\"NEW\") went to the SQL classifier as statements")
                .doesNotContain("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("the kit's own template and worked example pass the kit's own scanner")
    void theKitsOwnArtifacts_scanClean() {
        Path root = repositoryRoot();

        for (String artifact : List.of(TEMPLATE, EXAMPLE)) {
            Answer answer = run(root, "", List.of("scan", artifact, "--json", "--exit-code"));
            List<String> blocking = new ArrayList<>();
            try {
                for (JsonNode item : MAPPER.readTree(answer.output()).path("findings")) {
                    if ("BLOCK".equals(item.path("severity").asText())) {
                        blocking.add(item.path("ruleId").asText() + ": " + item.path("message").asText());
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("the scanner did not answer with JSON:\n" + answer.output(), e);
            }
            assertThat(blocking).as("%s is what the kit tells the model to transcribe; refusing it makes the gate unpassable by construction", artifact).isEmpty();
            assertThat(answer.exitCode()).as("%s must not fail the CI form of the same gate", artifact).isZero();
        }
    }

    @Test
    @DisplayName("the sanction is the SDK's own condition, declared beside the statement — without the companion the write is still refused")
    void withoutTheCompanion_theWriteIsStillRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, step("cleanup", "DELETE FROM test_data.orders", "")))
                .as("a bare DELETE with nothing scoping it is exactly what the write-guard refuses")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("write", "INSERT INTO app.clients(name) VALUES ('ACME')", "")))
                .as("an INSERT that declares no key cannot be undone, and nothing un-undoable may reach a shared stand")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("a cleanup that brings its own WHERE stays a finding — whereTestRunId beside it does not launder the statement")
    void authorWhere_isNotLaunderedByTheCompanion(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, step("cleanup", "DELETE FROM test_data.orders WHERE status = 'NEW'", "whereTestRunId(\"test_run_id\")")))
                .as("the SDK appends its own WHERE; a statement that already has one is not the shape db.cleanup produces")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("DDL and multi-statement stay findings whatever is declared beside them")
    void ddlAndMultiStatement_areNeverSanctioned(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, step("cleanup", "TRUNCATE TABLE test_data.orders", "whereTestRunId(\"test_run_id\")")))
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("cleanup", "DROP TABLE test_data.orders", "whereTestRunId(\"test_run_id\")")))
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("write", "INSERT INTO app.clients(id) VALUES (:id); DELETE FROM app.clients", "identifiedBy(\"id\")")))
                .as("multi-statement is refused by the SDK's classifier before any of this is asked")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("the companion is read as CODE — one written in a comment or inside a string sanctions nothing")
    void companionInProse_sanctionsNothing(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";
        String commented = """
                class T {
                    void t() {
                        Scenario.builder("s")
                                .step(DbStep.cleanup("orders-db")
                                        .sql("DELETE FROM test_data.orders")
                                        // TODO: add .whereTestRunId("test_run_id") once the column exists
                                        .build())
                                .build();
                    }
                }
                """;
        String stringly = commented.replace("// TODO: add .whereTestRunId(\"test_run_id\") once the column exists",
                ".param(\"note\", \".whereTestRunId(\")");

        assertThat(scan(project, artifact, commented))
                .as("a promise to scope it later is not scoping it, and the window used to read the promise")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, stringly))
                .as("nor is the same text carried as a string value")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("the sanction belongs to ONE step — the neighbour's key does not vouch for this statement")
    void neighbouringStep_doesNotVouch(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";
        String twoSteps = """
                class T {
                    void t() {
                        Scenario.builder("s")
                                .step(DbStep.cleanup("orders-db")
                                        .sql("DELETE FROM app.audit_log")
                                        .build())
                                .step(DbStep.cleanup("orders-db")
                                        .sql("DELETE FROM test_data.orders")
                                        .whereTestRunId("test_run_id")
                                        .build())
                                .build();
                    }
                }
                """;

        assertThat(scan(project, artifact, twoSteps))
                .as("a window bounded only by the statement terminator let the second step's key answer for the first")
                .containsExactly("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("only INSERT … VALUES is sanctioned: an upsert or INSERT … SELECT touches rows the key cannot undo")
    void upsertAndInsertSelect_areNotSanctioned(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, step("write", "INSERT INTO app.clients(client_id, name) VALUES (:client_id, 'ACME') ON CONFLICT (client_id) DO UPDATE SET name = 'ACME'", "identifiedBy(\"client_id\")")))
                .as("an upsert rewrites a row that already existed, and the undo-log compensation would delete someone else's")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("write", "INSERT INTO app.clients(client_id, name) SELECT id, name FROM prod.clients", "identifiedBy(\"client_id\")")))
                .as("INSERT … SELECT writes as many rows as the source has, and the key names one")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    /**
     * The blanket that ran before every sanction and made three of them unreachable.
     *
     * <p>{@code isPermittedRead} used to answer a second question — a write is fine if the statement
     * binds the run discriminator — and it answered it FIRST, so nothing below was consulted. Two
     * things followed. An {@code INSERT … ON CONFLICT DO UPDATE} passed the moment it mentioned
     * {@code :testRunId}, although core names an upsert tail a mutation of pre-existing rows and the
     * {@code db.write} sanction refuses it in as many words. And {@code MERGE} passed, although core's
     * classifier does not name it at all — its switch is SELECT, WITH, INSERT, UPDATE, DELETE, and
     * everything else is REJECTED — while the guardrail rules ban it outright. The detector table
     * claimed to mirror that classifier and disagreed with it on both.
     */
    @Test
    @DisplayName("a write is no longer waved through for mentioning testRunId — that blanket ran before every sanction")
    void bindingTheDiscriminator_isNoLongerASanctionOfItsOwn(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, step("query", "INSERT INTO app.orders(id, test_run_id) VALUES (:id, :testRunId) ON CONFLICT (id) DO UPDATE SET status = 'X'", "")))
                .as("an upsert binding the discriminator still rewrites rows the run does not own")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("query", "MERGE INTO app.orders o USING s ON (o.id = s.id) WHEN MATCHED THEN UPDATE SET o.status = 'X' WHERE o.test_run_id = :testRunId", "")))
                .as("core's classifier has no MERGE branch at all, so core rejects it — the table said otherwise")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("a read that writes is not a read: SELECT … INTO and a data-modifying WITH are refused as core refuses them")
    void readsThatWrite_areRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, step("query", "SELECT id INTO app.copy FROM app.orders", "")))
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("query", "WITH gone AS (DELETE FROM app.orders RETURNING id) SELECT id FROM gone", "")))
                .as("the statement returns rows and deletes them; core rejects the shape for exactly that")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("query", "WITH recent AS (SELECT id FROM app.orders WHERE id = :id) SELECT id FROM recent", "")))
                .as("an ordinary CTE is a read and must stay silent")
                .doesNotContain("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    /**
     * The rule the checklist has always called a BLOCK and nothing enforced.
     *
     * <p>A seed's INSERT binds {@code :testRunId} by construction, so under the blanket above it passed
     * whether or not it declared the marker column — and an undeclared column is the case the write
     * guard fails closed on at run time, because a seed tagging some other column leaks rows across
     * concurrent runs. The sanction now asks for both halves: the reserved bind IN the statement, and
     * {@code taggedByTestRunId} beside it.
     */
    @Test
    @DisplayName("a db.seed declares the column it tags, or it is not a sanctioned seed")
    void seedWithoutItsTagColumn_isAFinding(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";
        String insert = "INSERT INTO test_data.orders(id, status, test_run_id) VALUES (:id, 'NEW', :testRunId)";

        assertThat(scan(project, artifact, step("seed", insert, "")))
                .as("no taggedByTestRunId: nothing says which column the paired cleanup will reap")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("seed", insert, "taggedByTestRunId(\"test_run_id\")")))
                .as("the sanctioned shape must stay silent, or the kit's own crib is unwritable through its own hook")
                .doesNotContain("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("seed", "INSERT INTO test_data.orders(id, status) VALUES (:id, 'NEW')", "taggedByTestRunId(\"test_run_id\")")))
                .as("the declaration is not enough on its own: the statement has to bind the reserved parameter")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, step("seed", insert + " ON CONFLICT (id) DO UPDATE SET status = 'X'", "taggedByTestRunId(\"test_run_id\")")))
                .as("an upsert is refused in a seed for the same reason it is refused in a write")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("a builder parked in a local variable sanctions nothing — the factory must be part of the same expression")
    void anOpenBuilderInAVariable_sanctionsNothing(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String parked = """
                class V {
                    void t() {
                        var cleanup = DbStep.cleanup("orders-db");
                        jdbc.query("DELETE FROM public.customers");
                        var step = cleanup.sql("DELETE FROM test_data.seed").whereTestRunId("test_run_id").build();
                    }
                }
                """;

        assertThat(scan(project, "src/test/java/ru/alfa/qa/V.java", parked))
                .as("a window that ignored the statement terminator let an open builder vouch for every statement written after it")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("a statement outside the keyword list, or behind a comment, is still asked about — the filter names aliases, it does not whitelist SQL")
    void theNameFilter_isNotAnAllowList(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";

        assertThat(scan(project, artifact, "class T { void t() { db.sql(\"/* housekeeping */ DELETE FROM public.customers\"); } }\n"))
                .as("a leading comment turned deny-by-default into allow-by-default: the value never reached the classifier")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, "class T { void t() { db.sql(\"LOCK TABLE public.orders IN ACCESS EXCLUSIVE MODE\"); } }\n"))
                .as("the classifier refuses what it does not recognise; a keyword whitelist in front of it refuses nothing")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
        assertThat(scan(project, artifact, "class T { void t() { db.sql(\"VACUUM FULL public.orders\"); } }\n"))
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("real SQL handed to something other than DbStep.sql is still seen — dropping the .query anchor blinded the detector")
    void sqlOutsideTheDsl_isStillSeen(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/T.java";
        String raw = "class T {\n    void t() {\n        jdbcTemplate.query(\"DROP TABLE public.audit\");\n    }\n}\n";

        assertThat(scan(project, artifact, raw))
                .as("the alias and the REST parameter had to stop being read as statements; a statement had to keep being one")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("in a declarative document the field named query still carries SQL, and is judged as SQL")
    void inADocument_theQueryFieldIsStillSql(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String document = """
                {
                  "scenarioId": "order-flow",
                  "environment": "ift",
                  "steps": [
                    { "id": "wipe", "type": "db.expectEventually", "datasource": "orders-db", "query": "DELETE FROM app.orders", "timeout": "30s" }
                  ]
                }
                """;

        assertThat(scan(project, "src/test/resources/ai/order-flow.json", document))
                .as("the AI format has no db.cleanup and no companion to declare — a write there is a write")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("the canonical test can actually be written through the write hook")
    void preWrite_acceptsTheCanonicalTest(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        String artifact = "src/test/java/ru/alfa/qa/OrderScenarioTest.java";
        String payload = MAPPER.createObjectNode()
                .put("cwd", project.toString())
                .put("tool_name", "Write")
                .set("tool_input", MAPPER.createObjectNode()
                        .put("file_path", project.resolve(artifact).toString())
                        .put("content", CANONICAL))
                .toString();

        Answer answer = run(project, payload, List.of("pre-write"));

        assertThat(answer.exitCode()).as("the write hook refused the shapes the kit prescribes, and the model had no correct move left").isZero();
    }

    /** What the scanner answered: the exit code is the contract, the text is the report. */
    private record Answer(int exitCode, String output) {
    }
}
