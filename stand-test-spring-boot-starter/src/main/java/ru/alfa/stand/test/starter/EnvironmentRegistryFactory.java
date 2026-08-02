package ru.alfa.stand.test.starter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
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
        EnvironmentConfigFormat.requireSupported(properties.getVersion(), "stand.test");
        Map<String, EnvironmentDefinition> environments = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Environment> entry : properties.getEnvironments().entrySet()) {
            String name = entry.getKey();
            environments.put(name, toEnvironment(name, entry.getValue()));
        }
        return new InMemoryEnvironmentRegistry(environments);
    }

    private static EnvironmentDefinition toEnvironment(String name, StandTestProperties.Environment env) {
        try {
            return new EnvironmentDefinition(
                    name,
                    services(env),
                    topics(env),
                    datasources(env),
                    grpcTargets(env),
                    kafkaCluster(env.getKafkaCluster()),
                    kafkaClusters(env));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Invalid stand.test.environments." + name + " configuration: " + invalid.getMessage(), invalid);
        }
    }

    private static Map<String, ServiceEndpointDefinition> services(StandTestProperties.Environment env) {
        Map<String, ServiceEndpointDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Service> entry : env.getServices().entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.Service service = entry.getValue();
            result.put(alias, new ServiceEndpointDefinition(alias, refOrLiteral(service.getBaseUrl(), service.getBaseUrlRef(), "base-url", "base-url-ref", alias), correlation(service.getCorrelation()), auth(service.getAuth(), alias)));
        }
        return result;
    }

    private static AuthConfig auth(StandTestProperties.Auth auth, String alias) {
        if (auth == null) {
            return null;
        }
        // A missing scheme must surface as the environment-labelled IllegalStateException like every
        // other misconfiguration, so it is rejected here as IllegalArgumentException rather than
        // letting the core constructor's NullPointerException escape the toEnvironment wrapper.
        if (auth.getScheme() == null) {
            throw new IllegalArgumentException("service '" + alias + "' auth.scheme must not be null");
        }
        return new AuthConfig(
                auth.getScheme(),
                refOrLiteral(auth.getUsername(), auth.getUsernameRef(), "username", "username-ref", alias),
                refOrLiteral(auth.getPassword(), auth.getPasswordRef(), "password", "password-ref", alias),
                refOrLiteral(auth.getToken(), auth.getTokenRef(), "token", "token-ref", alias));
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
            result.put(alias, new GrpcTargetDefinition(alias, refOrLiteral(target.getTarget(), target.getTargetRef(), "target", "target-ref", alias), correlation(target.getCorrelation())));
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
                refOrLiteral(cluster.getBootstrapServers(), cluster.getBootstrapServersRef(), "bootstrap-servers", "bootstrap-servers-ref", "kafka-cluster"),
                refOrLiteral(cluster.getSecurityProtocol(), cluster.getSecurityProtocolRef(), "security-protocol", "security-protocol-ref", "kafka-cluster"),
                refOrLiteral(cluster.getSaslJaasConfig(), cluster.getSaslJaasConfigRef(), "sasl-jaas-config", "sasl-jaas-config-ref", "kafka-cluster"));
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
