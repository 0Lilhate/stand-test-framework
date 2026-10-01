package ru.alfa.stand.test.starter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentConfigFormat;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentSection;
import ru.alfa.stand.test.core.environment.SectionEntry;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.GrpcTargetDefinition;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.core.environment.UiLoginFormConfig;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.environment.ViewportProfile;

/**
 * Assembles an immutable {@link EnvironmentRegistry} from the mutable {@link StandTestProperties} tree.
 *
 * <p>This is the surface→internal boundary: the bound POJOs are translated into the core's immutable
 * {@code *Definition} records (which enforce the "references, never values" and "blank ref rejected"
 * invariants in their compact constructors). A construction failure is re-thrown with the offending
 * environment/alias so a misconfiguration is self-explanatory rather than a bare
 * {@code "baseUrlRef must not be blank"}.
 *
 * <p><strong>Empty by default.</strong> With no {@code stand.test.environments.*} configured the registry
 * is empty. After the strict runtime guardrail (an unknown environment is rejected before any step runs),
 * an empty registry means every scenario fails fast on {@code run} — so environments are effectively
 * mandatory. This is intentional: the starter never silently connects to a stand.
 *
 * <p><strong>Endpoint value fields.</strong> Non-secret endpoint fields have value twins
 * ({@code base-url}/{@code url}/{@code target}/{@code bootstrap-servers}/{@code security-protocol})
 * resolved by Spring at context startup — real {@code ${VAR:}} placeholders. A configured value (even
 * an empty one, from an unset variable behind {@code ${VAR:}}) is wrapped with
 * {@link SecretReferences#literal} so the core record invariants hold and adapters resolve it verbatim;
 * an empty value then fails lazily at step execution, preserving skip-without-stand behaviour. Each
 * value field is mutually exclusive with its {@code *-ref} twin. Credentials (auth
 * username/password/token, datasource user/password, Kafka SASL) also have value twins; supplying one
 * routes the Spring-resolved value through {@link SecretReferences#literal}, so a {@code ${VAR:default}}
 * placeholder for a secret works on this surface (Spring expands it at context startup and the resolved
 * value is used verbatim as the credential). The trade-off is the consumer's: a value given that way
 * materialises in the Spring {@code Environment} (reachable via actuator {@code /env}, logs, error dumps)
 * and any inline default lives in the configuration file — so the {@code *-ref} spelling (a bare env-var
 * NAME resolved lazily by the adapter, never bound into the Environment) remains the choice when a secret
 * must not appear anywhere but the environment variable.
 */
public final class EnvironmentRegistryFactory {

    private EnvironmentRegistryFactory() {
    }

