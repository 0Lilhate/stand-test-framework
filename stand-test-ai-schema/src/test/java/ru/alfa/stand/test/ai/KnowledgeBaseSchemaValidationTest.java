package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pins the AI knowledge-base contract shipped in {@code docs/ai-agent/knowledge-base/}: the umbrella
 * JSON Schema is well-formed, the thin per-entity schemas point into it, every example entry
 * validates, purpose-built invalid entries are rejected for the intended reason, cross-entity
 * references stay consistent, and no string anywhere in the examples carries a secret or URL shape.
 */
class KnowledgeBaseSchemaValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path KB_DIR = locateKnowledgeBaseDir();
    private static final JsonNode UMBRELLA = readJson(KB_DIR.resolve(Paths.get("schema", "stand-test-knowledge-base.schema.json")));
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final String FORBIDDEN_VALUE_SHAPES = "(://|jdbc:)|^[Bb]earer\\s|^[Bb]asic\\s";

    private static Path locateKnowledgeBaseDir() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent", "knowledge-base"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("docs/ai-agent/knowledge-base not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static JsonNode readJson(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    private static JsonNode readYaml(Path file) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = yaml.load(in);
            return MAPPER.valueToTree(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    private static JsonNode readYamlResource(String resourcePath) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (InputStream in = KnowledgeBaseSchemaValidationTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + resourcePath);
            }
            Object loaded = yaml.load(in);
            return MAPPER.valueToTree(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, e);
        }
    }

    private static JsonSchema envelopeSchema(String envelope) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.set("$defs", UMBRELLA.get("$defs"));
        wrapper.put("$ref", "#/$defs/" + envelope);
        return FACTORY.getSchema(wrapper);
    }

    private static String joinMessages(Set<ValidationMessage> messages) {
        return messages.stream().map(ValidationMessage::toString).collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("the umbrella schema is a loadable 2020-12 JSON Schema that rejects an empty document")
    void umbrellaSchema_loads() {
        JsonSchema schema = FACTORY.getSchema(UMBRELLA);
        Set<ValidationMessage> messages = schema.validate(MAPPER.createObjectNode());
        assertThat(messages).as("an empty document matches no KB file envelope").isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"service", "endpoint", "kafka-topic", "datasource", "db-probe", "db-table", "grpc-target", "environment", "test-case-mapping"})
    @DisplayName("every thin per-entity schema points at an existing umbrella $def")
    void thinSchemas_pointIntoUmbrella(String name) {
        JsonNode thin = readJson(KB_DIR.resolve(Paths.get("schema", name + ".schema.json")));
        String ref = thin.path("$ref").asText();
        assertThat(ref).as("thin schema %s must $ref the umbrella", name).startsWith("stand-test-knowledge-base.schema.json#/$defs/");
        String defName = ref.substring(ref.lastIndexOf('/') + 1);
        assertThat(UMBRELLA.path("$defs").has(defName)).as("umbrella $defs must contain %s", defName).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "services/example-service.yml, servicesFile",
        "endpoints/example-endpoints.yml, endpointsFile",
        "kafka/example-topics.yml, kafkaTopicsFile",
        "db/example-datasources.yml, datasourcesFile",
        "db/example-db-probes.yml, dbProbesFile",
        "db/example-db-tables.yml, dbTablesFile",
        "grpc/example-grpc-targets.yml, grpcTargetsFile",
        "environments/example-env.yml, environmentsFile",
        "mappings/example-test-case-mapping.yml, testCaseMappingsFile"
    })
    @DisplayName("every shipped example validates against its envelope and against the umbrella root")
    void examples_pass(String relativePath, String envelope) {
        JsonNode document = readYaml(KB_DIR.resolve(relativePath));
        Set<ValidationMessage> byEnvelope = envelopeSchema(envelope).validate(document);
        assertThat(byEnvelope).as("example %s should pass %s: %s", relativePath, envelope, joinMessages(byEnvelope)).isEmpty();
        Set<ValidationMessage> byRoot = FACTORY.getSchema(UMBRELLA).validate(document);
        assertThat(byRoot).as("example %s should match exactly one root envelope", relativePath).isEmpty();
    }

    @Test
    @DisplayName("the promoted pilot services (services/pakt-lgoty.yml) validate against the strict curated schema and are sterile")
    void promotedPilotServices_validate() {
        JsonNode document = readYaml(KB_DIR.resolve("services/pakt-lgoty.yml"));
        Set<ValidationMessage> byEnvelope = envelopeSchema("servicesFile").validate(document);
        assertThat(byEnvelope).as("promoted services should pass servicesFile: %s", joinMessages(byEnvelope)).isEmpty();
        Set<ValidationMessage> byRoot = FACTORY.getSchema(UMBRELLA).validate(document);
        assertThat(byRoot).as("promoted services should match exactly one umbrella root envelope").isEmpty();
        List<String> offenders = new ArrayList<>();
        collectForbiddenStrings(document, "services/pakt-lgoty.yml", offenders);
        assertThat(offenders).as("promoted services must carry references/contracts only, no URL/JDBC/secret shapes").isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "endpoints--direct-url.yml, endpointsFile, path",
        "endpoints--script-field.yml, endpointsFile, script",
        "environments--inline-secret.yml, environmentsFile, passwordRef",
        "environments--production-id.yml, environmentsFile, id",
        "db-probes--destructive-sql.yml, dbProbesFile, sql",
        "kafka--missing-timeout.yml, kafkaTopicsFile, timeout",
        "services--unknown-field.yml, servicesFile, bootstrapServers",
        "services--url-in-name.yml, servicesFile, name",
        "grpc--direct-target.yml, grpcTargetsFile, target"
    })
    @DisplayName("invalid KB entries are rejected for the intended reason, not just any reason")
    void invalidEntries_failForReason(String file, String envelope, String expectedToken) {
        JsonNode document = readYamlResource("/kb/invalid/" + file);
        Set<ValidationMessage> messages = envelopeSchema(envelope).validate(document);
        assertThat(messages).as("invalid entry %s should be rejected", file).isNotEmpty();
        assertThat(joinMessages(messages)).as("invalid entry %s should fail because of '%s'", file, expectedToken).contains(expectedToken);
    }

    @Test
    @DisplayName("cross-entity references in the shipped examples are consistent in both directions")
    void crossReferences_consistent() {
        JsonNode services = readYaml(KB_DIR.resolve("services/example-service.yml")).get("services");
        JsonNode endpoints = readYaml(KB_DIR.resolve("endpoints/example-endpoints.yml")).get("endpoints");
        JsonNode topics = readYaml(KB_DIR.resolve("kafka/example-topics.yml")).get("kafkaTopics");
        JsonNode datasources = readYaml(KB_DIR.resolve("db/example-datasources.yml")).get("datasources");
        JsonNode probes = readYaml(KB_DIR.resolve("db/example-db-probes.yml")).get("dbProbes");
        JsonNode dbTables = readYaml(KB_DIR.resolve("db/example-db-tables.yml")).get("dbTables");
        JsonNode grpcTargets = readYaml(KB_DIR.resolve("grpc/example-grpc-targets.yml")).get("grpcTargets");
        JsonNode environments = readYaml(KB_DIR.resolve("environments/example-env.yml")).get("environments");
        final JsonNode mappings = readYaml(KB_DIR.resolve("mappings/example-test-case-mapping.yml")).get("testCaseMappings");

        Set<String> serviceIds = ids(services);
        Set<String> endpointIds = ids(endpoints);
        Set<String> topicIds = ids(topics);
        Set<String> datasourceIds = ids(datasources);
        final Set<String> probeIds = ids(probes);
        Set<String> grpcTargetIds = ids(grpcTargets);
        Set<String> environmentIds = ids(environments);
        Set<String> grpcMethodIds = new HashSet<>();
        for (JsonNode target : grpcTargets) {
            grpcMethodIds.addAll(ids(target.get("methods")));
        }

        for (JsonNode endpoint : endpoints) {
            assertThat(serviceIds).as("endpoint %s serviceId", endpoint.get("id")).contains(endpoint.get("serviceId").asText());
        }
        for (JsonNode probe : probes) {
            assertThat(datasourceIds).as("probe %s datasourceId", probe.get("id")).contains(probe.get("datasourceId").asText());
        }
        for (JsonNode table : dbTables) {
            assertThat(datasourceIds).as("dbTable %s datasourceId", table.get("id")).contains(table.get("datasourceId").asText());
        }
        for (JsonNode service : services) {
            assertThat(texts(service.get("endpoints"))).as("service endpoints exist").isSubsetOf(endpointIds);
            assertThat(texts(service.get("datasources"))).as("service datasources exist").isSubsetOf(datasourceIds);
            assertThat(texts(service.get("grpcTargets"))).as("service grpc targets exist").isSubsetOf(grpcTargetIds);
            assertThat(texts(service.path("kafkaTopics").get("produces"))).as("produced topics exist").isSubsetOf(topicIds);
            assertThat(texts(service.path("kafkaTopics").get("consumes"))).as("consumed topics exist").isSubsetOf(topicIds);
            assertThat(texts(service.get("environments"))).as("service environments exist").isSubsetOf(environmentIds);
        }
        for (JsonNode endpoint : endpoints) {
            String owner = endpoint.get("serviceId").asText();
            for (JsonNode service : services) {
                if (service.get("id").asText().equals(owner)) {
                    assertThat(texts(service.get("endpoints"))).as("owning service lists endpoint %s", endpoint.get("id")).contains(endpoint.get("id").asText());
                }
            }
        }
        for (JsonNode environment : environments) {
            for (JsonNode binding : environment.get("services")) {
                assertThat(serviceIds).as("environment service binding").contains(binding.get("serviceId").asText());
            }
            for (JsonNode binding : environment.path("kafka").path("topics")) {
                assertThat(topicIds).as("environment topic binding").contains(binding.get("topicId").asText());
            }
            for (JsonNode binding : environment.path("datasources")) {
                assertThat(datasourceIds).as("environment datasource binding").contains(binding.get("datasourceId").asText());
            }
            for (JsonNode binding : environment.path("grpcTargets")) {
                assertThat(grpcTargetIds).as("environment grpc binding").contains(binding.get("targetId").asText());
            }
        }
        for (JsonNode mapping : mappings) {
            assertThat(environmentIds).as("mapping environment").contains(mapping.get("environment").asText());
            JsonNode matched = mapping.get("matched");
            assertThat(texts(matched.get("services"))).isSubsetOf(serviceIds);
            assertThat(texts(matched.get("endpoints"))).isSubsetOf(endpointIds);
            assertThat(texts(matched.get("kafkaTopics"))).isSubsetOf(topicIds);
            assertThat(texts(matched.get("datasources"))).isSubsetOf(datasourceIds);
            assertThat(texts(matched.get("dbProbes"))).isSubsetOf(probeIds);
            assertThat(texts(matched.get("grpcTargets"))).isSubsetOf(grpcTargetIds);
            assertThat(texts(matched.get("grpcMethods"))).isSubsetOf(grpcMethodIds);
        }
    }

    @Test
    @DisplayName("no string anywhere in the shipped examples carries a URL, JDBC or credential shape")
    void examples_carryNoSecretShapedStrings() {
        List<String> offenders = new ArrayList<>();
        for (String relativePath : List.of("services/example-service.yml", "endpoints/example-endpoints.yml", "kafka/example-topics.yml", "db/example-datasources.yml", "db/example-db-probes.yml", "db/example-db-tables.yml", "grpc/example-grpc-targets.yml", "environments/example-env.yml", "mappings/example-test-case-mapping.yml")) {
            collectForbiddenStrings(readYaml(KB_DIR.resolve(relativePath)), relativePath, offenders);
        }
        assertThat(offenders).as("KB examples must hold references and contracts only").isEmpty();
    }

    private static void collectForbiddenStrings(JsonNode node, String location, List<String> offenders) {
        if (node.isTextual() && node.asText().matches(".*(" + FORBIDDEN_VALUE_SHAPES + ").*")) {
            offenders.add(location + ": " + node.asText());
        }
        if (node.isContainerNode()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                collectForbiddenStrings(field.getValue(), location + "/" + field.getKey(), offenders);
            }
            if (node.isArray()) {
                for (JsonNode item : node) {
                    collectForbiddenStrings(item, location + "[]", offenders);
                }
            }
        }
    }

    private static Set<String> ids(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array != null) {
            for (JsonNode item : array) {
                result.add(item.get("id").asText());
            }
        }
        return result;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array != null) {
            for (JsonNode item : array) {
                result.add(item.asText());
            }
        }
        return result;
    }
}
