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
 * The machine half of the cold start: which aliases the environment registry attests.
 *
 * <p>A consumer installs the kit into an empty knowledge base, and every contract detail correctly
 * resolves to {@code missing} — which turns the first case into an interrogation. The one safe
 * relaxation is an ALIAS the registry declares: the SDK does not start without that file, so a person
 * curated it, and a case naming that system may proceed on a recorded assumption.
 *
 * <p>Safe only while "the registry attests it" is a fact read off the file. Stated in a skill and
 * left to recollection it becomes "the registry probably has it", which is the invention the whole
 * knowledge base exists to prevent — so the list comes from here, and this test is what says the list
 * is right.
 *
 * <p>Note what the relaxation is NOT: no registry anywhere attests a path, a field, a table or a gRPC
 * method, so those keep blocking. That boundary is prose in the skill; what a test can hold is the
 * narrower claim that only section KEYS become aliases and their configuration never does.
 */
class GuardKbStatusTest {

    private static final String GUARD = "docs/ai-agent/.claude/hooks/stand-guard.mjs";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REGISTRY = """
            environments:
              ift:
                services:
                  client-service:
                    base-url-ref: CLIENT_SERVICE_URL
                    correlation: { source: HEADER, name: X-Correlation-Id }
                    auth:
                      scheme: BASIC
                      username-ref: CLIENT_USER
                      password-ref: CLIENT_PASSWORD
                topics:
                  response-topic:
                    name: pakt.response.ift
                datasources:
                  main-db:
                    url-ref: MAIN_DB_URL
                    allowed-schemas: [test_data]
                    write-allowed: true
                grpc-targets:
                  billing-grpc:
                    target-ref: BILLING_GRPC_TARGET
                kafka-cluster:
                  bootstrap-servers-ref: KAFKA_BOOTSTRAP
            """;

    private static final String SPRING_REGISTRY = """
            stand:
              test:
                environments:
                  ift:
                    services:
                      order-service:
                        base-url: ${ORDER_SERVICE_URL:}
                    topics:
                      order-events:
                        name: order.events.ift
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

    private static JsonNode status(Path project) {
        List<String> command = new ArrayList<>(List.of("node", repositoryRoot().resolve(GUARD).toString(), "kb-status", "--json"));
        try {
            Process process = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Assumptions.assumeTrue(process.waitFor(60, TimeUnit.SECONDS), "the guard did not finish in 60s");
            assertThat(process.exitValue()).as("a report is not a refusal: kb-status never blocks anything").isZero();
            return MAPPER.readTree(output);
        } catch (IOException e) {
            Assumptions.abort("node is not available on this machine, so the guard could not be executed: " + e.getMessage());
            throw new IllegalStateException("unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<String> textAt(JsonNode node, String kind, String field) {
        List<String> values = new ArrayList<>();
        node.path("kinds").path(kind).path(field).forEach(item -> values.add(item.asText()));
        return values;
    }

    @Test
    @DisplayName("every alias of the registry is reported, and an empty knowledge base makes all of them the bootstrap worklist")
    void aliases_areReadOffTheRegistry(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "src/test/resources/stand-test-environments.yml", REGISTRY);

        JsonNode status = status(project);

        assertThat(status.path("registry").asText()).isEqualTo("src/test/resources/stand-test-environments.yml");
        assertThat(textAt(status, "service", "registry")).containsExactly("client-service");
        assertThat(textAt(status, "kafka-topic", "registry")).containsExactly("response-topic");
        assertThat(textAt(status, "datasource", "registry")).containsExactly("main-db");
        assertThat(textAt(status, "grpc-target", "registry")).containsExactly("billing-grpc");
        assertThat(status.path("missingTotal").asInt()).as("nothing is in the KB yet, so every attested alias is work to do").isEqualTo(4);
    }

    @Test
    @DisplayName("configuration under an alias is never mistaken for an alias — only the section's own keys are")
    void configurationKeys_areNotAliases(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "stand-test-environments.yml", REGISTRY);

        JsonNode status = status(project);

        assertThat(textAt(status, "service", "registry"))
                .as("'auth' and the *-ref names sit UNDER client-service; reporting one as attested would license a KB entry for something that is not a system")
                .doesNotContain("auth", "correlation", "base-url-ref", "password-ref");
        assertThat(textAt(status, "datasource", "registry")).doesNotContain("allowed-schemas", "write-allowed", "url-ref");
        assertThat(status.path("kinds").has("kafka-cluster")).as("the cluster block is not a kind of alias a case can name").isFalse();
    }

    @Test
    @DisplayName("an alias the knowledge base already covers drops off the worklist")
    void knownAliases_areNotMissing(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "stand-test-environments.yml", REGISTRY);
        write(project, "knowledge-base/services/client.yml", "services:\n  - id: client-service\n    name: Client Service\n");

        JsonNode status = status(project);

        assertThat(status.path("knowledgeBase").path("present").asBoolean()).isTrue();
        assertThat(textAt(status, "service", "missing")).isEmpty();
        assertThat(textAt(status, "kafka-topic", "missing")).containsExactly("response-topic");
        assertThat(status.path("missingTotal").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("the Spring spelling of the registry is read by the same scanner, nested deeper")
    void springRegistry_isReadToo(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();
        write(project, "src/test/resources/application.yml", SPRING_REGISTRY);

        JsonNode status = status(project);

        assertThat(textAt(status, "service", "registry")).containsExactly("order-service");
        assertThat(textAt(status, "kafka-topic", "registry")).containsExactly("order-events");
    }

    @Test
    @DisplayName("no registry is reported as no registry — there is nothing to bootstrap from, and that is the finding")
    void withoutARegistry_itSaysSo(@TempDir Path temporary) throws IOException {
        Path project = temporary.toRealPath();

        JsonNode status = status(project);

        assertThat(status.path("registry").isNull()).isTrue();
        assertThat(status.path("missingTotal").asInt()).as("an absent registry must not read as a clean bill of health")
                .isZero();
        assertThat(status.path("knowledgeBase").path("present").asBoolean()).isFalse();
    }
}
