package ru.alfa.stand.test.starter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.alfa.stand.test.core.environment.CorrelationSource;

/**
 * Bindable configuration for the stand-test SDK, rooted at {@code stand.test}.
 *
 * <p>Core value types are immutable {@code record}s whose compact constructors reject blank references,
 * so they cannot be bound by Spring's relaxed setter binding directly. This class therefore holds
 * <strong>mutable</strong> nested POJOs (plain getters/setters, relaxed kebab-case binding); the
 * immutable core records ({@link ru.alfa.stand.test.core.environment.EnvironmentDefinition} and friends)
 * are assembled from them by {@link EnvironmentRegistryFactory}. No endpoint, secret or credential
 * <em>value</em> is ever bound here — only {@code *Ref} names that the adapters resolve at run time.
 */
@ConfigurationProperties(value = "stand.test", ignoreUnknownFields = false)
public class StandTestProperties {

    /**
     * Master switch for the whole auto-configuration. When {@code false}, the starter contributes no
     * beans (an {@code @Autowired StandClient} then requires manual wiring). Defaults to {@code true}.
     */
    private boolean enabled = true;

    private final Await await = new Await();

    private final Reporting reporting = new Reporting();

    private final Map<String, Environment> environments = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Await getAwait() {
        return await;
    }

    public Reporting getReporting() {
        return reporting;
    }

    public Map<String, Environment> getEnvironments() {
        return environments;
    }

    /**
     * Defaults for the SDK's single await mechanism, exposed as a reusable {@code AwaitPolicy} bean.
     */
    public static class Await {

        private Duration timeout = Duration.ofSeconds(30);

        private Duration pollInterval = Duration.ofMillis(500);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }
    }

    /**
     * Reporting toggles. Reporting itself is a best-effort side-channel; disabling the global switch (or
     * just Allure) contributes no reporting publisher and falls back to the no-op publisher.
     */
    public static class Reporting {

        private boolean enabled = true;

        private final Allure allure = new Allure();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Allure getAllure() {
            return allure;
        }
    }

    /**
     * Allure reporting toggle. Only honoured when {@code stand-test-allure} is on the classpath.
     */
    public static class Allure {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * A single whitelisted environment: its logical services, datasources, topics, gRPC targets and
     * (optional) Kafka cluster, all keyed by alias.
     */
    public static class Environment {

        private final Map<String, Service> services = new LinkedHashMap<>();

        private final Map<String, Datasource> datasources = new LinkedHashMap<>();

        private final Map<String, Topic> topics = new LinkedHashMap<>();

        private final Map<String, GrpcTarget> grpcTargets = new LinkedHashMap<>();

        private KafkaCluster kafkaCluster;

        public Map<String, Service> getServices() {
            return services;
        }

        public Map<String, Datasource> getDatasources() {
            return datasources;
        }

        public Map<String, Topic> getTopics() {
            return topics;
        }

        public Map<String, GrpcTarget> getGrpcTargets() {
            return grpcTargets;
        }

        public KafkaCluster getKafkaCluster() {
            return kafkaCluster;
        }

        public void setKafkaCluster(KafkaCluster kafkaCluster) {
            this.kafkaCluster = kafkaCluster;
        }
    }

    /**
     * A logical REST/HTTP service endpoint. {@code baseUrlRef} is a reference (an env-var name), never a
     * URL value.
     */
    public static class Service {

        private String baseUrlRef;

        private Correlation correlation;

        public String getBaseUrlRef() {
            return baseUrlRef;
        }

        public void setBaseUrlRef(String baseUrlRef) {
            this.baseUrlRef = baseUrlRef;
        }

        public Correlation getCorrelation() {
            return correlation;
        }

        public void setCorrelation(Correlation correlation) {
            this.correlation = correlation;
        }
    }

    /**
     * A logical datasource. {@code urlRef}/{@code userRef}/{@code passwordRef} are secret references,
     * never values; writes are opt-in ({@code writeAllowed}) and confined to {@code allowedSchemas}.
     */
    public static class Datasource {

        private String urlRef;

        private String userRef;

        private String passwordRef;

        private final List<String> allowedSchemas = new ArrayList<>();

        private boolean writeAllowed;

        public String getUrlRef() {
            return urlRef;
        }

        public void setUrlRef(String urlRef) {
            this.urlRef = urlRef;
        }

        public String getUserRef() {
            return userRef;
        }

        public void setUserRef(String userRef) {
            this.userRef = userRef;
        }

        public String getPasswordRef() {
            return passwordRef;
        }

        public void setPasswordRef(String passwordRef) {
            this.passwordRef = passwordRef;
        }

        public List<String> getAllowedSchemas() {
            return allowedSchemas;
        }

        public boolean isWriteAllowed() {
            return writeAllowed;
        }

        public void setWriteAllowed(boolean writeAllowed) {
            this.writeAllowed = writeAllowed;
        }
    }

    /**
     * A logical Kafka topic: its alias maps to a concrete {@code name} for the environment.
     */
    public static class Topic {

        private String name;

        private Correlation correlation;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Correlation getCorrelation() {
            return correlation;
        }

        public void setCorrelation(Correlation correlation) {
            this.correlation = correlation;
        }
    }

    /**
     * A logical gRPC target. {@code targetRef} is a reference (an env-var name), never a {@code host:port}
     * value.
     */
    public static class GrpcTarget {

        private String targetRef;

        private Correlation correlation;

        public String getTargetRef() {
            return targetRef;
        }

        public void setTargetRef(String targetRef) {
            this.targetRef = targetRef;
        }

        public Correlation getCorrelation() {
            return correlation;
        }

        public void setCorrelation(Correlation correlation) {
            this.correlation = correlation;
        }
    }

    /**
     * The Kafka cluster of an environment. Broker address and credentials are references, never values;
     * {@code securityProtocolRef}/{@code saslJaasConfigRef} are optional (SASL/SSL stands only).
     */
    public static class KafkaCluster {

        private String bootstrapServersRef;

        private String securityProtocolRef;

        private String saslJaasConfigRef;

        public String getBootstrapServersRef() {
            return bootstrapServersRef;
        }

        public void setBootstrapServersRef(String bootstrapServersRef) {
            this.bootstrapServersRef = bootstrapServersRef;
        }

        public String getSecurityProtocolRef() {
            return securityProtocolRef;
        }

        public void setSecurityProtocolRef(String securityProtocolRef) {
            this.securityProtocolRef = securityProtocolRef;
        }

        public String getSaslJaasConfigRef() {
            return saslJaasConfigRef;
        }

        public void setSaslJaasConfigRef(String saslJaasConfigRef) {
            this.saslJaasConfigRef = saslJaasConfigRef;
        }
    }

    /**
     * How the SDK-owned correlation id is carried for a transport: a {@link CorrelationSource} plus the
     * carrier {@code name} (for example the header name {@code X-Correlation-Id}).
     */
    public static class Correlation {

        private CorrelationSource source;

        private String name;

        public CorrelationSource getSource() {
            return source;
        }

        public void setSource(CorrelationSource source) {
            this.source = source;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
