package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.config.YamlEnvironmentConfigLoader;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentSection;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.eq.config.EqBackendConfigParser;

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
    @DisplayName("BR-45: Spring binding and file YAML preserve the same nested version-6 backend section")
    void backendSectionParity() throws IOException {
        String yaml = """
                version: 6
                default-environment: ${STAND_TEST_STAGE1_UNSET:ift}
                environments:
                  ift:
                    eq-backends:
                      eq:
                        kind: showcases
                        service: showcases
                        path: /showcases/load/list
                  test:
                    eq-backends:
                      eq:
                        kind: gateway
                        write-allowed: true
                        base-url-ref: EQ_GATEWAY_URL
                        unit: {ref: EQ_UNIT}
                        branch: {ref: EQ_BRANCH}
                        inn-region-code: ${STAND_TEST_STAGE1_UNSET:77}
                        inn-tax-offices: {ref: EQ_INN_TAX_OFFICES}
                        cash-accounts:
                          RUR: {ref: EQ_CASH_RUR}
                          USD: {ref: EQ_CASH_USD}
                          EUR: {ref: EQ_CASH_EUR}
                        timeouts: {connect: 10s, response: 60s}
                        serialization:
                          acquire-timeout: ${STAND_TEST_STAGE1_UNSET:10m}
                        unit-phase:
                          system-ref: EQ_AS400_SYSTEM
                          username-ref: EQ_AS400_USER
                          password-ref: EQ_AS400_PASSWORD
                          allowed: [ACTIVE, READY]
                          cache-ttl: 5m
                        visibility:
                          probe:
                            service: tks
                            path: /api/client
                            query: {clientCode: '{seed.pin}'}
                            expect-status: 200
                            expect-body: {path: '$.clientCode', equals: '{seed.pin}'}
                          timeout: ${STAND_TEST_STAGE1_UNSET:120s}
                          poll-interval: 2s
                        defaults:
                          organisation: {name-prefix: 'ООО АТ'}
                          account: {type-organisation: CA, type-individual: EE, currency: RUR, top-up: 100000}
                """;
        EnvironmentRegistry fromYaml = loadFromYaml(yaml);
        EnvironmentSection expected = fromYaml.environment("test").orElseThrow().section("eq-backends").orElseThrow();
        System.clearProperty(CONFIG_PATH_PROPERTY);
        String starterYaml = "stand:\n  test:\n" + yaml.indent(4);
        var propertySources = new YamlPropertySourceLoader().load("stage-one",
                new ByteArrayResource(starterYaml.getBytes(StandardCharsets.UTF_8)));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StandTestAutoConfiguration.class))
                .withInitializer(context -> propertySources.forEach(context.getEnvironment().getPropertySources()::addFirst))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EnvironmentSection actual = context.getBean(EnvironmentRegistry.class).environment("test")
                            .orElseThrow().section("eq-backends").orElseThrow();
                    assertThat(actual).isEqualTo(expected);
                    assertThat(EqBackendConfigParser.parse("test", actual.entries().get("eq")))
                            .isEqualTo(EqBackendConfigParser.parse("test", expected.entries().get("eq")));
                    assertThat(context.getBean(EnvironmentRegistry.class).environment("ift").orElseThrow()
                            .section("eq-backends")).isEqualTo(fromYaml.environment("ift").orElseThrow().section("eq-backends"));
                });
    }

    @Test
    @DisplayName("BR-45: v5 remains readable, v6 accepts no new keys, and v7 is rejected on both surfaces")
    void versionSixCompatibility() throws IOException {
        for (int version : List.of(5, 6)) {
            StandTestProperties properties = new StandTestProperties();
            properties.setVersion(version);
            properties.getEnvironments().put("ift", new StandTestProperties.Environment());
            assertThat(EnvironmentRegistryFactory.build(properties).environment("ift")).isPresent();
            assertThat(loadFromYaml("version: " + version + "\nenvironments: {ift: {}}\n").environment("ift"))
                    .isPresent();
        }
        StandTestProperties future = new StandTestProperties();
        future.setVersion(7);
        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(future)).hasMessageContaining("up to 6");
        assertThatThrownBy(() -> loadFromYaml("version: 7\nenvironments: {}\n")).hasMessageContaining("up to 6");

        StandTestProperties oldWithSection = new StandTestProperties();
        oldWithSection.setVersion(5);
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        ift.getEqBackends().put("eq", java.util.Map.of("kind", "showcases"));
        oldWithSection.getEnvironments().put("ift", ift);
        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(oldWithSection)).hasMessageContaining("version 6");
        assertThatThrownBy(() -> loadFromYaml("""
                version: 5
                environments:
                  ift:
                    eq-backends:
                      eq: {kind: showcases}
                """)).hasMessageContaining("version 6");
    }

    @Test
    @DisplayName("BR-01/04: default environment is version-gated and validated on both registry surfaces")
    void defaultEnvironmentParity() throws IOException {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(6);
        properties.setDefaultEnvironment("ift");
        properties.getEnvironments().put("ift", new StandTestProperties.Environment());

        assertThat(EnvironmentRegistryFactory.build(properties).defaultEnvironment()).contains("ift");
        assertThat(loadFromYaml("""
                version: 6
                default-environment: ${STAND_TEST_STAGE1_UNSET:ift}
                environments: {ift: {}}
                """).defaultEnvironment()).contains("ift");

        properties.setDefaultEnvironment("missing");
        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties)).hasMessageContaining("missing");
        assertThatThrownBy(() -> loadFromYaml("""
                version: 6
                default-environment: missing
                environments: {ift: {}}
                """)).hasMessageContaining("missing");
        properties.setVersion(5);
        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties)).hasMessageContaining("version 6");
        assertThatThrownBy(() -> loadFromYaml("""
                version: 5
                default-environment: ift
                environments: {ift: {}}
                """)).hasMessageContaining("version 6");
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

    /**
     * The anti-drift contract test named by ADR-UI-004: the SAME configuration — including the format
     * version and the UI section — described through both surfaces must produce equal core records. The
     * two mappers are hand-maintained and the UI section is the first one written into both at once, so a
     * renamed key, a dropped field or a different default on either side surfaces here rather than at a
     * consumer.
     */
    @Test
    @DisplayName("both surfaces agree on the same config: a version-4 registry with a ui-applications section, a login form and both ways of naming an account maps identically")
    void bothSurfacesAgreeOnTheSameConfig() throws IOException {
        StandTestProperties properties = standTestProperties();
        properties.setVersion(4);
        final StandTestProperties.Environment ift = properties.getEnvironments().get("ift");

        StandTestProperties.UiApplication portal = new StandTestProperties.UiApplication();
        portal.setBaseUrlRef("CLIENT_PORTAL_IFT_URL");
        portal.setDefaultViewport("desktop");
        portal.getViewportProfiles().put("desktop", viewport(1440, 900));
        portal.getViewportProfiles().put("mobile", viewport(390, 844));
        portal.setTrace("off");
        StandTestProperties.UiAuth auth = new StandTestProperties.UiAuth();
        auth.setScheme(UiAuthScheme.FORM);
        auth.setCredentialsPoolRef("CLIENT_PORTAL_TEST_USERS");
        auth.getRoles().addAll(List.of("client", "operator", "no-rights"));
        auth.setDiscoveryAccountRef("CLIENT_PORTAL_DISCOVERY");
        // Deliberately not NONE. NONE is what an unread property maps to, so a fixture leaving it at the
        // default asserts nothing about the field: both mappers could stop reading `challenge` entirely and
        // this comparison would still pass. With MFA on both sides the field is load-bearing — and a mapper
        // that silently yields NONE would turn the G-1 refusal into a browser driven into an OTP screen.
        auth.setChallenge(UiLoginChallenge.MFA);
        StandTestProperties.UiLogin login = new StandTestProperties.UiLogin();
        login.setPath("/login");
        login.setUsernameLocator("testId=login-username");
        login.setPasswordLocator("testId=login-password");
        login.setSubmitLocator("role=button:Sign in");
        login.setSignedInLocator("testId=user-menu");
        auth.setLogin(login);
        portal.setAuth(auth);
        ift.getUiApplications().put("client-portal", portal);

        // The second application carries the OTHER way of naming an account — the direct pair, which
        // excludes a roster — so between the two every component of UiAuthConfig is exercised.
        StandTestProperties.UiApplication backOffice = new StandTestProperties.UiApplication();
        backOffice.setBaseUrlRef("BACK_OFFICE_IFT_URL");
        backOffice.setTrace("on-failure");
        StandTestProperties.UiAuth backOfficeAuth = new StandTestProperties.UiAuth();
        backOfficeAuth.setScheme(UiAuthScheme.STORAGE_STATE);
        backOfficeAuth.setCredentialsUsername("BACK_OFFICE_USERNAME");
        backOfficeAuth.setCredentialsPassword("BACK_OFFICE_PASSWORD");
        StandTestProperties.UiLogin backOfficeLogin = new StandTestProperties.UiLogin();
        backOfficeLogin.setSignedInLocator("testId=user-menu");
        backOfficeAuth.setLogin(backOfficeLogin);
        backOffice.setAuth(backOfficeAuth);
        ift.getUiApplications().put("back-office", backOffice);

        EnvironmentRegistry fromProperties = EnvironmentRegistryFactory.build(properties);
        EnvironmentRegistry fromYaml = loadFromYaml("""
                version: 4
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
                          challenge: mfa
                          login:
                            path: /login
                            username-locator: testId=login-username
                            password-locator: testId=login-password
                            submit-locator: "role=button:Sign in"
                            signed-in-locator: testId=user-menu
                      back-office:
                        base-url-ref: BACK_OFFICE_IFT_URL
                        trace: on-failure
                        auth:
                          scheme: STORAGE_STATE
                          credentials-username: BACK_OFFICE_USERNAME
                          credentials-password: BACK_OFFICE_PASSWORD
                          login:
                            signed-in-locator: testId=user-menu
                    kafka-cluster:
                      bootstrap-servers-ref: KAFKA_BOOTSTRAP
                      security-protocol-ref: KAFKA_SECURITY
                      sasl-jaas-config-ref: KAFKA_JAAS
                """);

        assertThat(fromProperties.environment("ift").orElseThrow())
                .isEqualTo(fromYaml.environment("ift").orElseThrow());

        // The comparison above only proves what the fixture actually sets, and the fixture is written by
        // hand — which is precisely how a field can be added to one mapper, forgotten in the other, and stay
        // invisible: nobody remembers to extend two mirrored fixtures. So the fixture is checked against the
        // model rather than against somebody's memory. A component added to UiAuthConfig or UiLoginFormConfig
        // fails here until both surfaces exercise it with a value that is not the one an unread field yields.
        // Checked ACROSS the two applications rather than on one, because the roster and the direct pair
        // exclude each other: no single auth section can carry both, so demanding it of one would make the
        // fixture unwritable rather than strict. What must hold is that every component is load-bearing
        // SOMEWHERE — a field dropped by one mapper still shows up as a mismatch above.
        UiAuthConfig portalAuth = fromYaml.environment("ift").orElseThrow().uiApplications().get("client-portal").auth();
        UiAuthConfig backOfficeAuthFromYaml = fromYaml.environment("ift").orElseThrow().uiApplications().get("back-office").auth();
        assertEveryComponentIsExercisedAcross(List.of(portalAuth, backOfficeAuthFromYaml));
        assertEveryComponentIsExercised(portalAuth.login());
    }

    @Test
    @DisplayName("both surfaces gate auth.challenge on the same declared format version too — the field that decides whether a sign-in demands a G-1 handler")
    void bothSurfacesGateTheChallengeOnVersion3() throws IOException {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(2);
        final StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.UiApplication portal = new StandTestProperties.UiApplication();
        portal.setBaseUrlRef("CLIENT_PORTAL_IFT_URL");
        StandTestProperties.UiAuth auth = new StandTestProperties.UiAuth();
        auth.setScheme(UiAuthScheme.FORM);
        auth.setCredentialsPoolRef("CLIENT_PORTAL_TEST_USERS");
        auth.setChallenge(UiLoginChallenge.MFA);
        portal.setAuth(auth);
        ift.getUiApplications().put("client-portal", portal);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("auth.challenge")
                .hasMessageContaining("format version 3");
        assertThatThrownBy(() -> loadFromYaml("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: CLIENT_PORTAL_IFT_URL
                        auth:
                          scheme: FORM
                          credentials-pool-ref: CLIENT_PORTAL_TEST_USERS
                          challenge: mfa
                """))
                .hasMessageContaining("auth.challenge")
                .hasMessageContaining("format version 3");
    }

    /**
     * The same check spread over several records of one type: each component must be exercised by at least
     * one of them. Used where the model makes two fields mutually exclusive, so no single instance can
     * carry every component at once.
     */
    private static void assertEveryComponentIsExercisedAcross(List<?> records) {
        for (RecordComponent component : records.get(0).getClass().getRecordComponents()) {
            boolean exercised = records.stream().map(record -> read(record, component)).anyMatch(EnvironmentRegistryParityTest::isExercised);
            assertThat(exercised)
                    .as("the parity fixture must set %s.%s on at least one of its applications to something an unread field could not produce"
                            + " — otherwise both mappers could drop it and this test would still pass",
                            records.get(0).getClass().getSimpleName(), component.getName())
                    .isTrue();
        }
    }

    private static boolean isExercised(Object value) {
        return value != null && !List.of().equals(value) && !"".equals(value) && value != UiLoginChallenge.NONE && value != UiAuthScheme.NONE;
    }

    /**
     * Asserts that the parity fixture gave every component of a record a value distinguishable from the one
     * an unread field produces — null, an empty list, or the neutral enum constant a mapper falls back to.
     */
    private static void assertEveryComponentIsExercised(Object record) {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            assertThat(read(record, component))
                    .as("the parity fixture must set %s.%s to something an unread field could not produce — otherwise both mappers could drop it and this test would still pass",
                            record.getClass().getSimpleName(), component.getName())
                    .isNotNull()
                    .isNotIn(List.of(), "", UiLoginChallenge.NONE, UiAuthScheme.NONE);
        }
    }

    private static Object read(Object record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException unreadable) {
            throw new IllegalStateException("Could not read component '" + component.getName() + "'", unreadable);
        }
    }

    @Test
    @DisplayName("both surfaces gate auth.login on the same declared format version, and say so the same way")
    void bothSurfacesGateTheLoginSectionOnVersion3() throws IOException {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(2);
        final StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.UiApplication portal = new StandTestProperties.UiApplication();
        portal.setBaseUrlRef("CLIENT_PORTAL_IFT_URL");
        StandTestProperties.UiAuth auth = new StandTestProperties.UiAuth();
        auth.setScheme(UiAuthScheme.STORAGE_STATE);
        auth.setCredentialsPoolRef("CLIENT_PORTAL_TEST_USERS");
        StandTestProperties.UiLogin login = new StandTestProperties.UiLogin();
        login.setSignedInLocator("testId=user-menu");
        auth.setLogin(login);
        portal.setAuth(auth);
        ift.getUiApplications().put("client-portal", portal);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("auth.login")
                .hasMessageContaining("format version 3");
        assertThatThrownBy(() -> loadFromYaml("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: CLIENT_PORTAL_IFT_URL
                        auth:
                          scheme: STORAGE_STATE
                          credentials-pool-ref: CLIENT_PORTAL_TEST_USERS
                          login: { signed-in-locator: testId=user-menu }
                """))
                .hasMessageContaining("auth.login")
                .hasMessageContaining("format version 3");
    }

    @Test
    @DisplayName("both surfaces gate the ui-applications section on the same declared format version")
    void bothSurfacesRequireTheSameFormatVersion() {
        StandTestProperties properties = new StandTestProperties();
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.UiApplication portal = new StandTestProperties.UiApplication();
        portal.setBaseUrlRef("CLIENT_PORTAL_IFT_URL");
        ift.getUiApplications().put("client-portal", portal);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("requires environment registry format version 2");
        assertThatThrownBy(() -> loadFromYaml("""
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: CLIENT_PORTAL_IFT_URL
                """))
                .hasMessageContaining("requires environment registry format version 2");
    }

    @Test
    @DisplayName("both surfaces refuse a format version newer than the SDK reads, with the same message")
    void bothSurfacesRejectANewerFormatVersion() {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(99);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("format version 99")
                .hasMessageContaining("upgrade the stand-test-* dependencies");
        assertThatThrownBy(() -> loadFromYaml("version: 99\nenvironments: {}\n"))
                .hasMessageContaining("format version 99")
                .hasMessageContaining("upgrade the stand-test-* dependencies");
    }

    @Test
    @DisplayName("both surfaces reject the same value-shaped UI reference — the UI guard cannot drift one-sided either")
    void bothSurfacesRejectValueShapedUiReference() {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(3);
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.UiApplication portal = new StandTestProperties.UiApplication();
        portal.setBaseUrlRef("https://portal.ift.example");
        ift.getUiApplications().put("client-portal", portal);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("reference NAME");
        assertThatThrownBy(() -> loadFromYaml("""
                version: 2
                environments:
                  ift:
                    ui-applications:
                      client-portal:
                        base-url-ref: https://portal.ift.example
                """))
                .hasMessageContaining("reference NAME");
    }

    @Test
    @DisplayName("endpoint value fields are deliberately starter-only: the config YAML surface rejects them fail-closed")
    void valueFieldsAreStarterOnly() {
        assertThatThrownBy(() -> loadFromYaml("""
                environments:
                  ift:
                    services:
                      client-service:
                        base-url: https://real-stand.example
                """))
                .hasMessageContaining("Unknown field");
    }

    @Test
    @DisplayName("both surfaces refuse the SDK-internal marker in a credential VALUE, not just in a *-ref — the newer guard is not one-sided either")
    void bothSurfacesRejectTheLiteralMarkerInAValue() {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(5);
        StandTestProperties.UiApplication application = new StandTestProperties.UiApplication();
        application.setBaseUrlRef("TAKSA_IFT_URL");
        StandTestProperties.UiAuth auth = new StandTestProperties.UiAuth();
        auth.setScheme(UiAuthScheme.STORAGE_STATE);
        auth.setCredentialsUsername("admin");
        auth.setCredentialsPassword("literal://s3cret");
        auth.getRoles().add("admin");
        StandTestProperties.UiLogin login = new StandTestProperties.UiLogin();
        login.setSignedInLocator("text=Выйти");
        auth.setLogin(login);
        application.setAuth(auth);
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        ift.getUiApplications().put("taksa", application);
        properties.getEnvironments().put("ift", ift);

        assertThatThrownBy(() -> EnvironmentRegistryFactory.build(properties))
                .hasMessageContaining("SDK-internal literal marker");
        assertThatThrownBy(() -> loadFromYaml("""
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
                .hasMessageContaining("SDK-internal literal marker");
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

    private static StandTestProperties.Viewport viewport(int width, int height) {
        StandTestProperties.Viewport viewport = new StandTestProperties.Viewport();
        viewport.setWidth(width);
        viewport.setHeight(height);
        return viewport;
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
