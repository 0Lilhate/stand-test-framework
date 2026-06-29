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
}
