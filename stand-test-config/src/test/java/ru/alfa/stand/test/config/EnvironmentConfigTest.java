package ru.alfa.stand.test.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.exception.StandTestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnvironmentConfigTest {

    private static EnvironmentRegistry parse(String yaml) {
        return EnvironmentConfig.toRegistry(SafeYaml.load(yaml));
    }

    @Test
    @DisplayName("a full environment maps every resource type to references (not values)")
    void fullEnvironment() {
        String yaml = """
                environments:
                  ift:
                    services:
                      client-service:
                        base-url-ref: CLIENT_SERVICE_URL
                        correlation: { source: HEADER, name: X-Correlation-Id }
                    topics:
                      response-topic: { name: pakt.response.ift, correlation: { source: header, name: X-Correlation-Id } }
                    datasources:
                      main-db:
                        url-ref: MAIN_DB_URL
                        user-ref: MAIN_DB_USER
                        password-ref: MAIN_DB_PASSWORD
                        allowed-schemas: [test_data, staging]
                        write-allowed: true
                    grpc-targets:
                      billing-grpc: { target-ref: BILLING_GRPC_TARGET, correlation: { source: METADATA, name: x-correlation-id } }
                    kafka-cluster:
                      bootstrap-servers-ref: KAFKA_BOOTSTRAP
                """;
        EnvironmentDefinition ift = parse(yaml).environment("ift").orElseThrow();

        assertThat(ift.service("client-service").orElseThrow().baseUrlRef()).isEqualTo("CLIENT_SERVICE_URL");
        assertThat(ift.service("client-service").orElseThrow().correlation().source()).isEqualTo(CorrelationSource.HEADER);
        assertThat(ift.topic("response-topic").orElseThrow().name()).isEqualTo("pakt.response.ift");
        DatasourceDefinition db = ift.datasource("main-db").orElseThrow();
        assertThat(db.urlRef()).isEqualTo("MAIN_DB_URL");
        assertThat(db.userRef()).isEqualTo("MAIN_DB_USER");
        assertThat(db.passwordRef()).isEqualTo("MAIN_DB_PASSWORD");
        assertThat(db.writeAllowed()).isTrue();
        assertThat(db.isSchemaAllowed("test_data")).isTrue();
        assertThat(ift.grpcTarget("billing-grpc").orElseThrow().targetRef()).isEqualTo("BILLING_GRPC_TARGET");
        assertThat(ift.grpcTarget("billing-grpc").orElseThrow().correlation().source()).isEqualTo(CorrelationSource.METADATA);
        assertThat(ift.kafkaCluster().bootstrapServersRef()).isEqualTo("KAFKA_BOOTSTRAP");
    }

    @Test
    @DisplayName("camelCase keys are accepted as an alias of kebab-case")
    void acceptsCamelCase() {
        String yaml = """
                environments:
                  dev:
                    services:
                      svc: { baseUrlRef: SVC_URL }
                    grpcTargets:
                      t: { targetRef: T_ADDR }
                """;
        EnvironmentDefinition dev = parse(yaml).environment("dev").orElseThrow();
        assertThat(dev.service("svc").orElseThrow().baseUrlRef()).isEqualTo("SVC_URL");
        assertThat(dev.grpcTarget("t").orElseThrow().targetRef()).isEqualTo("T_ADDR");
    }

    @Test
    @DisplayName("an empty or environments-less document yields an empty registry")
    void emptyDocument() {
        assertThat(EnvironmentConfig.toRegistry(null).environment("ift")).isEmpty();
        assertThat(parse("environments: {}").environment("ift")).isEmpty();
    }

    @Test
    @DisplayName("an unknown field is rejected with its location")
    void unknownFieldRejected() {
        String yaml = """
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: SVC_URL, bogus: 1 }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("bogus")
                .hasMessageContaining("environments.ift.services.svc");
    }

    @Test
    @DisplayName("a blank/missing reference is rejected with its location")
    void blankReferenceRejected() {
        String yaml = """
                environments:
                  ift:
                    datasources:
                      main-db: { url-ref: MAIN_DB_URL, user-ref: MAIN_DB_USER }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("password-ref")
                .hasMessageContaining("environments.ift.datasources.main-db");
    }

    @Test
    @DisplayName("an unknown correlation source is rejected")
    void unknownCorrelationSource() {
        String yaml = """
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: SVC_URL, correlation: { source: TRAILER, name: X } }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("source")
                .hasMessageContaining("TRAILER");
    }

    @Test
    @DisplayName("a non-mapping environment body is rejected")
    void nonMappingEnvironment() {
        assertThatThrownBy(() -> parse("environments:\n  ift: not-a-map"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("environments.ift");
    }

    @Test
    @DisplayName("allowed-schemas must be a list of non-blank strings")
    void allowedSchemasValidated() {
        String yaml = """
                environments:
                  ift:
                    datasources:
                      db: { url-ref: U, user-ref: US, password-ref: P, allowed-schemas: "not-a-list" }
                """;
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("allowed-schemas");
    }

    @Test
    @DisplayName("a *-ref value that is obviously a resolved endpoint or an inline secret is rejected fail-closed")
    void valueShapedReferenceRejected() {
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: "https://real-stand.example" }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    datasources:
                      db: { url-ref: "jdbc:postgresql://db:5432/app", user-ref: US, password-ref: P }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    datasources:
                      db: { url-ref: U, user-ref: US, password-ref: "Bearer sk-abc123def" }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    kafka-cluster: { bootstrap-servers-ref: "broker1:9092, broker2:9092" }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
    }

    @Test
    @DisplayName("${NAME} and ${NAME:default} placeholder refs are accepted and stored verbatim (resolved at the point of use)")
    void placeholderReferencesAccepted() {
        EnvironmentRegistry registry = parse("""
                environments:
                  ift:
                    datasources:
                      db:
                        url-ref: ${MAIN_DB_URL:jdbc:h2:mem:example}
                        user-ref: ${MAIN_DB_USER}
                        password-ref: MAIN_DB_PASSWORD
                """);

        DatasourceDefinition datasource = registry.environment("ift").orElseThrow().datasource("db").orElseThrow();
        assertThat(datasource.urlRef()).isEqualTo("${MAIN_DB_URL:jdbc:h2:mem:example}");
        assertThat(datasource.userRef()).isEqualTo("${MAIN_DB_USER}");
    }

    @Test
    @DisplayName("correlation and topic names are NOT ref fields — header-like values stay legitimate")
    void nonRefNamesUnaffectedByReferenceGuard() {
        EnvironmentRegistry registry = parse("""
                environments:
                  ift:
                    topics:
                      events:
                        name: ift.events.v1
                        correlation: { source: HEADER, name: X-Correlation-Id }
                """);

        assertThat(registry.environment("ift")).isPresent();
    }
}
