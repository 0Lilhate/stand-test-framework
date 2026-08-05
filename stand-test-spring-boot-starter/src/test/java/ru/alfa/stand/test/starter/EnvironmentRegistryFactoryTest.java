package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.environment.EnvironmentConfigFormat;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.environment.ViewportProfile;
import ru.alfa.stand.test.core.exception.StandTestException;

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
        final StandTestProperties properties = new StandTestProperties();
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
    @DisplayName("credential value twins (datasource user/password, auth, SASL) are wrapped as literals")
    void credentialValueTwins_wrapAsLiterals() {
        final StandTestProperties properties = new StandTestProperties();
        final StandTestProperties.Environment ift = new StandTestProperties.Environment();

        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrlRef("CLIENT_SERVICE_URL");
        StandTestProperties.Auth auth = new StandTestProperties.Auth();
        auth.setScheme(ru.alfa.stand.test.core.environment.AuthScheme.BASIC);
        auth.setUsername("alice");
        auth.setPassword("wonderland");
        service.setAuth(auth);
        ift.getServices().put("client-service", service);

        StandTestProperties.Datasource datasource = new StandTestProperties.Datasource();
        datasource.setUrlRef("MAIN_DB_URL");
        datasource.setUser("objects");
        datasource.setPassword("s3cr3t");
        ift.getDatasources().put("main-db", datasource);

        StandTestProperties.KafkaCluster cluster = new StandTestProperties.KafkaCluster();
        cluster.setBootstrapServersRef("KAFKA_BOOTSTRAP");
        cluster.setSaslJaasConfig("org.apache.kafka.common.security.plain.PlainLoginModule required;");
        ift.setKafkaCluster(cluster);

        properties.getEnvironments().put("ift", ift);

        EnvironmentDefinition definition = EnvironmentRegistryFactory.build(properties).environment("ift").orElseThrow();

        assertThat(resolveLiteral(definition.service("client-service").orElseThrow().auth().usernameRef())).isEqualTo("alice");
        assertThat(resolveLiteral(definition.service("client-service").orElseThrow().auth().passwordRef())).isEqualTo("wonderland");
        assertThat(resolveLiteral(definition.datasource("main-db").orElseThrow().userRef())).isEqualTo("objects");
        assertThat(resolveLiteral(definition.datasource("main-db").orElseThrow().passwordRef())).isEqualTo("s3cr3t");
    }

    @Test
    @DisplayName("setting both a credential value and its *-ref twin is ambiguous and fails with both field names")
    void bothCredentialTwins_areRejected() {
        final StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Datasource datasource = new StandTestProperties.Datasource();
        datasource.setUrlRef("MAIN_DB_URL");
        datasource.setUser("objects");
        datasource.setUserRef("MAIN_DB_USER");
        datasource.setPasswordRef("MAIN_DB_PASSWORD");
        ift.getDatasources().put("main-db", datasource);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("main-db")
                .hasMessageContaining("'user' and 'user-ref'")
                .hasMessageContaining("configure exactly one");
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

    // ---- registry format version and UI applications (BR-27, BR-31, BR-37) ----

    @Test
    @DisplayName("no declared version binds as format version 1 — an existing application.yml keeps working")
    void absentVersion_bindsAsInitialFormat() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrlRef("CLIENT_SERVICE_URL");
        ift.getServices().put("client-service", service);
        properties.getEnvironments().put("ift", ift);

        assertThat(properties.getVersion()).isNull();
        assertThat(EnvironmentRegistryFactory.build(properties).environment("ift").orElseThrow().uiApplications()).isEmpty();
    }

    @Test
    @DisplayName("a stand.test.version newer than this SDK reads fails with the version message, from the same core constant the file surface uses")
    void newerVersion_failsWithVersionMessage() {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(EnvironmentConfigFormat.SUPPORTED_VERSION + 1);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("format version " + (EnvironmentConfigFormat.SUPPORTED_VERSION + 1))
                .hasMessageContaining("stand.test")
                .hasMessageNotContaining("Unknown field");
    }

    @Test
    @DisplayName("a ui-applications section binds alias, viewport profiles, trace and reference-only auth")
    void uiApplications_bind() {
        EnvironmentDefinition definition = EnvironmentRegistryFactory.build(uiProperties(application -> { }))
                .environment("ift").orElseThrow();

        UiApplicationDefinition portal = definition.uiApplication("client-portal").orElseThrow();
        assertThat(portal.baseUrlRef()).isEqualTo("CLIENT_PORTAL_IFT_URL");
        assertThat(portal.defaultViewport()).isEqualTo("desktop");
        assertThat(portal.viewportProfile("desktop")).contains(new ViewportProfile(1440, 900));
        assertThat(portal.trace()).isEqualTo(UiTraceMode.ON_FAILURE);
        assertThat(portal.auth()).isEqualTo(new UiAuthConfig(
                UiAuthScheme.FORM,
                "CLIENT_PORTAL_TEST_USERS",
                List.of("client", "operator"),
                "CLIENT_PORTAL_DISCOVERY",
                new UiLoginFormConfig("/login", "testId=login-username", "testId=login-password", "role=button:Sign in", "testId=user-menu"),
                UiLoginChallenge.NONE));
    }

    @Test
    @DisplayName("trace binds from the YAML spellings a boolean-resolving parser produces ('false' as well as 'off')")
    void uiTrace_acceptsTheYamlSpellings() {
        assertThat(uiApplication(application -> application.setTrace("false")).trace()).isEqualTo(UiTraceMode.OFF);
        assertThat(uiApplication(application -> application.setTrace("off")).trace()).isEqualTo(UiTraceMode.OFF);
        assertThat(uiApplication(application -> application.setTrace(null)).trace()).isEqualTo(UiTraceMode.OFF);
        assertThatThrownBy(() -> uiApplication(application -> application.setTrace("true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'off' or 'on-failure'");
    }

    @Test
    @DisplayName("a ui-applications section without stand.test.version: 2 is refused with the version it needs — the two surfaces gate it identically")
    void uiApplications_requireFormatVersion2() {
        StandTestProperties properties = uiProperties(application -> { });
        properties.setVersion(null);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("ui-applications")
                .hasMessageContaining("requires environment registry format version 2")
                .hasMessageContaining("stand.test.environments.ift.ui-applications");
    }

    /**
     * The double-resolution trap, closed for UI fields: on this surface Spring expands a {@code ${VAR}}
     * placeholder before the SDK sees it, so a placeholder written inside a {@code *-ref} arrives as the
     * resolved address — and is rejected as a value rather than silently taken for a variable name. A
     * placeholder Spring could not expand is malformed and rejected too.
     */
    @Test
    @DisplayName("starter rejects a placeholder inside a UI *-ref: what reaches the SDK is a resolved value, not a reference name")
    void starterRejectsPlaceholderInsideUiRef() {
        assertThatThrownBy(() -> uiApplication(application -> application.setBaseUrlRef("https://portal.ift.example")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME")
                .hasMessageContaining("client-portal");
        assertThatThrownBy(() -> uiApplication(application -> application.setBaseUrlRef("${ CLIENT_PORTAL_IFT_URL }")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("malformed placeholder");
    }

    @Test
    @DisplayName("a UI credential reference is guarded like every other *-ref, and has no value twin to route it through the Spring Environment")
    void uiCredentialsAreReferencesOnly() {
        assertThatThrownBy(() -> uiApplication(application -> application.getAuth().setCredentialsPoolRef("Basic dXNlcjpwYXNz")))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("reference NAME");
        assertThat(StandTestProperties.UiAuth.class.getMethods())
                .as("a value twin for a UI credential would put it in the Spring Environment; there is deliberately none")
                .noneMatch(method -> "setCredentialsPool".equals(method.getName()) || "setDiscoveryAccount".equals(method.getName()));
    }

    @Test
    @DisplayName("the UI base URL supports the same value twin as every other endpoint, and setting both twins is ambiguous")
    void uiBaseUrl_hasTheSameTwinRules() {
        assertThat(resolveLiteral(uiApplication(application -> {
            application.setBaseUrlRef(null);
            application.setBaseUrl("https://portal.ift.example");
        }).baseUrlRef())).isEqualTo("https://portal.ift.example");

        assertThatThrownBy(() -> uiApplication(application -> application.setBaseUrl("https://portal.ift.example")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'base-url' and 'base-url-ref'")
                .hasMessageContaining("configure exactly one");
    }

    private static UiApplicationDefinition uiApplication(java.util.function.Consumer<StandTestProperties.UiApplication> customiser) {
        return EnvironmentRegistryFactory.build(uiProperties(customiser))
                .environment("ift").orElseThrow()
                .uiApplication("client-portal").orElseThrow();
    }

    private static StandTestProperties uiProperties(java.util.function.Consumer<StandTestProperties.UiApplication> customiser) {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(3);
        final StandTestProperties.Environment ift = new StandTestProperties.Environment();

        StandTestProperties.UiApplication application = new StandTestProperties.UiApplication();
        application.setBaseUrlRef("CLIENT_PORTAL_IFT_URL");
        application.setDefaultViewport("desktop");
        StandTestProperties.Viewport desktop = new StandTestProperties.Viewport();
        desktop.setWidth(1440);
        desktop.setHeight(900);
        application.getViewportProfiles().put("desktop", desktop);
        application.setTrace("on-failure");
        StandTestProperties.UiAuth auth = new StandTestProperties.UiAuth();
        auth.setScheme(UiAuthScheme.FORM);
        auth.setCredentialsPoolRef("CLIENT_PORTAL_TEST_USERS");
        auth.getRoles().addAll(List.of("client", "operator"));
        auth.setDiscoveryAccountRef("CLIENT_PORTAL_DISCOVERY");
        StandTestProperties.UiLogin login = new StandTestProperties.UiLogin();
        login.setPath("/login");
        login.setUsernameLocator("testId=login-username");
        login.setPasswordLocator("testId=login-password");
        login.setSubmitLocator("role=button:Sign in");
        login.setSignedInLocator("testId=user-menu");
        auth.setLogin(login);
        application.setAuth(auth);
        customiser.accept(application);

        ift.getUiApplications().put("client-portal", application);
        properties.getEnvironments().put("ift", ift);
        return properties;
    }

    private static String resolveLiteral(String reference) {
        assertThat(SecretReferences.isLiteral(reference)).as("expected a literal-wrapped reference but got: %s", reference).isTrue();
        return SecretReferences.resolve(reference, name -> null);
    }
}
