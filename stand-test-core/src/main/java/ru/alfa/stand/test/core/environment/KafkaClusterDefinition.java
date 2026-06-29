package ru.alfa.stand.test.core.environment;

import java.util.Optional;

/**
 * Definition of the Kafka cluster of an environment (plan §9).
 *
 * <p>One cluster per environment; topics ({@link TopicDefinition}) are separate, many per cluster. The
 * broker address and credentials are stored as <em>references</em> (for example environment-variable
 * names), never values — mirroring {@code ServiceEndpointDefinition.baseUrlRef()} for REST. The adapter
 * resolves a reference to its value at run time, so bootstrap servers and secrets stay out of source
 * (plan §9/§20). {@code securityProtocolRef} and {@code saslJaasConfigRef} are optional and only needed
 * for SASL/SSL stands.
 *
 * @param bootstrapServersRef a reference resolving to the bootstrap servers (never blank)
 * @param securityProtocolRef a reference resolving to the security protocol (may be null/blank)
 * @param saslJaasConfigRef a reference resolving to the SASL JAAS config (may be null/blank)
 */
public record KafkaClusterDefinition(
        String bootstrapServersRef,
        String securityProtocolRef,
        String saslJaasConfigRef) {

    public KafkaClusterDefinition {
        if (bootstrapServersRef == null || bootstrapServersRef.isBlank()) {
            throw new IllegalArgumentException("bootstrapServersRef must not be blank");
        }
    }

    /**
     * Creates a cluster definition with only the bootstrap-servers reference (no security).
     *
     * @param bootstrapServersRef a reference resolving to the bootstrap servers (never blank)
     * @return a new cluster definition
     */
    public static KafkaClusterDefinition of(String bootstrapServersRef) {
        return new KafkaClusterDefinition(bootstrapServersRef, null, null);
    }

    /**
     * Returns the security-protocol reference, if configured.
     *
     * @return the security-protocol reference, or empty when not set
     */
    public Optional<String> securityProtocolReference() {
        return blankToEmpty(securityProtocolRef);
    }

    /**
     * Returns the SASL JAAS config reference, if configured.
     *
     * @return the SASL JAAS config reference, or empty when not set
     */
    public Optional<String> saslJaasConfigReference() {
        return blankToEmpty(saslJaasConfigRef);
    }

    private static Optional<String> blankToEmpty(String reference) {
        return (reference == null || reference.isBlank()) ? Optional.empty() : Optional.of(reference);
    }
}
