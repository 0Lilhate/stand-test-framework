package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.config.YamlEnvironmentConfigLoader;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;

/**
 * Parity guard between the two hand-maintained surface->registry mappers of the same logical schema:
 * the starter's {@link EnvironmentRegistryFactory} (Spring {@code stand.test.environments.*} properties)
 * and stand-test-config's YAML loader ({@code stand-test-environments.yml}). The same logical
 * environment described through both surfaces must produce structurally equal core records — if either
 * mapper drifts (a renamed key, a dropped field, a different default), this test fails.
 */
class EnvironmentRegistryParityTest {

    private static final String CONFIG_PATH_PROPERTY = "stand.test.environments.config";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearConfigPathProperty() {
        System.clearProperty(CONFIG_PATH_PROPERTY);
    }

    @Test
    @DisplayName("the same logical environment built via starter properties and via the config YAML loader yields equal core records")
    void starterAndConfigMappers_produceEqualEnvironment() throws IOException {
        EnvironmentRegistry fromProperties = EnvironmentRegistryFactory.build(standTestProperties());
        EnvironmentRegistry fromYaml = loadFromYaml("""
                environments:
                  ift:
                    services:
                      client-service:
                        base-url-ref: CLIENT_SERVICE_URL
                        correlation:
                          source: HEADER
                          name: X-Correlation-Id
                        auth:
                          scheme: BASIC
                          username-ref: CLIENT_USER
                          password-ref: CLIENT_PASSWORD
                      token-service:
                        base-url-ref: TOKEN_SERVICE_URL
                        auth:
                          scheme: BEARER
                          token-ref: TOKEN_SERVICE_TOKEN
                    topics:
                      events:
                        name: ift.events.v1
                        correlation:
                          source: KEY
                          name: corrId
                      audit:
                        name: ift.audit.v1
                        cluster: audit
                    kafka-clusters:
                      audit:
                        bootstrap-servers-ref: AUDIT_BOOTSTRAP
                    datasources:
                      main-db:
                        url-ref: MAIN_DB_URL
                        user-ref: MAIN_DB_USER
                        password-ref: MAIN_DB_PASSWORD
                        allowed-schemas:
                          - test_data
                        write-allowed: true
                    grpc-targets:
                      accounts:
                        target-ref: ACCOUNTS_GRPC
                        correlation:
                          source: METADATA
                          name: x-correlation-id
                    kafka-cluster:
                      bootstrap-servers-ref: KAFKA_BOOTSTRAP
                      security-protocol-ref: KAFKA_SECURITY
                      sasl-jaas-config-ref: KAFKA_JAAS
                """);

        EnvironmentDefinition viaProperties = fromProperties.environment("ift").orElseThrow();
        EnvironmentDefinition viaYaml = fromYaml.environment("ift").orElseThrow();

        assertThat(viaProperties).isEqualTo(viaYaml);
    }

