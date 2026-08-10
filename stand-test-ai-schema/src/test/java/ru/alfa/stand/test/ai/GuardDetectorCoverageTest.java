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

    /** {@code rules} for a Page Object judged against a discovery report (finding 25, gate U1). */
    private static TreeSet<String> parity(Path directory, String pageName, String pageContent, String reportContent) {
        Path page = directory.resolve(pageName);
        Path report = directory.resolve("UiDiscoveryReport.md");
        try {
            Files.createDirectories(page.getParent());
            Files.writeString(page, pageContent, StandardCharsets.UTF_8);
            Files.writeString(report, reportContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the parity fixture", e);
        }

        List<String> line = List.of("node", repositoryRoot().resolve(GUARD).toString(),
                "scan", pageName, "--discovery", "UiDiscoveryReport.md", "--json");
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
                if ("UI_DISCOVERY_PARITY".equals(finding.path("ruleId").asText())) {
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

    @Test
    @DisplayName("the UI half of the sleep gate — driver-level waits the browser tempts the agent into (U6)")
    void uiDriverWait_isAThreadSleep(@TempDir Path temporary) {
        assertThat(blocking(temporary, "DriverWaitTest.java",
                "class DriverWaitTest {\n    void t() { page.waitForTimeout(2000); page.waitForSelector(\"#x\"); }\n}\n"))
                .contains("THREAD_SLEEP");

        assertThat(blocking(temporary, "WithDoubleWait.java",
                "class WithDoubleWait {\n    void t() { UiStep.expectEventually(\"p\", L).withinSeconds(5).build(); }\n}\n"))
                .as("a bounded within() is the sanctioned wait, and the driver-word detector must not read .withinSeconds as page.waitFor")
                .doesNotContain("THREAD_SLEEP");
    }

    @Test
    @DisplayName("the server's comment may explain why XPath is absent; only real XPath signatures are findings (U7)")
    void xpathLocator_isFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "XpathProbe.java",
                "class XpathProbe {\n    void t() { var l = by.xpath(\"//*[@id='x']\"); }\n}\n"))
                .contains("XPATH_LOCATOR");

        assertThat(blocking(temporary, "Xsaves.java",
                "class Xsaves {\n    void t() { /* no xpath spelling here */ }\n}\n"))
                .as("the projection without comments keeps the kit's own ban text out of its own findings")
                .doesNotContain("XPATH_LOCATOR");
    }

    @Test
    @DisplayName("a locator declared off-page is a finding; a Page Object keeps its own locators (U2)")
    void uiLocatorOutsidePages_isFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "BadConsumer.java",
                "class BadConsumer {\n    static final UiLocator L = UiLocator.testId(\"x\");\n}\n"))
                .contains("UI_LOCATOR_OUTSIDE_PAGES");

        assertThat(blocking(temporary, "ui/pages/GoodPage.java",
                "package a.b.ui.pages;\nclass GoodPage {\n    static final UiLocator L = UiLocator.label(\"X\");\n}\n"))
                .as("a file under ui/pages or declaring a ui.pages package owns its locators")
                .isEmpty();
    }

    @Test
    @DisplayName("sign-in without a role bypasses the pool and the validator — login(...) without .role(...) is a finding (U5)")
    void uiLoginWithoutRole_isFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "NoRole.java",
                "class NoRole {\n    void t() { UiStep.login(\"portal\").build(); }\n}\n"))
                .contains("UI_LOGIN_WITHOUT_ROLE");

        assertThat(blocking(temporary, "WithRole.java",
                "class WithRole {\n    void t() { UiStep.login(\"portal\").role(\"client\").build(); }\n}\n"))
                .doesNotContain("UI_LOGIN_WITHOUT_ROLE");
    }

    @Test
    @DisplayName("${…} in a ui.open path or in an assertion's expected value is not resolved — finding (U9)")
    void uiTemplateInOpenOrAssert_isFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "Templated.java",
                "class Templated {\n    void t() {\n        UiStep.open(\"p\", \"/apps/${tenantId}\");\n"
                        + "        UiStep.expect(\"p\", L).assertValue(\"${expected}\").build();\n}\n}\n"))
                .contains("UI_OPEN_OR_ASSERT_TEMPLATE");

        assertThat(blocking(temporary, "Resolved.java",
                "class Resolved {\n    void t() {\n        String v = \"ext-\" + testRunId();\n "
                        + "       UiStep.open(\"p\", \"/apps/123\").build();\n}\n}\n"))
                .as("a value built from a variable or a literal, not a ${…} placeholder, is resolved and not a finding")
                .doesNotContain("UI_OPEN_OR_ASSERT_TEMPLATE");
    }

    @Test
    @DisplayName("an address reproduced in a Ui*Report.md is a delivery channel — a focused detector closes U3's report half (U3)")
    void uiReportAddress_isFound(@TempDir Path temporary) {
        assertThat(blocking(temporary, "UiDiscoveryReport.md",
                "# UiDiscoveryReport\n\nurl: https://prod.example-bank.ru/requests\n"))
                .as("a Ui*Report.md must never reproduce a live stand address")
                .contains("UI_REPORT_STAND_ADDRESS");

assertThat(blocking(temporary, "safety-review-report.md",
                "## Находка\nадрес https://prod.example-bank.ru/requests из транскрипта\n"))
                .as("an ordinary markdown report may still describe a found address — that is finding 1's reason to skip prose")
                .doesNotContain("UI_REPORT_STAND_ADDRESS");
    }

    /** A generation report with all eight sections and a section 8 that names its snapshot (gate U16). */
    private static final String COMPLETE_GENERATION_REPORT = """
            # UI Generation Report: demo

            ## 1. What is covered
            one row per expectation.
            ## 2. What is not covered, and why
            none.
            ## 3. Assumptions
            none.
            ## 4. Fragile locators
            none.
            ## 5. How the UI is bound to the backend
            correlation.
            ## 6. Gate results
            safety PASS, quality PASS.
            ## 7. Files created or changed
            ui/pages/Page.java
            ## 8. Original generation (diff base for KPI-4)

            | Field | Value |
            |---|---|
            | Hash file | `original.sha256` |
            """;

    @Test
    @DisplayName("a generation report missing a section is blocked — gate U16 (finding 26, F006)")
    void uiGenerationReport_catchesAMissingSection(@TempDir Path temporary) {
        String truncated = COMPLETE_GENERATION_REPORT.replace("## 6. Gate results\nsafety PASS, quality PASS.\n", "");
        assertThat(blocking(temporary, "UiGenerationReport.md", truncated))
                .as("section 6 carries the gate verdicts; a report handed over without it presents an unverified artifact as reviewed")
                .contains("UI_GENERATION_REPORT_INCOMPLETE");
    }

    @Test
    @DisplayName("a generation report naming a snapshot that is not on disk is blocked — KPI-4 has nothing to diff (U16 negative)")
    void uiGenerationReport_catchesAnAbsentSnapshot(@TempDir Path temporary) {
        assertThat(blocking(temporary, "UiGenerationReport.md", COMPLETE_GENERATION_REPORT))
                .as("every section is present, but original.sha256 was never written: the diff base KPI-4 is measured against does not exist")
                .contains("UI_GENERATION_REPORT_INCOMPLETE");
    }

    @Test
    @DisplayName("a complete generation report whose snapshot exists is silent (U16 clean case)")
    void uiGenerationReport_completeReportWithSnapshotIsSilent(@TempDir Path temporary) {
        try {
            Files.writeString(temporary.resolve("original.sha256"),
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  ui/pages/Page.java\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the snapshot", e);
        }
        assertThat(reported(temporary, "UiGenerationReport.md", COMPLETE_GENERATION_REPORT))
                .as("eight sections and a snapshot that is really there — the shape the gate exists to let through")
                .doesNotContain("UI_GENERATION_REPORT_INCOMPLETE");
    }

    @Test
    @DisplayName("the kit's own report template is not a report and stays silent (U16 false-positive guard)")
    void uiGenerationReport_theTemplateIsNotAReport(@TempDir Path temporary) {
        String template = read(repositoryRoot()
                .resolve("docs/ai-agent/.claude/skills/stand-test-ui-generation-report/ui-generation-report-template.md"));
        assertThat(reported(temporary, "ui-generation-report-template.md", template))
                .as("the template carries unfilled <scenario-id> placeholders by design; a detector that refused it would refuse the kit's own asset")
                .doesNotContain("UI_GENERATION_REPORT_INCOMPLETE");
    }

    @Test
    @DisplayName("a discovery report is not judged by the generation report's sections (U16 scoping)")
    void uiGenerationReport_doesNotJudgeADiscoveryReport(@TempDir Path temporary) {
        assertThat(reported(temporary, "UiDiscoveryReport.md", DISCOVERY_REPORT))
                .as("the two reports have different shapes; applying one's structure to the other reports a defect that is not there")
                .doesNotContain("UI_GENERATION_REPORT_INCOMPLETE");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    private static final String DISCOVERY_REPORT = """
            # UI Discovery Report: demo / client-portal
            ## Elements observed
            | # | Case name | `data-testid` | role + accessible name | label | Chosen locator | Why not |
            |---|---|---|---|---|---|---|
            | 1 | поле «Статус» | `request-status` | — | — | `testId=request-status` | — |
            | 2 | кнопка «Отправить» | — | `button` / `Отправить` | — | `role=button:Отправить` | — |
            """;

    @Test
    @DisplayName("a locator the discovery report never observed is a fabrication — gate U1 (finding 25, S021)")
    void uiDiscoveryParity_catchesAnUntracedLocator(@TempDir Path temporary) {
        String page = """
                package demo.ui.pages;
                public class Page {
                    static final UiLocator STATUS = UiLocator.testId("requestStatus");
                    static final UiLocator SEND = UiLocator.role("button", "Отправить");
                }
                """;
        assertThat(parity(temporary, "ui/pages/Page.java", page, DISCOVERY_REPORT))
                .as("the report records `testId=request-status`; `requestStatus` does not trace to any row — a released rename or an invention")
                .containsExactly("UI_DISCOVERY_PARITY");
    }

    @Test
    @DisplayName("a Page Object whose every locator is a Chosen-locator row is silent (S21)")
    void findingPageParity_cleanPageIsSilent(@TempDir Path temporary) {
        String page = """
                package demo.ui.pages;
                public class Page {
                    static final UiLocator STATUS = UiLocator.testId("request-status");
                    static final UiLocator SEND = UiLocator.role("button", "Отправить");
                }
                """;
        assertThat(parity(temporary, "ui/pages/Page.java", page, DISCOVERY_REPORT))
                .as("each locator maps onto a row of the report; the clean build is exactly what the gate exists to keep")
                .isEmpty();
    }

    @Test
    @DisplayName("a Page Object scanned against a discovery report that does not exist is blocked — claimed discovery evidence is not passed (S21 negative)")
    void findingPageUi_absentReportIsBlocked(@TempDir Path temporary) {
        Path page = temporary.resolve("ui/pages/Page.java");
        try {
            Files.createDirectories(page.getParent());
            Files.writeString(page,
                    "package demo.ui.pages;\npublic class Page {\n    static final UiLocator S = UiLocator.testId(\"request-status\");\n}\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage the page", e);
        }
        String output = run(temporary, List.of("scan", "ui/pages/Page.java", "--discovery", "UiDiscoveryReport.md", "--json"));
        assertThat(output)
                .as("the caller named a report that is not on disk; that is not a scan over discovery evidence, and the parity gate refuses it")
                .contains("UI_DISCOVERY_PARITY");
    }

    @Test
    @DisplayName("a credential VALUE in a block-YAML registry is refused — the form the first pattern could not see (finding 2)")
    void blockYamlRegistryCredentialIsRefused(@TempDir Path temporary) {
        // Registry format version 4 made this expressible for the first time: `${VAR:value}` puts the
        // value in the file, not the name of a fallback variable, so the SDK's standing rule ("never
        // give a default to a password") stopped being a property of the format and became a habit.
        assertThat(blocking(temporary, "application.yml",
                "stand:\n  test:\n    version: 4\n    environments:\n      ift:\n        ui-applications:\n          portal:\n"
                        + "            auth:\n              scheme: FORM\n              credentials-password: ${web_password:hunter2}\n"))
                .as("the first pattern demands quotes on BOTH sides — a JSON-shaped line — and an environment registry is written as "
                        + "block YAML with no quotes at all, so the one document whose whole job is to hold references could hold a "
                        + "password and pass the gate")
                .contains("SECRET_IN_SOURCE");

        assertThat(blocking(temporary, "plain.yml", "auth:\n  password: hunter2\n"))
                .as("a bare literal is the same defect written shorter")
                .contains("SECRET_IN_SOURCE");
    }

    @Test
    @DisplayName("the sanctioned registry spellings stay silent — a gate that refuses the correct form is a gate that gets switched off")
    void sanctionedRegistrySpellingsAreSilent(@TempDir Path temporary) {
        assertThat(reported(temporary, "clean.yml",
                "auth:\n  credentials-username: PORTAL_USERNAME\n  credentials-password: ${PORTAL_PASSWORD}\n"
                        + "  password-ref: LEGACY_PASSWORD\n  token-ref: SERVICE_TOKEN\n"))
                .as("a bare NAME and a ${NAME} without a default are references, not values — refusing them would drive authors back "
                        + "to writing the secret itself")
                .doesNotContain("SECRET_IN_SOURCE");

        assertThat(reported(temporary, "locators.yml",
                "auth:\n  login:\n    username-locator: css=#username\n    password-locator: testId=login-password\n"))
                .as("`password-locator` addresses the FIELD, not the credential — the only false positive a measurement over the whole "
                        + "repository turned up, and the reason the key exemption names locators")
                .doesNotContain("SECRET_IN_SOURCE");

        assertThat(reported(temporary, "username.yml", "auth:\n  credentials-username: ${web_username:portal_admin}\n"))
                .as("the SDK's rule is about the PASSWORD. A login with a default is a documented trade-off, not a leak, and a finding "
                        + "here would be the detector inventing a rule the SDK does not have")
                .doesNotContain("SECRET_IN_SOURCE");
    }

    /** Runs the scanner against a staged directory and returns its JSON output, or aborts if node is absent. */
    private static String run(Path directory, List<String> args) {
        List<String> line = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString()));
        line.addAll(args);
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
        return output;
    }
}