    /**
     * Builds an in-memory registry from the bound properties.
     *
     * @param properties the bound stand-test properties
     * @return an immutable registry over the configured environments (possibly empty)
     */
    public static EnvironmentRegistry build(StandTestProperties properties) {
        int version = EnvironmentConfigFormat.requireSupported(properties.getVersion(), "stand.test");
        Map<String, EnvironmentDefinition> environments = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Environment> entry : properties.getEnvironments().entrySet()) {
            String name = entry.getKey();
            environments.put(name, toEnvironment(name, entry.getValue(), version));
        }
        String defaultEnvironment = properties.getDefaultEnvironment();
        if (defaultEnvironment != null) {
            EnvironmentConfigFormat.requireSectionSupported(version, "default-environment",
                    EnvironmentConfigFormat.DEFAULT_ENVIRONMENT_SINCE_VERSION, "stand.test");
        }
        return new InMemoryEnvironmentRegistry(environments, defaultEnvironment);
    }

    private static EnvironmentDefinition toEnvironment(String name, StandTestProperties.Environment env, int version) {
        try {
            return new EnvironmentDefinition(
                    name,
                    services(env),
                    topics(env),
                    datasources(env),
                    grpcTargets(env),
                    kafkaCluster(env.getKafkaCluster()),
                    kafkaClusters(env),
                    uiApplications(env, name, version), sections(env, name, version));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Invalid stand.test.environments." + name + " configuration: " + invalid.getMessage(), invalid);
        }
    }

    private static Map<String, EnvironmentSection> sections(StandTestProperties.Environment env, String environment, int version) {
        Map<String, Map<String, Object>> configured = env.getEqBackends();
        if (configured.isEmpty()) {
            return Map.of();
        }
        String sectionName = "eq-backends";
        EnvironmentConfigFormat.requireSectionSupported(version, sectionName, EnvironmentConfigFormat.SECTIONS_SINCE_VERSION,
                "stand.test.environments." + environment + "." + sectionName);
        Map<String, SectionEntry> entries = new LinkedHashMap<>();
        configured.forEach((alias, fields) -> entries.put(alias, new SectionEntry(alias, normalizeBoundMap(fields))));
        return Map.of(sectionName, new EnvironmentSection(sectionName, entries));
    }

    private static Map<String, Object> normalizeBoundMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            Object normalized = normalizeBoundValue(value);
            if ("write-allowed".equals(name) && normalized instanceof String text
                    && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
                normalized = Boolean.valueOf(text);
            }
            result.put(name, normalized);
        });
        return result;
    }

    private static Object normalizeBoundValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = normalizeBoundMap(map);
            if (!nested.isEmpty() && java.util.stream.IntStream.range(0, nested.size())
                    .allMatch(index -> nested.containsKey(String.valueOf(index)))) {
                return java.util.stream.IntStream.range(0, nested.size())
                        .mapToObj(index -> nested.get(String.valueOf(index))).toList();
            }
            return nested;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EnvironmentRegistryFactory::normalizeBoundValue).toList();
        }
        return value;
    }

    /**
     * Maps the {@code ui-applications} section, gated by the same format-version rule the file surface
     * applies: the section arrived in format version
     * {@link EnvironmentConfigFormat#UI_APPLICATIONS_SINCE_VERSION}, so a configuration carrying it must
     * declare at least that version. Both surfaces call the same core check, which is what keeps them from
     * disagreeing about what they can read.
     */
    private static Map<String, UiApplicationDefinition> uiApplications(StandTestProperties.Environment env, String environment,
            int version) {
        Map<String, StandTestProperties.UiApplication> configured = env.getUiApplications();
        if (configured.isEmpty()) {
            return Map.of();
        }
        EnvironmentConfigFormat.requireSectionSupported(
                version, "ui-applications", EnvironmentConfigFormat.UI_APPLICATIONS_SINCE_VERSION,
                "stand.test.environments." + environment + ".ui-applications");
        Map<String, UiApplicationDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.UiApplication> entry : configured.entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.UiApplication application = entry.getValue();
            result.put(alias, new UiApplicationDefinition(
                    alias,
                    refOrLiteral(application.getBaseUrl(), application.getBaseUrlRef(), "base-url", "base-url-ref", alias),
                    application.getDefaultViewport(),
                    viewportProfiles(application, alias),
                    UiTraceMode.fromConfig(application.getTrace()),
                    uiAuth(application.getAuth(), alias, environment, version)));
        }
        return result;
    }

    private static Map<String, ViewportProfile> viewportProfiles(StandTestProperties.UiApplication application, String alias) {
        Map<String, ViewportProfile> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Viewport> entry : application.getViewportProfiles().entrySet()) {
            StandTestProperties.Viewport viewport = entry.getValue();
            try {
                result.put(entry.getKey(), new ViewportProfile(viewport.getWidth(), viewport.getHeight()));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("ui application '" + alias + "' viewport profile '" + entry.getKey() + "': "
                        + invalid.getMessage(), invalid);
            }
        }
        return result;
    }

    /**
     * Maps a UI application's sign-in section, refusing any field whose format version the document does not
     * declare — {@code auth.login}/{@code auth.challenge} arrived in version 3, the direct credential pair in
     * version 4, its {@code *-ref} twins in version 5.
     *
     * <p>The account roster and the discovery account are references only: there is deliberately no value
     * twin for either, so neither can be routed through (and left in) the Spring Environment the way an
     * endpoint value can. The single-account pair is the one credential that has a twin here, and what its
     * two spellings mean depends on the declared version — see {@link #uiCredential}, which owns that rule
     * for both front-ends.
     */
    private static UiAuthConfig uiAuth(StandTestProperties.UiAuth auth, String alias, String environment, int version) {
        if (auth == null) {
            return null;
        }
        if (auth.getScheme() == null) {
            throw new IllegalArgumentException("ui application '" + alias + "' auth.scheme must not be null");
        }
        String location = "stand.test.environments." + environment + ".ui-applications." + alias + ".auth";
        if (auth.getLogin() != null) {
            EnvironmentConfigFormat.requireSectionSupported(version, "auth.login", EnvironmentConfigFormat.UI_LOGIN_SINCE_VERSION, location
                    + ".login");
        }
        if (auth.getChallenge() != null) {
            EnvironmentConfigFormat.requireSectionSupported(version, "auth.challenge", EnvironmentConfigFormat.UI_LOGIN_SINCE_VERSION,
                    location + ".challenge");
        }
        if (auth.getCredentialsUsername() != null || auth.getCredentialsPassword() != null) {
            EnvironmentConfigFormat.requireSectionSupported(
                    version, "auth.credentials-username/credentials-password", EnvironmentConfigFormat.UI_DIRECT_CREDENTIALS_SINCE_VERSION,
                            location);
        }
        if (auth.getCredentialsUsernameRef() != null || auth.getCredentialsPasswordRef() != null) {
            EnvironmentConfigFormat.requireSectionSupported(
                    version, "auth.credentials-username-ref/credentials-password-ref",
                    EnvironmentConfigFormat.UI_CREDENTIAL_VALUE_TWINS_SINCE_VERSION, location);
        }
        return new UiAuthConfig(
                auth.getScheme(),
                ref(auth.getCredentialsPoolRef(), "credentials-pool-ref", alias),
                List.copyOf(auth.getRoles()),
                ref(auth.getDiscoveryAccountRef(), "discovery-account-ref", alias),
                uiLogin(auth.getLogin()),
                (auth.getChallenge() == null) ? UiLoginChallenge.NONE : auth.getChallenge(),
                uiCredential(auth.getCredentialsUsername(), auth.getCredentialsUsernameRef(), "credentials-username", alias, location,
                        version),
                uiCredential(auth.getCredentialsPassword(), auth.getCredentialsPasswordRef(), "credentials-password", alias, location,
                        version));
    }

    /**
     * Maps the sign-in form. Its fields are locator expressions, not references: a locator is not a secret,
     * and it legitimately carries spaces and punctuation that {@code ref(...)} rejects. The one thing that
     * must not appear here is a credential, and there is nowhere to put one.
     */
    private static UiLoginFormConfig uiLogin(StandTestProperties.UiLogin login) {
        if (login == null) {
            return null;
        }
        return new UiLoginFormConfig(login.getPath(), login.getUsernameLocator(), login.getPasswordLocator(), login.getSubmitLocator(),
                login.getSignedInLocator());
    }

    private static Map<String, ServiceEndpointDefinition> services(StandTestProperties.Environment env) {
        Map<String, ServiceEndpointDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Service> entry : env.getServices().entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.Service service = entry.getValue();
            result.put(alias,
                    new ServiceEndpointDefinition(alias,
                    refOrLiteral(service.getBaseUrl(), service.getBaseUrlRef(), "base-url", "base-url-ref",
                    alias), correlation(service.getCorrelation()), auth(service.getAuth(), alias)));
        }
        return result;
    }

    private static AuthConfig auth(StandTestProperties.Auth auth, String alias) {
        if (auth == null) {
            return null;
        }
        if (auth.getScheme() == null) {
            throw new IllegalArgumentException("service '" + alias + "' auth.scheme must not be null");
        }
        return new AuthConfig(
                auth.getScheme(),
                refOrLiteral(auth.getUsername(), auth.getUsernameRef(), "username", "username-ref", alias),
                refOrLiteral(auth.getPassword(), auth.getPasswordRef(), "password", "password-ref", alias),
                refOrLiteral(auth.getToken(), auth.getTokenRef(), "token", "token-ref", alias));
    }

    /**
     * One UI credential, by the rule the document's format version puts on it.
     *
     * <p><strong>Version 4 and below:</strong> the bare {@code credentials-username} is a REFERENCE — the
     * name of an environment variable — and there is no value twin at all. That is the contract those
     * documents were written against, and it keeps working unchanged.
     *
     * <p><strong>From version 5:</strong> the bare field is the VALUE, like {@code base-url} and every
     * other twin here, and {@code *-ref} carries the reference. This is what makes
     * {@code credentials-username: ${web_username:tks_Admin}} work on the starter at all: Spring resolves
     * the placeholder before the SDK sees the field, so the SDK receives {@code tks_Admin} and cannot tell
     * it from a variable name — the two meanings need two keys, and the registry's convention already says
     * which is which.
     *
     * <p><strong>The cost of the value twin, stated where it is paid:</strong> a value routed this way
     * lives in the Spring Environment for the life of the context, so actuator's {@code /env}, a heap dump
     * and a context report can each show it, and any default written into the file stays in git history
     * after the credential is rotated. For a PASSWORD that is a real exposure and {@code *-ref} remains the
     * right spelling; for a login it is usually acceptable. The SDK offers both and refuses to decide for
     * a consumer — but the kit's safety gate does have an opinion, and flags a password value in a registry
     * document as a blocking finding.
     */
    private static String uiCredential(String value, String reference, String field, String alias, String location, int version) {
        if (version < EnvironmentConfigFormat.UI_CREDENTIAL_VALUE_TWINS_SINCE_VERSION) {
            return ref(value, field, alias);
        }
        if (value != null && reference != null && !reference.isBlank()) {
            throw new IllegalArgumentException("ui application '" + alias + "' sets both '" + field + "' and '"
                    + field + "-ref' — configure exactly one: the first is the value, the second the name of the variable holding it");
        }
        if (value == null) {
            return ref(reference, field + "-ref", alias);
        }
        EnvironmentConfigFormat.rejectVariableNameAsCredentialValue(value, field, location);
        SecretReferences.rejectLiteralMarkerInValue(value, field, location);
        return SecretReferences.literal(value);
    }

    private static Map<String, TopicDefinition> topics(StandTestProperties.Environment env) {
        Map<String, TopicDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Topic> entry : env.getTopics().entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.Topic topic = entry.getValue();
            result.put(alias, new TopicDefinition(alias, topic.getName(), correlation(topic.getCorrelation()), topic.getCluster()));
        }
        return result;
    }

    private static Map<String, DatasourceDefinition> datasources(StandTestProperties.Environment env) {
        Map<String, DatasourceDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Datasource> entry : env.getDatasources().entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.Datasource ds = entry.getValue();
            result.put(alias, new DatasourceDefinition(
                    alias,
                    refOrLiteral(ds.getUrl(), ds.getUrlRef(), "url", "url-ref", alias),
                    refOrLiteral(ds.getUser(), ds.getUserRef(), "user", "user-ref", alias),
                    refOrLiteral(ds.getPassword(), ds.getPasswordRef(), "password", "password-ref", alias),
                    Set.copyOf(ds.getAllowedSchemas()),
                    ds.isWriteAllowed()));
        }
        return result;
    }

    private static Map<String, GrpcTargetDefinition> grpcTargets(StandTestProperties.Environment env) {
        Map<String, GrpcTargetDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.GrpcTarget> entry : env.getGrpcTargets().entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.GrpcTarget target = entry.getValue();
            result.put(alias,
                    new GrpcTargetDefinition(alias, refOrLiteral(target.getTarget(), target.getTargetRef(), "target", "target-ref", alias),
                    correlation(target.getCorrelation())));
        }
        return result;
    }

    private static Map<String, KafkaClusterDefinition> kafkaClusters(StandTestProperties.Environment env) {
        Map<String, KafkaClusterDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.KafkaCluster> entry : env.getKafkaClusters().entrySet()) {
            result.put(entry.getKey(), kafkaCluster(entry.getValue()));
        }
        return result;
    }

    private static KafkaClusterDefinition kafkaCluster(StandTestProperties.KafkaCluster cluster) {
        if (cluster == null) {
            return null;
        }
        return new KafkaClusterDefinition(
                refOrLiteral(cluster.getBootstrapServers(), cluster.getBootstrapServersRef(), "bootstrap-servers", "bootstrap-servers-ref",
                        "kafka-cluster"),
                refOrLiteral(cluster.getSecurityProtocol(), cluster.getSecurityProtocolRef(), "security-protocol", "security-protocol-ref",
                        "kafka-cluster"),
                refOrLiteral(cluster.getSaslJaasConfig(), cluster.getSaslJaasConfigRef(), "sasl-jaas-config", "sasl-jaas-config-ref",
                        "kafka-cluster"));
    }

    /**
     * Chooses between an endpoint's Spring-resolved value field and its {@code *-ref} twin. A value is
     * "configured" when it is non-null — including the empty string an unset environment variable
     * behind a {@code ${VAR:}} placeholder resolves to; it is wrapped with
     * {@link SecretReferences#literal} so the failure is deferred to step execution. Setting both twins
     * is ambiguous and fails the context startup.
     */
    private static String refOrLiteral(String value, String reference, String valueField, String refField, String alias) {
        if (value == null) {
            return ref(reference, refField, alias);
        }
        if (reference != null && !reference.isBlank()) {
            throw new IllegalArgumentException(
                    "alias '" + alias + "' sets both '" + valueField + "' and '" + refField + "' — configure exactly one");
        }
        SecretReferences.rejectLiteralMarkerInValue(value, valueField, "alias '" + alias + "'");
        return SecretReferences.literal(value);
    }

    /**
     * Applies the shared {@code *-ref} shape guard to a configured reference. Null/blank values are
     * passed through untouched — the core {@code *Definition} constructors own the required/blank rule
     * (and its established error message); this guard only rejects present values that are obviously a
     * resolved endpoint or an inline secret rather than a reference name.
     */
    private static String ref(String value, String field, String alias) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return SecretReferences.requireReferenceShape(value, field, "stand.test.environments alias '" + alias + "'");
    }

    private static CorrelationConfig correlation(StandTestProperties.Correlation correlation) {
        if (correlation == null) {
            return null;
        }
        return new CorrelationConfig(correlation.getSource(), correlation.getName());
    }
}
