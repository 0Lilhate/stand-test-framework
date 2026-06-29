package ru.alfa.stand.test.kafka;

/**
 * A Kafka cluster with its references already resolved to values, handed to a
 * {@link KafkaClientFactory}.
 *
 * <p>The {@code bootstrapServers} are resolved from the cluster's {@code bootstrapServersRef}; the
 * optional {@code securityProtocol}/{@code saslJaasConfig} are resolved from their references when set
 * (null when the stand needs no SASL/SSL). Keeping resolution in the executor (and values only here)
 * mirrors how the REST adapter resolves {@code baseUrlRef} before calling its transport, so secrets and
 * broker addresses never live in source (plan §9).
 *
 * @param bootstrapServers the resolved bootstrap servers (never blank)
 * @param securityProtocol the resolved security protocol, or null when not configured
 * @param saslJaasConfig the resolved SASL JAAS config, or null when not configured
 */
public record ResolvedKafkaCluster(String bootstrapServers, String securityProtocol, String saslJaasConfig) {

    public ResolvedKafkaCluster {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
    }
}