    @Test
    @DisplayName("both surfaces reject the same value-shaped *-ref input — the reference guard cannot drift one-sided")
    void bothSurfacesRejectValueShapedReference() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrlRef("https://real-stand.example");
        ift.getServices().put("client-service", service);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> loadFromYaml("""
                environments:
                  ift:
                    services:
                      client-service:
                        base-url-ref: https://real-stand.example
                """))
                .hasMessageContaining("reference NAME");
    }

    @Test
    @DisplayName("both surfaces reject the same value-shaped auth reference — the auth guard cannot drift one-sided")
    void bothSurfacesRejectValueShapedAuthReference() {
        final StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrlRef("CLIENT_SERVICE_URL");
        StandTestProperties.Auth auth = new StandTestProperties.Auth();
        auth.setScheme(AuthScheme.BEARER);
        auth.setTokenRef("Bearer sk-abc123def");
        service.setAuth(auth);
        ift.getServices().put("client-service", service);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> loadFromYaml("""
                environments:
                  ift:
                    services:
                      client-service:
                        base-url-ref: CLIENT_SERVICE_URL
                        auth:
                          scheme: BEARER
                          token-ref: "Bearer sk-abc123def"
                """))
                .hasMessageContaining("reference NAME");
    }

    private static StandTestProperties standTestProperties() {
        final StandTestProperties properties = new StandTestProperties();
        final StandTestProperties.Environment ift = new StandTestProperties.Environment();

        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrlRef("CLIENT_SERVICE_URL");
        service.setCorrelation(correlation(CorrelationSource.HEADER, "X-Correlation-Id"));
        StandTestProperties.Auth basicAuth = new StandTestProperties.Auth();
        basicAuth.setScheme(AuthScheme.BASIC);
        basicAuth.setUsernameRef("CLIENT_USER");
        basicAuth.setPasswordRef("CLIENT_PASSWORD");
        service.setAuth(basicAuth);
        ift.getServices().put("client-service", service);

        StandTestProperties.Service tokenService = new StandTestProperties.Service();
        tokenService.setBaseUrlRef("TOKEN_SERVICE_URL");
        StandTestProperties.Auth bearerAuth = new StandTestProperties.Auth();
        bearerAuth.setScheme(AuthScheme.BEARER);
        bearerAuth.setTokenRef("TOKEN_SERVICE_TOKEN");
        tokenService.setAuth(bearerAuth);
        ift.getServices().put("token-service", tokenService);

        StandTestProperties.Topic topic = new StandTestProperties.Topic();
        topic.setName("ift.events.v1");
        topic.setCorrelation(correlation(CorrelationSource.KEY, "corrId"));
        ift.getTopics().put("events", topic);

        StandTestProperties.Topic auditTopic = new StandTestProperties.Topic();
        auditTopic.setName("ift.audit.v1");
        auditTopic.setCluster("audit");
        ift.getTopics().put("audit", auditTopic);

        StandTestProperties.KafkaCluster auditCluster = new StandTestProperties.KafkaCluster();
        auditCluster.setBootstrapServersRef("AUDIT_BOOTSTRAP");
        ift.getKafkaClusters().put("audit", auditCluster);

        StandTestProperties.Datasource datasource = new StandTestProperties.Datasource();
        datasource.setUrlRef("MAIN_DB_URL");
        datasource.setUserRef("MAIN_DB_USER");
        datasource.setPasswordRef("MAIN_DB_PASSWORD");
        datasource.getAllowedSchemas().add("test_data");
        datasource.setWriteAllowed(true);
        ift.getDatasources().put("main-db", datasource);

        StandTestProperties.GrpcTarget grpcTarget = new StandTestProperties.GrpcTarget();
        grpcTarget.setTargetRef("ACCOUNTS_GRPC");
        grpcTarget.setCorrelation(correlation(CorrelationSource.METADATA, "x-correlation-id"));
        ift.getGrpcTargets().put("accounts", grpcTarget);

        StandTestProperties.KafkaCluster kafkaCluster = new StandTestProperties.KafkaCluster();
        kafkaCluster.setBootstrapServersRef("KAFKA_BOOTSTRAP");
        kafkaCluster.setSecurityProtocolRef("KAFKA_SECURITY");
        kafkaCluster.setSaslJaasConfigRef("KAFKA_JAAS");
        ift.setKafkaCluster(kafkaCluster);

        properties.getEnvironments().put("ift", ift);
        return properties;
    }

    private static StandTestProperties.Correlation correlation(CorrelationSource source, String name) {
        StandTestProperties.Correlation correlation = new StandTestProperties.Correlation();
        correlation.setSource(source);
        correlation.setName(name);
        return correlation;
    }

    private EnvironmentRegistry loadFromYaml(String yaml) throws IOException {
        Path file = this.tempDir.resolve("stand-test-environments.yml");
        Files.writeString(file, yaml);
        System.setProperty(CONFIG_PATH_PROPERTY, file.toString());
        return new YamlEnvironmentConfigLoader().load();
    }
}
