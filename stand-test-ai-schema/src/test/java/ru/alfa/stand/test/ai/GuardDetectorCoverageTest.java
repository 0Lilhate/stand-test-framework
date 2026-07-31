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
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Violations the detector table DECLARED and the scanner never found.
 *
 * <p>Each case here passed a clean scan while {@code safety-review/SKILL.md} listed it as BLOCK, and
 * every one was reachable by ordinary work rather than by contrivance:
 *
 * <ul>
 *   <li>SQL in a Java text block — the extraction regex demanded a quote straight after {@code (},
 *       and {@code """} put two more in the way, so a {@code DROP TABLE} written the way Java 17
 *       writes multi-line SQL was invisible to the highest-severity detector;</li>
 *   <li>the SQL sleep functions of finding 6 — {@code sqlPatterns} was only ever reached through
 *       finding 3's dispatch key, and bailed at once for want of an {@code extract} finding 6 has no
 *       reason to carry. The whole ban was dead code;</li>
 *   <li>Spring's {@code WebClient} and {@code RestTemplate} — the HTTP group knew about
 *       {@code java.net.http}, okhttp and Apache, and not about the client the SDK's own REST adapter
 *       is built on, which is therefore already on the classpath of every starter project;</li>
 *   <li>{@code new DefaultStandClient(} and {@code stand.test.enabled=false} — two bypasses named in
 *       the checklist and absent from the marker list;</li>
 *   <li>every model-walking finding on a YAML scenario — the document model was {@code JSON.parse},
 *       so findings 5, 7 and 16 reported nothing at all on the format the AI-format skill is named
 *       for, while the report still said seventeen of eighteen ran.</li>
 * </ul>
 *
 * <p>The other half of this file is the opposite duty. A detector that fires on the pipeline's own
 * deliverables is worse than one that misses: the safety-review report QUOTES the finding it found,
 * and while findings 1, 3, 6 and 11 ran over prose that report could not be written. So the
 * quotations must pass, and the disclosure findings must still refuse a real credential in the same
 * file — a distinction, not an exemption.
 */
class GuardDetectorCoverageTest {

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

    /** The rule ids the scanner reports at BLOCK severity for one artifact. */
    private static TreeSet<String> blocking(Path directory, String name, String content) {
        return rules(directory, name, content, "BLOCK");
    }

    /** Every rule id reported, at any severity. */
    private static TreeSet<String> reported(Path directory, String name, String content) {
        return rules(directory, name, content, null);
    }

    private static TreeSet<String> rules(Path directory, String name, String content, String severity) {
        Path file = directory.resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage " + file, e);
        }

        List<String> line = List.of("node", repositoryRoot().resolve(GUARD).toString(), "scan", name, "--json");
        String output;
        try {
            Process process = new ProcessBuilder(line).directory(directory.toFile()).start();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the scan did not finish in 60s");
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

        TreeSet<String> found = new TreeSet<>();
        try {
            for (JsonNode finding : MAPPER.readTree(output).path("findings")) {
                if (severity == null || severity.equals(finding.path("severity").asText())) {
                    found.add(finding.path("ruleId").asText());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("the scan did not answer with JSON:\n" + output, e);
        }
        return found;
    }

    @Test
    @DisplayName("SQL in a Java text block is read — it is how Java 17 writes multi-line SQL, and it was invisible")
    void sqlInATextBlock_isExtracted(@TempDir Path temporary) {
        String destructive = """
                class DropTest {
                    void t() {
                        DbStep.query("orders-db").sql(\"""
                            DROP TABLE app.orders
                            \""").build();
                    }
                }
                """;

        assertThat(blocking(temporary, "DropTest.java", destructive))
                .as("the extraction regex wanted a quote straight after '(' and the text block put two more in the way")
                .contains("DESTRUCTIVE_SQL_WITHOUT_ALLOW");
    }

    @Test
    @DisplayName("a sanctioned cleanup keeps its sanction when its statement is written as a text block")
    void sanctionedCleanupInATextBlock_isSilent(@TempDir Path temporary) {
        String sanctioned = """
                class CleanupTest {
                    void t() {
                        DbStep.cleanup("orders-db").sql(\"""
                            DELETE FROM test_data.orders
                            \""").whereTestRunId("test_run_id").build();
                    }
                }
                """;

        assertThat(blocking(temporary, "CleanupTest.java", sanctioned))
                .as("reading a new spelling must not cost the SDK's own sanctioned shapes their sanction")
                .isEmpty();
    }

    @Test
    @DisplayName("the SQL sleep functions of finding 6 are actually asked — the whole ban was dead code")
    void sqlSleepFunctions_areFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "SleepTest.java",
                "class SleepTest {\n    void t() { DbStep.query(\"db\").sql(\"SELECT pg_sleep(5)\").build(); }\n}\n"))
                .as("sqlPatterns was reachable only through finding 3's dispatch key, and bailed for want of an extract")
                .contains("THREAD_SLEEP");

        assertThat(blocking(temporary, "sleep-scenario.json",
                "{\"id\":\"s\",\"environment\":\"ift\",\"steps\":[{\"id\":\"a\",\"type\":\"db.expectEventually\","
                        + "\"timeout\":\"30s\",\"query\":\"SELECT id FROM t WHERE pg_sleep(5) IS NULL\"}]}"))
                .as("the same statement in the declarative format is the same violation")
                .contains("THREAD_SLEEP");
    }

    @Test
    @DisplayName("Awaitility is a second wait mechanism beside the SDK's one, and the import alone says so")
    void awaitility_isFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "AwaitTest.java",
                "import org.awaitility.Awaitility;\n\nclass AwaitTest {\n    void t() { Awaitility.await().until(() -> true); }\n}\n"))
                .contains("THREAD_SLEEP");
    }

    @Test
    @DisplayName("Spring's own HTTP clients count as reaching the transport — the SDK is built on one of them")
    void springHttpClients_areFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "SpringClientTest.java",
                "import org.springframework.web.reactive.function.client.WebClient;\n"
                        + "import org.springframework.web.client.RestTemplate;\n\n"
                        + "class SpringClientTest {\n    void t() { WebClient.create(\"x\"); new RestTemplate(); }\n}\n"))
                .contains("DIRECT_TRANSPORT_CLIENT");

        assertThat(blocking(temporary, "SpringBootTest.java",
                "import org.springframework.boot.test.context.SpringBootTest;\n\n"
                        + "@SpringBootTest\nclass SpringBootTest {\n    void t() { }\n}\n"))
                .as("the sanctioned wiring of a starter project must not read as reaching the transport")
                .isEmpty();
    }

    @Test
    @DisplayName("the two validator bypasses the checklist named and the marker list did not carry")
    void namedValidatorBypasses_areFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "ClientTest.java",
                "class ClientTest {\n    void t() { var c = new DefaultStandClient(registry); }\n}\n"))
                .contains("VALIDATOR_BYPASS");

        assertThat(blocking(temporary, "DisabledTest.java",
                "@SpringBootTest(properties = \"stand.test.enabled=false\")\nclass DisabledTest {\n    void t() { }\n}\n"))
                .as("with the autoconfiguration off there is nothing left to validate and nobody left to do it")
                .contains("VALIDATOR_BYPASS");
    }

    @Test
    @DisplayName("a YAML scenario is walked as a model — the format the AI-format skill is named for was unchecked")
    void yamlScenario_isWalked(@TempDir Path temporary) {
        String hostile = """
                id: s
                environment: ift
                steps:
                  - id: consume
                    type: kafka.expect
                    topic: orders
                    key: fixed-key
                  - id: poll
                    type: rest.expectEventually
                    service: orders
                    path: /api/orders
                """;

        assertThat(blocking(temporary, "hostile.yaml", hostile))
                .as("neither step declares a bounded wait, and the kafka step cannot tell its own run's messages "
                        + "from another's — both BLOCK, and both reported nothing while the model was JSON.parse")
                .contains("UNBOUNDED_TIMEOUT", "KAFKA_EXPECT_WITHOUT_DISCRIMINATOR");

        assertThat(blocking(temporary, "script.yaml",
                "id: s\nenvironment: ift\nsteps:\n  - id: a\n    type: rest.expectEventually\n"
                        + "    timeout: 10s\n    script: return 1 == 1\n"))
                .contains("SCRIPT_IN_DECLARATIVE_DOCUMENT");
    }

    @Test
    @DisplayName("a correct YAML scenario is silent, and so is a knowledge-base file that merely uses the same words")
    void yamlModelWalk_doesNotFireOnOrdinaryWork(@TempDir Path temporary) {
        String correct = """
                id: order-flow
                environment: ift
                steps:
                  - id: consume
                    type: kafka.expect
                    topic: orders
                    correlation:
                      fromContext: true
                    timeout: 30s
                  - id: poll
                    type: rest.expectEventually
                    service: orders
                    path: /api/orders
                    timeout: 45s
                """;
        assertThat(blocking(temporary, "correct.yaml", correct)).isEmpty();

        // The reason the YAML branch asks whether the document calls itself a scenario: `code`, `key`
        // and `bootstrap-servers` are ordinary words in the base and in the registry, and a word search
        // over those turns the ordinary work of three skills into blocking findings.
        assertThat(blocking(temporary, "knowledge-base/services/orders.yml",
                "services:\n  - id: orders\n    code: PRICEASAVE\n    key: order-id\n"))
                .as("a knowledge-base entry is not an executable scenario and must not be read as one")
                .isEmpty();
        assertThat(blocking(temporary, "stand-test-environments.yml",
                "environments:\n  ift:\n    topics:\n      orders:\n        bootstrap-servers-ref: KAFKA_BOOTSTRAP\n"))
                .as("the environment registry is the file /stand-test-generate-env writes by design")
                .isEmpty();
    }

    @Test
    @DisplayName("the pipeline's own report may quote the violation it found — a gate that refuses it gets switched off")
    void proseMayQuoteAFinding(@TempDir Path temporary) {
        String report = """
                # Safety review — order-flow

                Вердикт: BLOCK.

                | # | Находка | Файл |
                |---|---|---|
                | 1 | адрес вместо алиаса: `https://stand-ift.example.ru/api/orders` | OrderTest.java:41 |
                | 6 | ручное ожидание: `Thread.sleep(5000)` | OrderTest.java:58 |
                | 3 | `DELETE FROM app.orders WHERE status = 'NEW'` вне db.cleanup | OrderTest.java:71 |
                | 11 | `.header("X-Correlation-Id", "fixed-0001")` | OrderTest.java:33 |

                Источник кейса: https://confluence.example.ru/pages/12345
                """;

        assertThat(blocking(temporary, "safety-review-report.md", report))
                .as("markdown is on no classpath: an address here is a quotation, not a route to a stand — and while "
                        + "these four ran over prose the gate refused the very document it demands")
                .isEmpty();
    }

    @Test
    @DisplayName("a real credential in a report is still refused — disclosure is not delivery, and prose discloses")
    void proseStillRefusesASecret(@TempDir Path temporary) {
        assertThat(blocking(temporary, "leak.md",
                "# Отчёт\n\nТокен стенда: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.abcdef1234567890\n"))
                .as("a credential is committed wherever it is written, and markdown commits exactly as well as Java")
                .contains("SECRET_IN_SOURCE");

        assertThat(reported(temporary, "grep-note.md",
                "Проверить грепом: `Authorization|Bearer |Basic |api[-_]?key`.\n"))
                .as("the pattern that DESCRIBES the rule is not the rule being broken — `\\S+` matched '|Basic' and "
                        + "made the kit's own checklists fail its own scan")
                .doesNotContain("SECRET_IN_SOURCE");
    }

    @Test
    @DisplayName("delivery is still refused where delivery is possible — the distinction is by format, not by file name")
    void deliveryFormatsAreUnchanged(@TempDir Path temporary) {
        assertThat(blocking(temporary, "application.yml",
                "stand:\n  test:\n    environments:\n      ift:\n        services:\n          orders:\n"
                        + "            base-url: https://orders-ift.example.ru\n"))
                .contains("HARDCODED_STAND_URL");

        assertThat(blocking(temporary, "local.properties", "jdbc.url=jdbc:postgresql://db-ift.example.ru:5432/app\n"))
                .as("a properties file is not prose: it is loaded, and the address in it reaches a stand")
                .contains("HARDCODED_STAND_URL");

        assertThat(blocking(temporary, "SleepInJava.java",
                "class SleepInJava {\n    void t() throws Exception { Thread.sleep(5000); }\n}\n"))
                .contains("THREAD_SLEEP");
    }

    /**
     * A build file the detector named and could never read.
     *
     * <p>{@code pom.xml} has been in finding 15's own {@code buildFiles} list from the start, and the
     * only extraction spec was Gradle's quoted {@code "group:artifact:version"} — which a pom never
     * writes. So a Maven consumer got a dependency check that reported nothing while looking exactly
     * like one that had run, which is the dead branch this table calls worse than an absent one.
     *
     * <p>The second assertion is why the Maven spec is scoped to {@code <dependency>} blocks: a pom
     * names a {@code groupId} for the project itself and another for its parent, and reading those as
     * dependencies would report every Maven project as carrying two unsanctioned ones.
     */
    @Test
    @DisplayName("a pom declares dependencies too — the detector listed pom.xml and could only read Gradle")
    void mavenCoordinates_areRead(@TempDir Path temporary) {
        String pom = """
                <project>
                  <groupId>ru.alfa.qa</groupId>
                  <artifactId>qa-tests</artifactId>
                  <parent><groupId>ru.alfa.platform</groupId><artifactId>parent</artifactId></parent>
                  <dependencies>
                    <dependency>
                      <groupId>ru.alfa.stand.test</groupId>
                      <artifactId>stand-test-sdk</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.squareup.okhttp3</groupId>
                      <artifactId>okhttp</artifactId>
                      <version>4.12.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """;

        assertThat(reported(temporary, "pom.xml", pom))
                .as("okhttp exists in a test only to reach a transport directly")
                .contains("UNSANCTIONED_DEPENDENCY");

        String clean = """
                <project>
                  <groupId>ru.alfa.qa</groupId>
                  <artifactId>qa-tests</artifactId>
                  <parent><groupId>ru.alfa.platform</groupId><artifactId>parent</artifactId></parent>
                  <dependencies>
                    <dependency>
                      <groupId>ru.alfa.stand.test</groupId>
                      <artifactId>stand-test-sdk</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>io.qameta.allure</groupId>
                      <artifactId>allure-junit5</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        assertThat(reported(temporary, "clean-pom/pom.xml", clean))
                .as("the project's own groupId and its parent's are not dependencies, and a check that says they are "
                        + "fires on every Maven project it was installed to protect")
                .doesNotContain("UNSANCTIONED_DEPENDENCY");
    }

    @Test
    @DisplayName("every detector that skips prose says which kinds it skips, and no disclosure finding is among them")
    void onlyDeliveryFindingsSkipProse() {
        Path table = repositoryRoot().resolve("docs/ai-agent/.claude/hooks/detectors.json");
        JsonNode detectors;
        try {
            detectors = MAPPER.readTree(Files.readString(table, StandardCharsets.UTF_8)).path("detectors");
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + table, e);
        }

        List<String> skipsProse = new ArrayList<>();
        detectors.forEach(detector -> {
            for (JsonNode kind : detector.path("notOn")) {
                if ("prose".equals(kind.asText())) {
                    skipsProse.add(detector.path("ruleId").asText());
                }
            }
        });

        assertThat(skipsProse)
                .as("these ask whether an artifact DELIVERS something to a stand, which markdown cannot — plus the "
                        + "transport detector, whose only two branches are java imports and parsed documents, so on "
                        + "prose it was present and inert while the coverage line counted it as run")
                .containsExactlyInAnyOrder("HARDCODED_STAND_URL", "DESTRUCTIVE_SQL_WITHOUT_ALLOW",
                        "THREAD_SLEEP", "DIRECT_TRANSPORT_CLIENT", "HARDCODED_CORRELATION_ID");
        assertThat(skipsProse)
                .as("a disclosure finding must never be waived on prose: the file is committed either way, and this "
                        + "is the line between a distinction and an exemption")
                .doesNotContain("SECRET_IN_SOURCE", "MASKED_SECRET", "PII_IN_FIXTURE");
    }
}
