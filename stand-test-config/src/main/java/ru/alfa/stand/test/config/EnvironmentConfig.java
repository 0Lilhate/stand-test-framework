package ru.alfa.stand.test.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.GrpcTargetDefinition;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
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
 */
public final class EnvironmentConfig {

    private static final Set<String> ROOT_KEYS = Set.of("environments");
    private static final Set<String> ENV_KEYS = Set.of("services", "topics", "datasources", "grpc-targets", "grpcTargets", "kafka-cluster", "kafkaCluster");
    private static final Set<String> SERVICE_KEYS = Set.of("base-url-ref", "baseUrlRef", "correlation");
    private static final Set<String> TOPIC_KEYS = Set.of("name", "correlation");
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
        Map<String, Object> environments = namedMap(document.get("environments"), "environments");
        Map<String, EnvironmentDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : environments.entrySet()) {
            result.put(entry.getKey(), environment(entry.getKey(), entry.getValue()));
        }
        return new InMemoryEnvironmentRegistry(result);
    }

    private static EnvironmentDefinition environment(String name, Object value) {
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
        return build(location, () -> new EnvironmentDefinition(name, services, topics, datasources, grpcTargets, kafkaCluster));
    }

    private static ServiceEndpointDefinition service(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, SERVICE_KEYS, location);
        String baseUrlRef = requireString(fields, "base-url-ref", "baseUrlRef", location);
        CorrelationConfig correlation = correlation(fields.get("correlation"), location + ".correlation");
        return build(location, () -> new ServiceEndpointDefinition(alias, baseUrlRef, correlation));
    }

    private static TopicDefinition topic(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, TOPIC_KEYS, location);
        String name = requireString(fields, "name", "name", location);
        CorrelationConfig correlation = correlation(fields.get("correlation"), location + ".correlation");
        return build(location, () -> new TopicDefinition(alias, name, correlation));
    }

    private static DatasourceDefinition datasource(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, DATASOURCE_KEYS, location);
        String urlRef = requireString(fields, "url-ref", "urlRef", location);
        String userRef = requireString(fields, "user-ref", "userRef", location);
        String passwordRef = requireString(fields, "password-ref", "passwordRef", location);
        Set<String> allowedSchemas = stringSet(pick(fields, "allowed-schemas", "allowedSchemas"), location + ".allowed-schemas");
        boolean writeAllowed = boolFlag(pick(fields, "write-allowed", "writeAllowed"), location + ".write-allowed");
        return build(location, () -> new DatasourceDefinition(alias, urlRef, userRef, passwordRef, allowedSchemas, writeAllowed));
    }

    private static GrpcTargetDefinition grpcTarget(String alias, Object value, String location) {
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, GRPC_KEYS, location);
        String targetRef = requireString(fields, "target-ref", "targetRef", location);
        CorrelationConfig correlation = correlation(fields.get("correlation"), location + ".correlation");
        return build(location, () -> new GrpcTargetDefinition(alias, targetRef, correlation));
    }

    private static KafkaClusterDefinition kafkaCluster(Object value, String location) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = asMap(value, location);
        checkKnownKeys(fields, KAFKA_KEYS, location);
        String bootstrapServersRef = requireString(fields, "bootstrap-servers-ref", "bootstrapServersRef", location);
        String securityProtocolRef = optionalString(pick(fields, "security-protocol-ref", "securityProtocolRef"), location);
        String saslJaasConfigRef = optionalString(pick(fields, "sasl-jaas-config-ref", "saslJaasConfigRef"), location);
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
