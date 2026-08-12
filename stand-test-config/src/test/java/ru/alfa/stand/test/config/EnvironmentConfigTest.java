package ru.alfa.stand.test.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentConfigFormat;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.environment.ViewportProfile;
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
                      svc: { baseUrlRef: SVC_URL, auth: { scheme: BEARER, tokenRef: SVC_TOKEN } }
                    grpcTargets:
                      t: { targetRef: T_ADDR }
                """;
        EnvironmentDefinition dev = parse(yaml).environment("dev").orElseThrow();
        assertThat(dev.service("svc").orElseThrow().baseUrlRef()).isEqualTo("SVC_URL");
        assertThat(dev.service("svc").orElseThrow().auth()).isEqualTo(AuthConfig.bearer("SVC_TOKEN"));
        assertThat(dev.grpcTarget("t").orElseThrow().targetRef()).isEqualTo("T_ADDR");
    }

    @Test
    @DisplayName("a service auth block maps to an AuthConfig of references; a service without one carries no auth")
    void serviceAuthParsed() {
        String yaml = """
                environments:
                  ift:
                    services:
                      secured:
                        base-url-ref: SECURED_URL
                        auth: { scheme: basic, username-ref: CLIENT_USER, password-ref: CLIENT_PASSWORD }
                      open:
                        base-url-ref: OPEN_URL
                """;
        EnvironmentDefinition ift = parse(yaml).environment("ift").orElseThrow();

        assertThat(ift.service("secured").orElseThrow().auth())
                .isEqualTo(new AuthConfig(AuthScheme.BASIC, "CLIENT_USER", "CLIENT_PASSWORD", null));
        assertThat(ift.service("open").orElseThrow().auth()).isNull();
    }

    @Test
    @DisplayName("an unknown auth key, an unknown scheme and a broken scheme combination are rejected with their location")
    void authValidationFailClosed() {
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: U, auth: { scheme: BASIC, username-ref: A, password-ref: B, bogus: 1 } }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("bogus")
                .hasMessageContaining("environments.ift.services.svc.auth");
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: U, auth: { scheme: DIGEST } }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("scheme")
                .hasMessageContaining("DIGEST");
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: U, auth: { scheme: BASIC, username-ref: A } }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("passwordRef")
                .hasMessageContaining("environments.ift.services.svc.auth");
    }

    @Test
    @DisplayName("a value-shaped auth reference is rejected fail-closed")
    void authValueShapedReferenceRejected() {
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    services:
                      svc: { base-url-ref: U, auth: { scheme: BEARER, token-ref: "Bearer sk-abc123def" } }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
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
    @DisplayName("named kafka-clusters parse and a topic selects one via 'cluster'; the single kafka-cluster stays the default")
    void namedKafkaClustersParse() {
        EnvironmentRegistry registry = parse("""
                environments:
                  ift:
                    topics:
                      events: { name: ift.events.v1 }
                      audit:  { name: ift.audit.v1, cluster: audit }
                    kafka-cluster:
                      bootstrap-servers-ref: KAFKA_BOOTSTRAP
                    kafka-clusters:
                      audit:
                        bootstrap-servers-ref: AUDIT_BOOTSTRAP
                """);

        EnvironmentDefinition ift = registry.environment("ift").orElseThrow();
        assertThat(ift.kafkaCluster().bootstrapServersRef()).isEqualTo("KAFKA_BOOTSTRAP");
        assertThat(ift.kafkaCluster("audit").orElseThrow().bootstrapServersRef()).isEqualTo("AUDIT_BOOTSTRAP");
        assertThat(ift.topic("events").orElseThrow().cluster()).isNull();
        assertThat(ift.topic("audit").orElseThrow().cluster()).isEqualTo("audit");
    }

    @Test
    @DisplayName("a topic naming an undeclared kafka cluster is rejected fail-closed with the environment location")
    void topicWithUndeclaredClusterRejected() {
        assertThatThrownBy(() -> parse("""
                environments:
                  ift:
                    topics:
                      audit: { name: ift.audit.v1, cluster: ghost }
                    kafka-cluster:
                      bootstrap-servers-ref: KAFKA_BOOTSTRAP
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("names Kafka cluster 'ghost'")
                .hasMessageContaining("environments.ift");
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

    // ---- registry format version (BR-37) ----

    @Test
    @DisplayName("a file without 'version' is read as format version 1 — existing files load unchanged")
    void fileWithoutVersionIsReadAsV1() {
        EnvironmentRegistry registry = parse("""
                environments:
                  ift:
                    services:
                      client-service: { base-url-ref: CLIENT_SERVICE_URL }
                """);

        assertThat(registry.environment("ift").orElseThrow().service("client-service")).isPresent();
        assertThat(registry.environment("ift").orElseThrow().uiApplications()).isEmpty();
    }

    @Test
    @DisplayName("an explicitly declared supported version is accepted (1 and the current one alike)")
    void declaredSupportedVersionIsAccepted() {
        assertThat(parse("""
                version: 1
                environments:
                  ift:
                    services:
                      client-service: { base-url-ref: CLIENT_SERVICE_URL }
                """).environment("ift")).isPresent();
        assertThat(parse("""
                version: 2
                environments:
                  ift: {}
                """).environment("ift")).isPresent();
    }

    @Test
    @DisplayName("a newer format version fails with a VERSION message, not with 'Unknown field' — this is the whole point of BR-37")
    void newerFormatVersionFailsWithVersionMessage() {
        assertThatThrownBy(() -> parse("""
                version: 99
                environments:
                  ift: {}
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("format version 99")
                .hasMessageContaining("up to " + EnvironmentConfigFormat.SUPPORTED_VERSION)
                .hasMessageContaining("upgrade the stand-test-* dependencies")
                .hasMessageNotContaining("Unknown field");
    }

    @Test
    @DisplayName("a non-integer or non-positive version is rejected — fail-closed survives the new key")
    void nonIntegerVersionIsRejected() {
        assertThatThrownBy(() -> parse("version: \"2\"\nenvironments: {}\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
        assertThatThrownBy(() -> parse("version: 2.5\nenvironments: {}\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
        assertThatThrownBy(() -> parse("version: 0\nenvironments: {}\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> parse("version: -1\nenvironments: {}\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("'version' declared twice is a duplicate key — the safe loader rejects it before the mapper sees it")
    void duplicateVersionKeyIsRejected() {
        assertThatThrownBy(() -> parse("version: 1\nversion: 2\nenvironments: {}\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("duplicate key");
    }

    @Test
    @DisplayName("'version' is the ONLY new root key — any other root key is still rejected")
    void rootKeyWhitelistStaysClosed() {
        assertThatThrownBy(() -> parse("versions: 2\nenvironments: {}\n"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unknown field 'versions'")
                .hasMessageContaining("<document>");
    }

    // ---- ui-applications (BR-27, BR-31) ----

    @Test
    @DisplayName("an application with exactly one account names it directly, and the pair is read as references like every other credential")
    void uiApplicationDirectCredentialsAreParsed() {
        EnvironmentDefinition ift = parse("""
                version: 4
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-username: ${web_username:TAKSA_USERNAME}
                          credentials-password: TAKSA_PASSWORD
                          roles: [admin]
                          login:
                            signed-in-locator: text=Выйти
                """).environment("ift").orElseThrow();

        UiAuthConfig auth = ift.uiApplication("taksa").orElseThrow().auth();
        assertThat(auth.credentialsUsername()).isEqualTo("${web_username:TAKSA_USERNAME}");
        assertThat(auth.credentialsPassword()).isEqualTo("TAKSA_PASSWORD");
        assertThat(auth.credentialsPoolRef()).isNull();
        assertThat(auth.hasDirectCredentials()).isTrue();
    }

    @Test
    @DisplayName("the direct pair declares the format version it arrived in, so an older SDK names the version instead of the field")
    void uiApplicationDirectCredentialsRequireVersionFour() {
        assertThatThrownBy(() -> parse("""
                version: 3
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: FORM
                          credentials-username: TAKSA_USERNAME
                          credentials-password: TAKSA_PASSWORD
                          login:
                            username-locator: css=#username
                            password-locator: css=#password
                            submit-locator: css=#kc-login
                            signed-in-locator: text=Выйти
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("credentials-username")
                .hasMessageContaining("format version 4");
    }

    @Test
    @DisplayName("from version 5 the bare credential is a VALUE and *-ref carries the reference — the same rule the starter applies")
    void uiApplicationCredentialTwinsFromVersionFive() {
        EnvironmentDefinition ift = parse("""
                version: 5
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-username: tks_Admin
                          credentials-password-ref: TAKSA_ADMIN_PASSWORD
                          roles: [admin]
                          login:
                            signed-in-locator: text=Выйти
                """).environment("ift").orElseThrow();

        UiAuthConfig auth = ift.uiApplication("taksa").orElseThrow().auth();
        assertThat(SecretReferences.isLiteral(auth.credentialsUsername()))
                .as("a login written as a value is a value on both front-ends from version 5")
                .isTrue();
        assertThat(SecretReferences.resolve(auth.credentialsUsername(), name -> null)).isEqualTo("tks_Admin");
        assertThat(auth.credentialsPassword())
                .as("a secret keeps the reference spelling, and the reference is not wrapped")
                .isEqualTo("TAKSA_ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("a version-4 document keeps the older meaning, so the flip cannot reach a file already written")
    void uiApplicationCredentialsStayReferencesBeforeVersionFive() {
        EnvironmentDefinition ift = parse("""
                version: 4
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-username: TAKSA_ADMIN_USERNAME
                          credentials-password: TAKSA_ADMIN_PASSWORD
                          roles: [admin]
                          login:
                            signed-in-locator: text=Выйти
                """).environment("ift").orElseThrow();

        assertThat(SecretReferences.isLiteral(ift.uiApplication("taksa").orElseThrow().auth().credentialsUsername())).isFalse();
    }

    @Test
    @DisplayName("a version-5 value shaped like a variable NAME is refused, and the message names the *-ref spelling")
    void uiApplicationRefusesVariableNameAsCredentialValue() {
        assertThatThrownBy(() -> parse("""
                version: 5
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-username: TAKSA_ADMIN_USERNAME
                          roles: [admin]
                          login:
                            signed-in-locator: text=Выйти
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("shaped like the NAME of an environment variable")
                .hasMessageContaining("credentials-username-ref");
    }

    @Test
    @DisplayName("the SDK-internal literal marker is refused in a credential VALUE too, not only in a *-ref")
    void uiApplicationRefusesLiteralMarkerAsCredentialValue() {
        // requireReferenceShape has always refused the marker in a *-ref. The value twin had no guard, and
        // the marker does not cancel itself: wrapping it again leaves one prefix behind after resolve, so
        // the sign-in would have been attempted with 'literal://s3cret' as the password.
        assertThatThrownBy(() -> parse("""
                version: 5
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-username: admin
                          credentials-password: literal://s3cret
                          roles: [admin]
                          login:
                            signed-in-locator: text=Выйти
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("SDK-internal literal marker");
    }

    @Test
    @DisplayName("a roster and a direct pair together are refused by the loader, naming both spellings")
    void uiApplicationRefusesPoolAndDirectPairTogether() {
        assertThatThrownBy(() -> parse("""
                version: 4
                environments:
                  ift:
                    ui-applications:
                      taksa:
                        base-url-ref: TAKSA_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-pool-ref: TAKSA_TEST_USERS
                          credentials-username: TAKSA_USERNAME
                          credentials-password: TAKSA_PASSWORD
                          login:
                            signed-in-locator: text=Выйти
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("credentials-pool-ref");
    }

    @Test
    @DisplayName("a ui-applications section parses alias, base-url-ref, viewport profiles, trace and auth")
    void uiApplicationsSectionIsParsed() {
        EnvironmentDefinition ift = parse("""
                version: 3
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: CLIENT_PORTAL_IFT_URL
                        default-viewport: desktop
                        viewport-profiles:
                          desktop: { width: 1440, height: 900 }
                          mobile: { width: 390, height: 844 }
                        trace: off
                        auth:
                          scheme: FORM
                          credentials-pool-ref: CLIENT_PORTAL_TEST_USERS
                          roles: [client, operator, no-rights]
                          discovery-account-ref: CLIENT_PORTAL_DISCOVERY
                          challenge: none
                          login:
                            path: /login
                            username-locator: testId=login-username
                            password-locator: testId=login-password
                            submit-locator: "role=button:Sign in"
                            signed-in-locator: testId=user-menu
                """).environment("ift").orElseThrow();

        UiApplicationDefinition portal = ift.uiApplication("client-portal").orElseThrow();
        assertThat(portal.baseUrlRef()).isEqualTo("CLIENT_PORTAL_IFT_URL");
        assertThat(portal.defaultViewport()).isEqualTo("desktop");
        assertThat(portal.viewportProfile("mobile")).contains(new ViewportProfile(390, 844));
        assertThat(portal.defaultViewportProfile()).contains(new ViewportProfile(1440, 900));
        assertThat(portal.trace()).isEqualTo(UiTraceMode.OFF);
        assertThat(portal.auth()).isEqualTo(new UiAuthConfig(
                UiAuthScheme.FORM,
                "CLIENT_PORTAL_TEST_USERS",
                List.of("client", "operator", "no-rights"),
                "CLIENT_PORTAL_DISCOVERY",
                new UiLoginFormConfig("/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu"),
                UiLoginChallenge.NONE));
        assertThat(ift.uiApplication("ghost-portal")).isEmpty();
    }

    @Test
    @DisplayName("the ui section accepts the camelCase spelling of every key, like the rest of the format")
    void uiApplicationsAcceptCamelCase() {
        EnvironmentDefinition ift = parse("""
                version: 3
                environments:
                  ift:
                    uiApplications:
                      client-portal:
                        baseUrlRef: CLIENT_PORTAL_IFT_URL
                        defaultViewport: desktop
                        viewportProfiles:
                          desktop: { width: 1440, height: 900 }
                        trace: on-failure
                        auth:
                          scheme: storage-state
                          credentialsPoolRef: POOL
                          discoveryAccountRef: DISCOVERY
                          challenge: mfa
                          login:
                            signedInLocator: testId=user-menu
                """).environment("ift").orElseThrow();

        UiApplicationDefinition portal = ift.uiApplication("client-portal").orElseThrow();
        assertThat(portal.defaultViewport()).isEqualTo("desktop");
        assertThat(portal.trace()).isEqualTo(UiTraceMode.ON_FAILURE);
        assertThat(portal.auth().scheme()).isEqualTo(UiAuthScheme.STORAGE_STATE);
        assertThat(portal.auth().challenge()).isEqualTo(UiLoginChallenge.MFA);
        assertThat(portal.auth().login().signedInLocator()).isEqualTo("testId=user-menu");
    }

    @Test
    @DisplayName("a ui-applications section in a version-1 document is refused with the version it needs — otherwise an older SDK would meet 'Unknown field'")
    void uiApplicationsRequireFormatVersion2() {
        String yaml = """
                environments:
                  ift:
                    ui-applications:
                      client-portal: { base-url-ref: CLIENT_PORTAL_IFT_URL }
                """;

        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("ui-applications")
                .hasMessageContaining("requires environment registry format version 2")
                .hasMessageContaining("environments.ift.ui-applications");
        assertThatThrownBy(() -> parse("version: 1\n" + yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("requires environment registry format version 2");
    }

    @Test
    @DisplayName("a ui base-url-ref carrying a resolved URL or the SDK-internal literal marker is rejected fail-closed")
    void uiApplicationRefRejectsValues() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal: { base-url-ref: https://portal.ift.example }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME")
                .hasMessageContaining("environments.ift.ui-applications.client-portal");

        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal: { base-url-ref: "literal://https://portal.ift.example" }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("literal marker");
    }

    @Test
    @DisplayName("ui auth references are guarded exactly like every other *-ref: a pasted credential is not a name")
    void uiAuthRefsAreGuarded() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: CLIENT_PORTAL_IFT_URL
                        auth: { scheme: FORM, credentials-pool-ref: "Basic dXNlcjpwYXNz" }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME")
                .hasMessageContaining("client-portal.auth");
    }

    @Test
    @DisplayName("the ui section is fail-closed: unknown keys, bad viewports, a default outside the profiles and an unknown scheme are rejected with their location")
    void uiApplicationsFailClosed() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal: { base-url-ref: URL, viewport: desktop }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unknown field 'viewport'")
                .hasMessageContaining("environments.ift.ui-applications.client-portal");

        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        viewport-profiles: { desktop: { width: wide, height: 900 } }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number")
                .hasMessageContaining("viewport-profiles.desktop.width");

        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        default-viewport: tablet
                        viewport-profiles: { desktop: { width: 1440, height: 900 } }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("tablet")
                .hasMessageContaining("environments.ift.ui-applications.client-portal");

        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth: { scheme: OAUTH }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("OAUTH")
                .hasMessageContaining("client-portal.auth");
    }

    @Test
    @DisplayName("the ui auth key is 'scheme', not 'type' — the service spelling is the registry's one spelling (BR-37)")
    void uiAuthUsesTheServiceSpelling() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth: { type: FORM }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unknown field 'type'")
                .hasMessageContaining("client-portal.auth");
    }

    @Test
    @DisplayName("trace must be 'off' or 'on-failure'; an unquoted YAML 'on' (the boolean true) is refused rather than silently recording")
    void traceValuesAreClosed() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal: { base-url-ref: URL, trace: on }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("'off' or 'on-failure'")
                .hasMessageContaining("environments.ift.ui-applications.client-portal.trace");
    }

    @Test
    @DisplayName("roles keep their duplicates on the way in, so the value type's own invariant can reject them")
    void duplicateRolesAreRejected() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth: { scheme: FORM, credentials-pool-ref: POOL, roles: [client, client] }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    @DisplayName("a credential typed into password-locator is refused when the file is read — the field addresses an element, and the shape check says so")
    void credentialInTheLoginSectionIsRefused() {
        assertThatThrownBy(() -> parse("""
                version: 3
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth:
                          scheme: FORM
                          credentials-pool-ref: POOL
                          login:
                            username-locator: testId=login-username
                            password-locator: P@ssw0rd-2026
                            submit-locator: testId=submit
                            signed-in-locator: testId=user-menu
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("password-locator")
                .hasMessageContaining("never holds a credential");
    }

    @Test
    @DisplayName("a scheme that signs in without a login section is refused where it is written, not when the browser is already open")
    void formWithoutALoginSectionIsRefused() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth: { scheme: FORM, credentials-pool-ref: POOL }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("login section");
    }

    @Test
    @DisplayName("an unknown key inside the login section is refused, like everywhere else in this fail-closed loader")
    void unknownLoginKeysAreRefused() {
        assertThatThrownBy(() -> parse("""
                version: 3
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-pool-ref: POOL
                          login: { signed-in-locator: testId=user-menu, otp-locator: testId=otp }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("Unknown field 'otp-locator'");
    }

    @Test
    @DisplayName("auth.login arrived after version 2, so a version-2 document carrying it is refused with the version it needs — not with 'Unknown field'")
    void loginRequiresFormatVersion3() {
        String yaml = """
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-pool-ref: POOL
                          login: { signed-in-locator: testId=user-menu }
                """;

        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("auth.login")
                .hasMessageContaining("requires environment registry format version " + EnvironmentConfigFormat.UI_LOGIN_SINCE_VERSION)
                .hasMessageContaining("declares version 2");
    }

    @Test
    @DisplayName("the challenge flag is gated on the same version — a field added to a section is a section for this purpose")
    void challengeRequiresFormatVersion3() {
        assertThatThrownBy(() -> parse("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth: { scheme: NONE, challenge: none }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("auth.challenge")
                .hasMessageContaining("format version " + EnvironmentConfigFormat.UI_LOGIN_SINCE_VERSION);
    }

    @Test
    @DisplayName("an unknown challenge value is refused with the closed list — declaring one does not make the SDK defeat it, it makes the refusal speak")
    void unknownChallengeIsRefused() {
        assertThatThrownBy(() -> parse("""
                version: 3
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-pool-ref: POOL
                          challenge: fingerprint
                          login: { signed-in-locator: testId=user-menu }
                """))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("challenge")
                .hasMessageContaining("MFA");
    }
}
