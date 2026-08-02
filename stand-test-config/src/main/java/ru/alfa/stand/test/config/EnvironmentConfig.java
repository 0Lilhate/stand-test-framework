package ru.alfa.stand.test.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentConfigFormat;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.GrpcTargetDefinition;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.environment.UiApplicationDefinition;
import ru.alfa.stand.test.core.environment.UiAuthConfig;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiTraceMode;
import ru.alfa.stand.test.core.environment.ViewportProfile;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Maps a parsed YAML/JSON tree ({@code Map}/{@code List}/scalar) into an immutable core
 * {@link EnvironmentRegistry}. This is the canonical file-config schema mapper: it mirrors the logical
 * shape the Spring starter binds under {@code stand.test.environments}, but takes a neutral map (so it has
 * no Spring dependency).
 *
 * <p>Fail-closed (plan §8.3): unknown keys, ill-typed values and invalid references are rejected as
 * config-class {@link StandTestException} with a dotted location. Field names are kebab-case
 * (canonical); the camelCase spelling is also accepted. Only <strong>references</strong>
 * (environment-variable names) are stored, never resolved addresses/secrets.
 *
 * <p>The one root key besides {@code environments} is {@code version}: the <em>format</em> version of the
 * document, governed by {@link EnvironmentConfigFormat}. It is what turns "this file was written for a
 * newer SDK" from {@code Unknown field '<new-section>'} into a message naming both versions and the
 * action. Absent means version 1, so every file written before versioning existed keeps loading unchanged.
 */
public final class EnvironmentConfig {

    private static final Set<String> ROOT_KEYS = Set.of("environments", EnvironmentConfigFormat.VERSION_FIELD);
    private static final Set<String> ENV_KEYS = Set.of("services", "topics", "datasources", "grpc-targets", "grpcTargets", "kafka-cluster", "kafkaCluster", "kafka-clusters", "kafkaClusters", "ui-applications", "uiApplications");
    private static final Set<String> SERVICE_KEYS = Set.of("base-url-ref", "baseUrlRef", "correlation", "auth");

    private static final Set<String> AUTH_KEYS = Set.of("scheme", "username-ref", "usernameRef", "password-ref", "passwordRef", "token-ref", "tokenRef");
    private static final Set<String> UI_APPLICATION_KEYS = Set.of("base-url-ref", "baseUrlRef", "default-viewport", "defaultViewport", "viewport-profiles", "viewportProfiles", "trace", "auth");
    private static final Set<String> UI_AUTH_KEYS = Set.of("scheme", "credentials-pool-ref", "credentialsPoolRef", "roles", "discovery-account-ref", "discoveryAccountRef");
    private static final Set<String> VIEWPORT_KEYS = Set.of("width", "height");
    private static final Set<String> TOPIC_KEYS = Set.of("name", "correlation", "cluster");
    private static final Set<String> DATASOURCE_KEYS = Set.of("url-ref", "urlRef", "user-ref", "userRef", "password-ref", "passwordRef", "allowed-schemas", "allowedSchemas", "write-allowed", "writeAllowed");
    private static final Set<String> GRPC_KEYS = Set.of("target-ref", "targetRef", "correlation");
    private static final Set<String> KAFKA_KEYS = Set.of("bootstrap-servers-ref", "bootstrapServersRef", "security-protocol-ref", "securityProtocolRef", "sasl-jaas-config-ref", "saslJaasConfigRef");
    private static final Set<String> CORRELATION_KEYS = Set.of("source", "name");

    private EnvironmentConfig() {
    }

