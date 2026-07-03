package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnvironmentTest {

    @Test
    @DisplayName("definitions reject blank aliases and references")
    void definitions_rejectBlank() {
        assertThatThrownBy(() -> new ServiceEndpointDefinition(" ", "URL_REF", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServiceEndpointDefinition("svc", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TopicDefinition("alias", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GrpcTargetDefinition("alias", " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorrelationConfig(null, "X-Correlation-Id"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("datasource keeps an immutable, defensively copied schema whitelist and readonly default")
    void datasource_schemaWhitelist() {
        Set<String> schemas = new HashSet<>();
        schemas.add("test_data");
        DatasourceDefinition datasource = new DatasourceDefinition(
                "mainDb", "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD", schemas, false);

        schemas.add("public");

        assertThat(datasource.allowedSchemas()).containsExactly("test_data");
        assertThat(datasource.isSchemaAllowed("test_data")).isTrue();
        assertThat(datasource.isSchemaAllowed("public")).isFalse();
        assertThat(datasource.writeAllowed()).isFalse();
        assertThatThrownBy(() -> datasource.allowedSchemas().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the schema whitelist folds the unquoted target to lower case (PostgreSQL semantics); whitelist entries are physical lower-case names")
    void datasource_schemaWhitelistFoldsTargetCase() {
        DatasourceDefinition lower = new DatasourceDefinition("mainDb", "U", "U", "P", Set.of("test_data"), true);
        // The unquoted SQL target is folded the way PostgreSQL folds it, so any case of the target matches.
        assertThat(lower.isSchemaAllowed("TEST_DATA")).isTrue();
        assertThat(lower.isSchemaAllowed("Test_Data")).isTrue();
        assertThat(lower.isSchemaAllowed("test_data")).isTrue();
        assertThat(lower.isSchemaAllowed("other")).isFalse();
        assertThat(lower.isSchemaAllowed(null)).isFalse();

        // An empty whitelist allows nothing.
        DatasourceDefinition none = new DatasourceDefinition("mainDb", "U", "U", "P", Set.of(), true);
        assertThat(none.isSchemaAllowed("test_data")).isFalse();

        // A non-lower-case whitelist entry is inert (configure the physical lower-case schema name): the target
        // is folded, the whitelist is not, so this stays fail-closed rather than leniently matching.
        DatasourceDefinition upper = new DatasourceDefinition("mainDb", "U", "U", "P", Set.of("TEST_DATA"), true);
        assertThat(upper.isSchemaAllowed("test_data")).isFalse();
        assertThat(upper.isSchemaAllowed("TEST_DATA")).isFalse();
    }

    @Test
    @DisplayName("environment definition resolves aliases via Optional and is immutable")
    void environmentDefinition_resolvesAliases() {
        ServiceEndpointDefinition service = new ServiceEndpointDefinition(
                "client-service", "CLIENT_SERVICE_URL", new CorrelationConfig(CorrelationSource.HEADER, "X-Correlation-Id"));
        EnvironmentDefinition environment = new EnvironmentDefinition(
                "ift", Map.of("client-service", service), Map.of(), Map.of(), Map.of());

        assertThat(environment.service("client-service")).contains(service);
        assertThat(environment.service("unknown")).isEmpty();
        assertThatThrownBy(() -> environment.services().put("x", service))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("kafka cluster definition requires a bootstrap reference and exposes optional security refs")
    void kafkaCluster_referencesOnly() {
        assertThatThrownBy(() -> new KafkaClusterDefinition(" ", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        KafkaClusterDefinition plain = KafkaClusterDefinition.of("KAFKA_BOOTSTRAP_SERVERS");
        assertThat(plain.bootstrapServersRef()).isEqualTo("KAFKA_BOOTSTRAP_SERVERS");
        assertThat(plain.securityProtocolReference()).isEmpty();
        assertThat(plain.saslJaasConfigReference()).isEmpty();

        KafkaClusterDefinition secured = new KafkaClusterDefinition("BOOT_REF", "SEC_PROTO_REF", "SASL_REF");
        assertThat(secured.securityProtocolReference()).contains("SEC_PROTO_REF");
        assertThat(secured.saslJaasConfigReference()).contains("SASL_REF");
    }

    @Test
    @DisplayName("environment definition carries a topic and an optional kafka cluster")
    void environmentDefinition_carriesKafka() {
        TopicDefinition topic = new TopicDefinition("response-topic", "pakt.response.ift", new CorrelationConfig(CorrelationSource.HEADER, "X-Correlation-Id"));
        KafkaClusterDefinition cluster = KafkaClusterDefinition.of("KAFKA_BOOTSTRAP_SERVERS");
        EnvironmentDefinition withKafka = new EnvironmentDefinition(
                "ift", Map.of(), Map.of("response-topic", topic), Map.of(), Map.of(), cluster);
        EnvironmentDefinition withoutKafka = new EnvironmentDefinition("dev", Map.of(), Map.of(), Map.of(), Map.of());

        assertThat(withKafka.topic("response-topic")).contains(topic);
        assertThat(withKafka.kafkaCluster()).isSameAs(cluster);
        assertThat(withoutKafka.kafkaCluster()).isNull();
    }

    @Test
    @DisplayName("in-memory registry resolves known environments and rejects unknown or blank names")
    void registry_resolves() {
        EnvironmentDefinition ift = new EnvironmentDefinition("ift", Map.of(), Map.of(), Map.of(), Map.of());
        EnvironmentRegistry registry = new InMemoryEnvironmentRegistry(Map.of("ift", ift));

        assertThat(registry.environment("ift")).contains(ift);
        assertThat(registry.environment("prod")).isEmpty();
        assertThat(registry.environment(" ")).isEmpty();
        assertThat(registry.environment(null)).isEmpty();
    }

    @Test
    @DisplayName("named Kafka clusters resolve by alias; the default cluster stays outside the named lookup")
    void namedKafkaClusters_resolveByAlias() {
        KafkaClusterDefinition defaultCluster = KafkaClusterDefinition.of("KAFKA_BOOTSTRAP");
        KafkaClusterDefinition audit = KafkaClusterDefinition.of("AUDIT_BOOTSTRAP");
        EnvironmentDefinition ift = new EnvironmentDefinition(
                "ift", Map.of(), Map.of(), Map.of(), Map.of(), defaultCluster, Map.of("audit", audit));

        assertThat(ift.kafkaCluster()).isEqualTo(defaultCluster);
        assertThat(ift.kafkaCluster("audit")).contains(audit);
        assertThat(ift.kafkaCluster("ghost")).isEmpty();
    }

    @Test
    @DisplayName("a topic naming an undeclared Kafka cluster is rejected at environment construction (closed whitelist)")
    void topicWithUnknownCluster_rejected() {
        TopicDefinition onAudit = new TopicDefinition("events", "ift.events.v1", null, "audit");

        assertThatThrownBy(() -> new EnvironmentDefinition(
                "ift", Map.of(), Map.of("events", onAudit), Map.of(), Map.of(),
                KafkaClusterDefinition.of("KAFKA_BOOTSTRAP"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names Kafka cluster 'audit'")
                .hasMessageContaining("not declared in kafka-clusters");

        // Declared cluster → accepted; a blank cluster alias on the topic itself is rejected.
        new EnvironmentDefinition("ift", Map.of(), Map.of("events", onAudit), Map.of(), Map.of(),
                null, Map.of("audit", KafkaClusterDefinition.of("AUDIT_BOOTSTRAP")));
        assertThatThrownBy(() -> new TopicDefinition("events", "ift.events.v1", null, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
