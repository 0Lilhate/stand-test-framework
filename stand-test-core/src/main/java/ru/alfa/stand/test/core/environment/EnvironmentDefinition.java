package ru.alfa.stand.test.core.environment;

import java.util.Map;
import java.util.Optional;

/**
 * Definition of a single whitelisted environment: its logical services, topics, datasources, gRPC
 * targets and (optional) Kafka cluster. All maps are defensively copied and exposed as immutable.
 *
 * @param name the logical environment name (never blank)
 * @param services service endpoints keyed by alias
 * @param topics topics keyed by alias
 * @param datasources datasources keyed by alias
 * @param grpcTargets gRPC targets keyed by alias
 * @param kafkaCluster the Kafka cluster of this environment (may be null when no Kafka is configured)
 */
public record EnvironmentDefinition(
        String name,
        Map<String, ServiceEndpointDefinition> services,
        Map<String, TopicDefinition> topics,
        Map<String, DatasourceDefinition> datasources,
        Map<String, GrpcTargetDefinition> grpcTargets,
        KafkaClusterDefinition kafkaCluster) {

    public EnvironmentDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("environment name must not be blank");
        }
        services = (services == null) ? Map.of() : Map.copyOf(services);
        topics = (topics == null) ? Map.of() : Map.copyOf(topics);
        datasources = (datasources == null) ? Map.of() : Map.copyOf(datasources);
        grpcTargets = (grpcTargets == null) ? Map.of() : Map.copyOf(grpcTargets);
    }

    /**
     * Creates an environment with no Kafka cluster. Convenience for the (REST/DB/gRPC) callers that do
     * not configure Kafka, preserving the prior five-argument shape.
     *
     * @param name the logical environment name (never blank)
     * @param services service endpoints keyed by alias
     * @param topics topics keyed by alias
     * @param datasources datasources keyed by alias
     * @param grpcTargets gRPC targets keyed by alias
     */
    public EnvironmentDefinition(
            String name,
            Map<String, ServiceEndpointDefinition> services,
            Map<String, TopicDefinition> topics,
            Map<String, DatasourceDefinition> datasources,
            Map<String, GrpcTargetDefinition> grpcTargets) {
        this(name, services, topics, datasources, grpcTargets, null);
    }

    /**
     * Resolves a service endpoint by alias.
     *
     * @param alias the service alias
     * @return the service endpoint, or empty if not whitelisted
     */
    public Optional<ServiceEndpointDefinition> service(String alias) {
        return Optional.ofNullable(services.get(alias));
    }

    /**
     * Resolves a topic by alias.
     *
     * @param alias the topic alias
     * @return the topic, or empty if not whitelisted
     */
    public Optional<TopicDefinition> topic(String alias) {
        return Optional.ofNullable(topics.get(alias));
    }

    /**
     * Resolves a datasource by alias.
     *
     * @param alias the datasource alias
     * @return the datasource, or empty if not whitelisted
     */
    public Optional<DatasourceDefinition> datasource(String alias) {
        return Optional.ofNullable(datasources.get(alias));
    }

    /**
     * Resolves a gRPC target by alias.
     *
     * @param alias the gRPC target alias
     * @return the gRPC target, or empty if not whitelisted
     */
    public Optional<GrpcTargetDefinition> grpcTarget(String alias) {
        return Optional.ofNullable(grpcTargets.get(alias));
    }
}