    /**
     * Builds a registry from a parsed config tree (the result of {@link SafeYaml#load(String)}).
     *
     * @param root the parsed document (a map with an {@code environments} key), or null for an empty document
     * @return an immutable environment registry
     */
    public static EnvironmentRegistry toRegistry(Object root) {
        if (root == null) {
            return new InMemoryEnvironmentRegistry(Map.of());
        }
        Map<String, Object> document = asMap(root, "<document>");
        checkKnownKeys(document, ROOT_KEYS, "<document>");
        int version = EnvironmentConfigFormat.requireSupported(document.get(EnvironmentConfigFormat.VERSION_FIELD), "<document>");
        Map<String, Object> environments = namedMap(document.get("environments"), "environments");
        Map<String, EnvironmentDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : environments.entrySet()) {
            result.put(entry.getKey(), environment(entry.getKey(), entry.getValue(), version));
        }
        return new InMemoryEnvironmentRegistry(result);
    }

    private static EnvironmentDefinition environment(String name, Object value, int version) {
        String location = "environments." + name;
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, ENV_KEYS, location);
        Map<String, ServiceEndpointDefinition> services = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(fields.get("services"), location + ".services").entrySet()) {
            services.put(entry.getKey(), service(entry.getKey(), entry.getValue(), location + ".services." + entry.getKey()));
        }
        Map<String, TopicDefinition> topics = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(fields.get("topics"), location + ".topics").entrySet()) {
            topics.put(entry.getKey(), topic(entry.getKey(), entry.getValue(), location + ".topics." + entry.getKey()));
        }
        Map<String, DatasourceDefinition> datasources = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(fields.get("datasources"), location + ".datasources").entrySet()) {
            datasources.put(entry.getKey(), datasource(entry.getKey(), entry.getValue(), location + ".datasources." + entry.getKey()));
        }
        Map<String, GrpcTargetDefinition> grpcTargets = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(pick(fields, "grpc-targets", "grpcTargets"), location + ".grpc-targets").entrySet()) {
            grpcTargets.put(entry.getKey(), grpcTarget(entry.getKey(), entry.getValue(), location + ".grpc-targets." + entry.getKey()));
        }
        KafkaClusterDefinition kafkaCluster = kafkaCluster(pick(fields, "kafka-cluster", "kafkaCluster"), location + ".kafka-cluster");
        Map<String, KafkaClusterDefinition> kafkaClusters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(pick(fields, "kafka-clusters", "kafkaClusters"), location + ".kafka-clusters").entrySet()) {
            kafkaClusters.put(entry.getKey(), kafkaCluster(entry.getValue(), location + ".kafka-clusters." + entry.getKey()));
        }
        Map<String, UiApplicationDefinition> uiApplications = uiApplications(pick(fields, "ui-applications", "uiApplications"), location, version);
        return build(location, () -> new EnvironmentDefinition(name, services, topics, datasources, grpcTargets, kafkaCluster, kafkaClusters, uiApplications));
    }

    /**
     * Reads the per-environment {@code ui-applications} section — the whitelist that makes a UI application
     * addressable by a logical alias instead of a URL. The section arrived with format version
     * {@link EnvironmentConfigFormat#UI_APPLICATIONS_SINCE_VERSION}, so a document carrying it must declare
     * at least that version: without the declaration an SDK that predates the section would meet the bare
     * {@code Unknown field 'ui-applications'} this versioning exists to replace.
     */
    private static Map<String, UiApplicationDefinition> uiApplications(Object value, String environmentLocation, int version) {
        String location = environmentLocation + ".ui-applications";
        if (value == null) {
            return Map.of();
        }
        EnvironmentConfigFormat.requireSectionSupported(version, "ui-applications", EnvironmentConfigFormat.UI_APPLICATIONS_SINCE_VERSION, location);
        Map<String, UiApplicationDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(value, location).entrySet()) {
            result.put(entry.getKey(), uiApplication(entry.getKey(), entry.getValue(), location + "." + entry.getKey()));
        }
        return result;
    }

    private static UiApplicationDefinition uiApplication(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, UI_APPLICATION_KEYS, location);
        String baseUrlRef = requireReference(fields, "base-url-ref", "baseUrlRef", location);
        String defaultViewport = optionalString(pick(fields, "default-viewport", "defaultViewport"), location + ".default-viewport");
        Map<String, ViewportProfile> viewportProfiles = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : namedMap(pick(fields, "viewport-profiles", "viewportProfiles"), location + ".viewport-profiles").entrySet()) {
            viewportProfiles.put(entry.getKey(), viewportProfile(entry.getValue(), location + ".viewport-profiles." + entry.getKey()));
        }
        UiTraceMode trace = traceMode(fields.get("trace"), location + ".trace");
        UiAuthConfig auth = uiAuth(fields.get("auth"), location + ".auth");
        return build(location, () -> new UiApplicationDefinition(alias, baseUrlRef, defaultViewport, viewportProfiles, trace, auth));
    }

    private static ViewportProfile viewportProfile(Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, VIEWPORT_KEYS, location);
        int width = requireInt(fields.get("width"), location + ".width");
        int height = requireInt(fields.get("height"), location + ".height");
        return build(location, () -> new ViewportProfile(width, height));
    }

    /**
     * Reads the {@code trace} flag through the core parser both surfaces share (which is also where the
     * YAML-1.1 {@code off == false} subtlety is handled), re-labelling its rejection with this file's
     * dotted location.
     */
    private static UiTraceMode traceMode(Object value, String location) {
        try {
            return UiTraceMode.fromConfig(value);
        } catch (IllegalArgumentException rejected) {
            throw new StandTestException("Field 'trace' at " + location + ": " + rejected.getMessage(), rejected);
        }
    }

    private static UiAuthConfig uiAuth(Object value, String location) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, UI_AUTH_KEYS, location);
        UiAuthScheme scheme = uiAuthScheme(requireString(fields, "scheme", "scheme", location), location);
        String credentialsPoolRef = optionalReference(fields, "credentials-pool-ref", "credentialsPoolRef", location);
        String discoveryAccountRef = optionalReference(fields, "discovery-account-ref", "discoveryAccountRef", location);
        List<String> roles = stringList(fields.get("roles"), location + ".roles");
        return build(location, () -> new UiAuthConfig(scheme, credentialsPoolRef, roles, discoveryAccountRef));
    }

    private static UiAuthScheme uiAuthScheme(String scheme, String location) {
        try {
            return UiAuthScheme.valueOf(scheme.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new StandTestException("Field 'scheme' at " + location + " must be one of " + Set.of(UiAuthScheme.values()) + ", but was '" + scheme + "'");
        }
    }

    private static ServiceEndpointDefinition service(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, SERVICE_KEYS, location);
        String baseUrlRef = requireReference(fields, "base-url-ref", "baseUrlRef", location);
        CorrelationConfig correlation = correlation(fields.get("correlation"), location + ".correlation");
        AuthConfig auth = auth(fields.get("auth"), location + ".auth");
        return build(location, () -> new ServiceEndpointDefinition(alias, baseUrlRef, correlation, auth));
    }

    private static TopicDefinition topic(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, TOPIC_KEYS, location);
        String name = requireString(fields, "name", "name", location);
        CorrelationConfig correlation = correlation(fields.get("correlation"), location + ".correlation");
        String cluster = optionalString(fields.get("cluster"), location + ".cluster");
        return build(location, () -> new TopicDefinition(alias, name, correlation, cluster));
    }

    private static DatasourceDefinition datasource(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, DATASOURCE_KEYS, location);
        String urlRef = requireReference(fields, "url-ref", "urlRef", location);
        String userRef = requireReference(fields, "user-ref", "userRef", location);
        String passwordRef = requireReference(fields, "password-ref", "passwordRef", location);
        Set<String> allowedSchemas = stringSet(pick(fields, "allowed-schemas", "allowedSchemas"), location + ".allowed-schemas");
        boolean writeAllowed = boolFlag(pick(fields, "write-allowed", "writeAllowed"), location + ".write-allowed");
        return build(location, () -> new DatasourceDefinition(alias, urlRef, userRef, passwordRef, allowedSchemas, writeAllowed));
    }

    private static GrpcTargetDefinition grpcTarget(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, GRPC_KEYS, location);
        String targetRef = requireReference(fields, "target-ref", "targetRef", location);
        CorrelationConfig correlation = correlation(fields.get("correlation"), location + ".correlation");
        return build(location, () -> new GrpcTargetDefinition(alias, targetRef, correlation));
    }

    private static KafkaClusterDefinition kafkaCluster(Object value, String location) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, KAFKA_KEYS, location);
        String bootstrapServersRef = requireReference(fields, "bootstrap-servers-ref", "bootstrapServersRef", location);
        String securityProtocolRef = optionalReference(fields, "security-protocol-ref", "securityProtocolRef", location);
        String saslJaasConfigRef = optionalReference(fields, "sasl-jaas-config-ref", "saslJaasConfigRef", location);
        return build(location, () -> new KafkaClusterDefinition(bootstrapServersRef, securityProtocolRef, saslJaasConfigRef));
    }

    private static CorrelationConfig correlation(Object value, String location) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, CORRELATION_KEYS, location);
        String source = requireString(fields, "source", "source", location);
        String name = requireString(fields, "name", "name", location);
        CorrelationSource parsedSource = correlationSource(source, location);
        return build(location, () -> new CorrelationConfig(parsedSource, name));
    }

    private static CorrelationSource correlationSource(String source, String location) {
        try {
            return CorrelationSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new StandTestException("Field 'source' at " + location + " must be one of " + Set.of(CorrelationSource.values()) + ", but was '" + source + "'");
        }
    }

    private static AuthConfig auth(Object value, String location) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, AUTH_KEYS, location);
        AuthScheme scheme = authScheme(requireString(fields, "scheme", "scheme", location), location);
        String usernameRef = optionalReference(fields, "username-ref", "usernameRef", location);
        String passwordRef = optionalReference(fields, "password-ref", "passwordRef", location);
        String tokenRef = optionalReference(fields, "token-ref", "tokenRef", location);
        return build(location, () -> new AuthConfig(scheme, usernameRef, passwordRef, tokenRef));
    }

    private static AuthScheme authScheme(String scheme, String location) {
        try {
            return AuthScheme.valueOf(scheme.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new StandTestException("Field 'scheme' at " + location + " must be one of " + Set.of(AuthScheme.values()) + ", but was '" + scheme + "'");
        }
    }

    // ---- generic helpers (self-contained; mirrors scenario-yaml SurfaceValues style) ----

    private static <T> T build(String location, Supplier<T> constructor) {
        try {
            return constructor.get();
        } catch (IllegalArgumentException invalid) {
            throw new StandTestException(location + ": " + invalid.getMessage(), invalid);
        }
    }

    private static Object pick(Map<String, Object> fields, String kebab, String camel) {
        Object value = fields.get(kebab);
        return (value != null) ? value : fields.get(camel);
    }

    private static Map<String, Object> asMap(Object value, String location) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new StandTestException("Expected a mapping at " + location + ", but found " + typeOf(value));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Map<String, Object> namedMap(Object value, String location) {
        return (value == null) ? Map.of() : asMap(value, location);
    }

    private static void checkKnownKeys(Map<String, Object> fields, Set<String> known, String location) {
        for (String key : fields.keySet()) {
            if (!known.contains(key)) {
                throw new StandTestException("Unknown field '" + key + "' at " + location + " (allowed: " + known + ")");
            }
        }
    }

    private static String requireString(Map<String, Object> fields, String kebab, String camel, String location) {
        Object value = pick(fields, kebab, camel);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("Field '" + kebab + "' at " + location + " must be a non-blank string");
        }
        return text;
    }

    /**
     * Reads a required {@code *-ref} field and applies the shared reference-shape guard: a reference is
     * the NAME of an env-var/secret entry, so a value carrying whitespace, a {@code ://} scheme or a
     * {@code Bearer}/{@code Basic} prefix is rejected fail-closed. NOT applied to non-ref strings
     * (correlation/topic {@code name}), where values like {@code X-Correlation-Id} are legitimate.
     */
    private static String requireReference(Map<String, Object> fields, String kebab, String camel, String location) {
        return SecretReferences.requireReferenceShape(requireString(fields, kebab, camel, location), kebab, location);
    }

    private static String optionalReference(Map<String, Object> fields, String kebab, String camel, String location) {
        String value = optionalString(pick(fields, kebab, camel), location);
        return (value == null) ? null : SecretReferences.requireReferenceShape(value, kebab, location);
    }

    private static String optionalString(Object value, String location) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("Field at " + location + " must be a string");
        }
        return text;
    }

    private static boolean boolFlag(Object value, String location) {
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new StandTestException("Field at " + location + " must be a boolean");
        }
        return flag;
    }

    private static int requireInt(Object value, String location) {
        if (!(value instanceof Integer number)) {
            throw new StandTestException("Field at " + location + " must be a whole number, but found " + typeOf(value));
        }
        return number;
    }

    /**
     * Reads a list of non-blank strings, preserving order AND duplicates — unlike {@link #stringSet}, whose
     * deduplication would hide a repeated entry from the value type's own invariant check.
     */
    private static List<String> stringList(Object value, String location) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("Field at " + location + " must be a list of strings");
        }
        List<String> result = new ArrayList<>();
        for (Object item : new ArrayList<>(list)) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new StandTestException("List at " + location + " must contain only non-blank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static Set<String> stringSet(Object value, String location) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("Field at " + location + " must be a list of strings");
        }
        Set<String> result = new LinkedHashSet<>();
        List<Object> items = new ArrayList<>(list);
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new StandTestException("List at " + location + " must contain only non-blank strings");
            }
            result.add(text);
        }
        return result;
    }

    private static String typeOf(Object value) {
        return (value == null) ? "nothing" : value.getClass().getSimpleName();
    }
}
