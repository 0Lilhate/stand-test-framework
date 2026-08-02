package ru.alfa.stand.test.core.environment;

import java.util.Map;
import java.util.Optional;

/**
 * Definition of a single whitelisted environment: its logical services, topics, datasources, gRPC
 * targets, Kafka clusters and UI applications. All maps are defensively copied and exposed as immutable.
 *
 * <p>Kafka clusters come in two shapes that may be combined: the single {@code kafkaCluster} is the
 * environment's <em>default</em> cluster (used by every topic that names no cluster), and
 * {@code kafkaClusters} whitelists additional <em>named</em> clusters that a {@link TopicDefinition}
 * selects via its {@code cluster} alias. A topic naming a cluster absent from {@code kafkaClusters}
 * is rejected at construction — the whitelist stays closed.
 *
 * <p>{@code uiApplications} is optional and empty by default, so every constructor that predates it keeps
 * working unchanged; an environment that configures no UI simply has none. It carries the same meaning as
 * the other maps: an alias is addressable exactly because the registry lists it.
 *
 * @param name the logical environment name (never blank)
 * @param services service endpoints keyed by alias
 * @param topics topics keyed by alias
 * @param datasources datasources keyed by alias
 * @param grpcTargets gRPC targets keyed by alias
 * @param kafkaCluster the DEFAULT Kafka cluster (may be null when no Kafka is configured)
 * @param kafkaClusters additional named Kafka clusters keyed by cluster alias
 * @param uiApplications UI applications keyed by alias
 */
public record EnvironmentDefinition(
        String name,
        Map<String, ServiceEndpointDefinition> services,
        Map<String, TopicDefinition> topics,
        Map<String, DatasourceDefinition> datasources,
        Map<String, GrpcTargetDefinition> grpcTargets,
        KafkaClusterDefinition kafkaCluster,
        Map<String, KafkaClusterDefinition> kafkaClusters,
        Map<String, UiApplicationDefinition> uiApplications) {

    public EnvironmentDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("environment name must not be blank");
        }
        services = (services == null) ? Map.of() : Map.copyOf(services);
        topics = (topics == null) ? Map.of() : Map.copyOf(topics);
        datasources = (datasources == null) ? Map.of() : Map.copyOf(datasources);
        grpcTargets = (grpcTargets == null) ? Map.of() : Map.copyOf(grpcTargets);
        kafkaClusters = (kafkaClusters == null) ? Map.of() : Map.copyOf(kafkaClusters);
        uiApplications = (uiApplications == null) ? Map.of() : Map.copyOf(uiApplications);
        for (TopicDefinition topic : topics.values()) {
            if (topic.cluster() != null && !kafkaClusters.containsKey(topic.cluster())) {
                throw new IllegalArgumentException("topic '" + topic.alias() + "' names Kafka cluster '" + topic.cluster()
                        + "', which is not declared in kafka-clusters of environment '" + name + "'");
            }
        }
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
        this(name, services, topics, datasources, grpcTargets, null, Map.of(), Map.of());
    }

    /**
     * Creates an environment with a single (default) Kafka cluster and no named clusters, preserving
     * the prior six-argument shape.
     *
     * @param name the logical environment name (never blank)
     * @param services service endpoints keyed by alias
     * @param topics topics keyed by alias
     * @param datasources datasources keyed by alias
     * @param grpcTargets gRPC targets keyed by alias
     * @param kafkaCluster the default Kafka cluster (may be null)
     */
    public EnvironmentDefinition(
            String name,
            Map<String, ServiceEndpointDefinition> services,
            Map<String, TopicDefinition> topics,
            Map<String, DatasourceDefinition> datasources,
            Map<String, GrpcTargetDefinition> grpcTargets,
            KafkaClusterDefinition kafkaCluster) {
        this(name, services, topics, datasources, grpcTargets, kafkaCluster, Map.of(), Map.of());
    }

    /**
     * Creates an environment with no UI applications, preserving the prior seven-argument shape.
     *
     * @param name the logical environment name (never blank)
     * @param services service endpoints keyed by alias
     * @param topics topics keyed by alias
     * @param datasources datasources keyed by alias
     * @param grpcTargets gRPC targets keyed by alias
     * @param kafkaCluster the default Kafka cluster (may be null)
     * @param kafkaClusters additional named Kafka clusters keyed by cluster alias
     */
    public EnvironmentDefinition(
            String name,
            Map<String, ServiceEndpointDefinition> services,
            Map<String, TopicDefinition> topics,
            Map<String, DatasourceDefinition> datasources,
            Map<String, GrpcTargetDefinition> grpcTargets,
            KafkaClusterDefinition kafkaCluster,
            Map<String, KafkaClusterDefinition> kafkaClusters) {
        this(name, services, topics, datasources, grpcTargets, kafkaCluster, kafkaClusters, Map.of());
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

    /**
     * Resolves a named Kafka cluster by alias (the default {@link #kafkaCluster()} is not part of this
     * lookup — it applies to topics that name no cluster).
     *
     * @param alias the cluster alias
     * @return the named cluster, or empty if not whitelisted
     */
    public Optional<KafkaClusterDefinition> kafkaCluster(String alias) {
        return Optional.ofNullable(kafkaClusters.get(alias));
    }

    /**
     * Resolves a UI application by alias.
     *
     * @param alias the UI application alias
     * @return the UI application, or empty if not whitelisted
     */
    public Optional<UiApplicationDefinition> uiApplication(String alias) {
        return Optional.ofNullable(uiApplications.get(alias));
    }
}
