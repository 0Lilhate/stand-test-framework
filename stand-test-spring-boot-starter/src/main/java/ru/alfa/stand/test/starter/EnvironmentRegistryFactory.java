package ru.alfa.stand.test.starter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.GrpcTargetDefinition;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
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
                    kafkaCluster(env.getKafkaCluster()));
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
            result.put(alias, new ServiceEndpointDefinition(alias, service.getBaseUrlRef(), correlation(service.getCorrelation())));
        }
        return result;
    }

    private static Map<String, TopicDefinition> topics(StandTestProperties.Environment env) {
        Map<String, TopicDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, StandTestProperties.Topic> entry : env.getTopics().entrySet()) {
            String alias = entry.getKey();
            StandTestProperties.Topic topic = entry.getValue();
            result.put(alias, new TopicDefinition(alias, topic.getName(), correlation(topic.getCorrelation())));
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
                    ds.getUrlRef(),
                    ds.getUserRef(),
                    ds.getPasswordRef(),
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
            result.put(alias, new GrpcTargetDefinition(alias, target.getTargetRef(), correlation(target.getCorrelation())));
        }
        return result;
    }

    private static KafkaClusterDefinition kafkaCluster(StandTestProperties.KafkaCluster cluster) {
        if (cluster == null) {
            return null;
        }
        return new KafkaClusterDefinition(
                cluster.getBootstrapServersRef(),
                cluster.getSecurityProtocolRef(),
                cluster.getSaslJaasConfigRef());
    }

    private static CorrelationConfig correlation(StandTestProperties.Correlation correlation) {
        if (correlation == null) {
            return null;
        }
        return new CorrelationConfig(correlation.getSource(), correlation.getName());
    }
}
