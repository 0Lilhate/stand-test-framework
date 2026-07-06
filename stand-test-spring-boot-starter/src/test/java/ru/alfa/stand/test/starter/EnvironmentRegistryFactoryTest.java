package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.SecretReferences;

/**
 * Unit tests for the endpoint value fields (Spring-resolved twins of the {@code *-ref} fields):
 * a configured value — including the empty string an unset variable behind {@code ${VAR:}} resolves
 * to — is wrapped as an SDK-internal literal, both twins together are ambiguous, and the established
 * ref-only error texts stay untouched.
 */
class EnvironmentRegistryFactoryTest {

    @Test
    @DisplayName("value-only endpoint fields are wrapped as literals for every resource kind")
    void valueFields_wrapAsLiterals() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();

        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrl("https://stand.example:8443/api");
        ift.getServices().put("client-service", service);

        StandTestProperties.Datasource datasource = new StandTestProperties.Datasource();
        datasource.setUrl("jdbc:postgresql://stand:5432/app");
        datasource.setUserRef("MAIN_DB_USER");
        datasource.setPasswordRef("MAIN_DB_PASSWORD");
        ift.getDatasources().put("main-db", datasource);

        StandTestProperties.GrpcTarget target = new StandTestProperties.GrpcTarget();
        target.setTarget("billing.stand.local:6565");
        ift.getGrpcTargets().put("billing-grpc", target);

        StandTestProperties.KafkaCluster cluster = new StandTestProperties.KafkaCluster();
        cluster.setBootstrapServers("broker-1:9092,broker-2:9092");
        cluster.setSecurityProtocol("PLAINTEXT");
        ift.setKafkaCluster(cluster);

        properties.getEnvironments().put("ift", ift);

        EnvironmentDefinition definition = EnvironmentRegistryFactory.build(properties).environment("ift").orElseThrow();

        assertThat(resolveLiteral(definition.service("client-service").orElseThrow().baseUrlRef())).isEqualTo("https://stand.example:8443/api");
        assertThat(resolveLiteral(definition.datasource("main-db").orElseThrow().urlRef())).isEqualTo("jdbc:postgresql://stand:5432/app");
        assertThat(definition.datasource("main-db").orElseThrow().userRef()).isEqualTo("MAIN_DB_USER");
        assertThat(resolveLiteral(definition.grpcTarget("billing-grpc").orElseThrow().targetRef())).isEqualTo("billing.stand.local:6565");
        assertThat(resolveLiteral(definition.kafkaCluster().bootstrapServersRef())).isEqualTo("broker-1:9092,broker-2:9092");
        assertThat(resolveLiteral(definition.kafkaCluster().securityProtocolReference().orElseThrow())).isEqualTo("PLAINTEXT");
    }

    @Test
    @DisplayName("an empty value (unset variable behind ${VAR:}) is configured-but-empty: wrapped as an empty literal, no startup failure")
    void emptyValue_wrapsAsEmptyLiteral() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrl("");
        ift.getServices().put("client-service", service);
        properties.getEnvironments().put("ift", ift);

        EnvironmentDefinition definition = EnvironmentRegistryFactory.build(properties).environment("ift").orElseThrow();

        assertThat(resolveLiteral(definition.service("client-service").orElseThrow().baseUrlRef())).isEmpty();
    }

    @Test
    @DisplayName("setting both the value field and its *-ref twin is ambiguous and fails with the alias and both field names")
    void bothTwins_areRejected() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrl("https://stand.example");
        service.setBaseUrlRef("CLIENT_SERVICE_URL");
        ift.getServices().put("client-service", service);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stand.test.environments.ift")
                .hasMessageContaining("client-service")
                .hasMessageContaining("'base-url' and 'base-url-ref'")
                .hasMessageContaining("configure exactly one");
    }

    @Test
    @DisplayName("with neither twin configured the established ref-only error text is preserved")
    void neitherTwin_keepsEstablishedError() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        ift.getServices().put("client-service", new StandTestProperties.Service());
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrlRef must not be blank");
    }

    private static String resolveLiteral(String reference) {
        assertThat(SecretReferences.isLiteral(reference)).as("expected a literal-wrapped reference but got: %s", reference).isTrue();
        return SecretReferences.resolve(reference, name -> null);
    }
}
