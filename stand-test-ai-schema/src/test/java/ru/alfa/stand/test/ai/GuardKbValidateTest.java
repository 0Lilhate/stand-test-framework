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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The knowledge base checked where it is used, rather than only where it was written.
 *
 * <p>In this repository the KB is validated against its twenty schemas by a Gradle test. That test
 * does not travel with the bundle: at a consumer the schemas are documents nobody executes, so the
 * base drifts exactly where it matters and announces itself as a generated test failing against a
 * stand for a reason that looks like anything else.
 *
 * <p>Half of these cases are negative on purpose. A checker that fires on correct content is one
 * people switch off, taking the accurate findings with it — and the first run against the kit's own
 * reference base produced three false findings and one true one. Each false one is pinned below, so
 * the fix cannot quietly regress: {@code schemaRef} is a classpath resource rather than an
 * environment variable, a URL in a COMMENT documents the shape of a variable rather than hardcoding
 * an address, and an entry that carries {@code alias:} beside its {@code id:} is one entry seen
 * twice rather than a duplicate.
 */
class GuardKbValidateTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLEAN_SERVICES = """
            # Curated services. The KB stores env-var NAMES, never values.
            services:
              - id: client-service
                alias: client-service
                name: Client Service
                auth:
                  scheme: BASIC
                  usernameRef: CLIENT_SERVICE_USER
                  passwordRef: CLIENT_SERVICE_PASSWORD
            """;

    private static final String REGISTRY = """
            environments:
              ift:
                services:
                  client-service:
                    base-url-ref: CLIENT_SERVICE_URL
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

    private static void write(Path project, String relative, String content) {
        Path file = project.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not stage " + file, e);
        }
    }

    private static JsonNode run(Path project, String subcommand) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString(), subcommand, "--json"));
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the guard did not finish in 60s");
            return MAPPER.readTree(output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine, so the guard could not be executed: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<String> ruleIds(JsonNode report) {
        List<String> ids = new ArrayList<>();
        report.path("findings").forEach(item -> ids.add(item.path("ruleId").asText()));
        return ids;
    }

    @Test
    @DisplayName("a correct knowledge base produces no findings, and the report names what it did not check")
    void cleanBase_isSilentAndHonest(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/services/client.yml", CLEAN_SERVICES);

        JsonNode report = run(project, "kb-validate");

        assertThat(ruleIds(report)).isEmpty();
        assertThat(report.path("notChecked")).as("a clean report from part of the contract must not read as a clean report from all of it").isNotEmpty();
        assertThat(report.path("files").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a ref that carries a placeholder instead of a bare NAME is the double-resolution trap, and is a finding")
    void refWithPlaceholder_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/services/client.yml", CLEAN_SERVICES.replace("passwordRef: CLIENT_SERVICE_PASSWORD", "passwordRef: ${CLIENT_SERVICE_PASSWORD}"));

        assertThat(ruleIds(run(project, "kb-validate")))
                .as("Spring collapses the placeholder before the SDK sees the ref, which then reads the VALUE as a variable name")
                .contains("KB_REF_NOT_BARE_NAME");
    }

    @Test
    @DisplayName("schemaRef points at a classpath resource and is none of this checker's business")
    void schemaRef_isNotAnEnvironmentVariable(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/endpoints/example.yml", """
                endpoints:
                  - id: create-request
                    request:
                      schemaRef: schemas/rest/create-request.request.schema.json
                """);

        assertThat(ruleIds(run(project, "kb-validate")))
                .as("the reference base carries these, and a rule that condemns them is a rule that gets switched off")
                .doesNotContain("KB_REF_NOT_BARE_NAME");
    }

    @Test
    @DisplayName("a URL in a comment documents a variable's shape; a credential in a comment is committed all the same")
    void comments_areJudgedByWhatTheyCanRuin(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/services/documented.yml",
                "# export CLIENT_SERVICE_URL=\"http://<stand-host>:<port>\"\n" + CLEAN_SERVICES);
        assertThat(ruleIds(run(project, "kb-validate"))).isEmpty();

        write(project, "knowledge-base/services/leaky.yml", "# password: hunter2reallysecret\n" + CLEAN_SERVICES.replace("client-service", "other-service"));
        assertThat(ruleIds(run(project, "kb-validate"))).contains("KB_SECRET_IN_COMMENT");
    }

    @Test
    @DisplayName("an entry named after production is a finding wherever it sits in the base")
    void productionName_isAFinding(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/environments/envs.yml", "environments:\n  - id: prod\n    description: nope\n");

        assertThat(ruleIds(run(project, "kb-validate"))).contains("KB_PRODUCTION_ENVIRONMENT");
    }

    @Test
    @DisplayName("the same id in two files is a finding; the same id spelled id+alias in one entry is not")
    void duplicateIds_areCountedPerEntry(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/services/a.yml", CLEAN_SERVICES);
        assertThat(ruleIds(run(project, "kb-validate")))
                .as("id: и alias: одной записи — это одна запись")
                .doesNotContain("KB_DUPLICATE_ID");

        write(project, "knowledge-base/services/b.yml", CLEAN_SERVICES);
        assertThat(ruleIds(run(project, "kb-validate")))
                .as("two files describing one alias resolve by file order, which is to say at random")
                .contains("KB_DUPLICATE_ID");
    }

    @Test
    @DisplayName("the SDK-internal literal:// marker is refused in the base as it is in user config")
    void literalMarker_isRefused(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/services/client.yml", CLEAN_SERVICES.replace("CLIENT_SERVICE_USER", "literal://svc-user"));

        assertThat(ruleIds(run(project, "kb-validate"))).contains("KB_LITERAL_MARKER");
    }

    @Test
    @DisplayName("an alias the base describes but the registry never declares is a test that fails at resolution")
    void aliasWithoutRegistryEntry_isReported(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "stand-test-environments.yml", REGISTRY);
        write(project, "knowledge-base/services/client.yml", CLEAN_SERVICES);
        assertThat(ruleIds(run(project, "alias-check"))).as("this alias IS declared by the registry").isEmpty();

        write(project, "knowledge-base/services/orphan.yml", CLEAN_SERVICES.replace("client-service", "order-service"));
        JsonNode report = run(project, "alias-check");

        assertThat(ruleIds(report)).containsExactly("KB_ALIAS_NOT_IN_REGISTRY");
        assertThat(report.path("kinds").path("service").path("unregistered").get(0).asText()).isEqualTo("order-service");
    }

    @Test
    @DisplayName("without a registry there is nothing to compare against, and no alias is condemned for it")
    void withoutARegistry_nothingIsCondemned(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "knowledge-base/services/client.yml", CLEAN_SERVICES);

        JsonNode report = run(project, "alias-check");

        assertThat(report.path("registry").isNull()).isTrue();
        assertThat(ruleIds(report)).as("an absent registry is a fact about the project, not a defect in every KB entry").isEmpty();
    }
}
