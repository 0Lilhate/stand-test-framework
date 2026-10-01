package ru.alfa.stand.test.starter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.alfa.stand.test.core.environment.AuthScheme;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;

/**
 * Bindable configuration for the stand-test SDK, rooted at {@code stand.test}.
 *
 * <p>Core value types are immutable {@code record}s whose compact constructors reject blank references,
 * so they cannot be bound by Spring's relaxed setter binding directly. This class therefore holds
 * <strong>mutable</strong> nested POJOs (plain getters/setters, relaxed kebab-case binding); the
 * immutable core records ({@link ru.alfa.stand.test.core.environment.EnvironmentDefinition} and friends)
 * are assembled from them by {@link EnvironmentRegistryFactory}. Endpoints and credentials may be bound
 * either as a {@code *Ref} name (resolved lazily from the OS environment by the adapters, never entering
 * the Spring Environment) or as a Spring-resolved <em>value</em> twin ({@code base-url}/{@code url}/
 * {@code target}/{@code bootstrap-servers}/{@code security-protocol}, and the credential twins {@code
 * user}/{@code password}/{@code username}/{@code token}/{@code sasl-jaas-config}). A value twin resolves
 * {@code ${VAR}} through the Spring Environment at startup, so a secret supplied that way materialises
 * there — prefer {@code ${ENV_VAR}} and never inline a literal secret in a value twin.
 */
@ConfigurationProperties(value = "stand.test", ignoreUnknownFields = false)
public class StandTestProperties {

    /**
     * Master switch for the whole auto-configuration. When {@code false}, the starter contributes no
     * beans (an {@code @Autowired StandClient} then requires manual wiring). Defaults to {@code true}.
     */
    private boolean enabled = true;

    /**
     * Declared <strong>format</strong> version of the environment registry (not the SDK version), mirroring
     * the root {@code version} key of {@code stand-test-environments.yml}. Absent means version 1, so every
     * configuration written before versioning existed keeps binding unchanged; a version newer than this
     * SDK reads is rejected with a message naming both versions. See
     * {@link ru.alfa.stand.test.core.environment.EnvironmentConfigFormat}.
     */
    private Integer version;

    private String defaultEnvironment;

    private final Await await = new Await();

    private final Reporting reporting = new Reporting();

    private final Map<String, Environment> environments = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getDefaultEnvironment() {
        return defaultEnvironment;
    }

    public void setDefaultEnvironment(String defaultEnvironment) {
        this.defaultEnvironment = defaultEnvironment;
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
     * A single whitelisted environment: its logical services, datasources, topics, gRPC targets, UI
     * applications and (optional) Kafka cluster, all keyed by alias.
     */
    public static class Environment {

        private final Map<String, Service> services = new LinkedHashMap<>();

        private final Map<String, Datasource> datasources = new LinkedHashMap<>();

        private final Map<String, Topic> topics = new LinkedHashMap<>();

        private final Map<String, GrpcTarget> grpcTargets = new LinkedHashMap<>();

        private final Map<String, KafkaCluster> kafkaClusters = new LinkedHashMap<>();

        private final Map<String, UiApplication> uiApplications = new LinkedHashMap<>();

        private final Map<String, Map<String, Object>> eqBackends = new LinkedHashMap<>();

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

        public Map<String, KafkaCluster> getKafkaClusters() {
            return kafkaClusters;
        }

        public Map<String, UiApplication> getUiApplications() {
            return uiApplications;
        }

        public Map<String, Map<String, Object>> getEqBackends() {
            return eqBackends;
        }
    }

    /**
     * A logical UI application: the alias a scenario addresses instead of a URL. {@code baseUrlRef} is a
     * reference (an env-var name), {@code baseUrl} its Spring-resolved value twin — mutually exclusive,
     * exactly as for a {@link Service}. Everything about how the run is performed (viewport, trace, sign-in)
     * lives here rather than in the scenario, so the core scenario model stays free of browser fields.
     *
     * <p>Requires {@code stand.test.version: 2} or higher — the format version in which this section
     * arrived.
     */
    public static class UiApplication {

        private String baseUrl;

        private String baseUrlRef;

        private String defaultViewport;

        private final Map<String, Viewport> viewportProfiles = new LinkedHashMap<>();

        /**
         * Whether a browser trace may be recorded: {@code off} (default) or {@code on-failure}. Bound as a
         * string rather than the enum because YAML resolves an unquoted {@code off} to the boolean
         * {@code false}; the shared core parser turns both spellings into the same mode.
         */
        private String trace;

        private UiAuth auth;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getBaseUrlRef() {
            return baseUrlRef;
        }

        public void setBaseUrlRef(String baseUrlRef) {
            this.baseUrlRef = baseUrlRef;
        }

        public String getDefaultViewport() {
            return defaultViewport;
        }

        public void setDefaultViewport(String defaultViewport) {
            this.defaultViewport = defaultViewport;
        }

        public Map<String, Viewport> getViewportProfiles() {
            return viewportProfiles;
        }

        public String getTrace() {
            return trace;
        }

        public void setTrace(String trace) {
            this.trace = trace;
        }

        public UiAuth getAuth() {
            return auth;
        }

        public void setAuth(UiAuth auth) {
            this.auth = auth;
        }
    }

    /**
     * A named viewport size of a {@link UiApplication}, in CSS pixels.
     */
    public static class Viewport {

        private int width;

        private int height;

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }
    }

    /**
     * Sign-in configuration of a {@link UiApplication}. The scheme is spelled {@code scheme} — the same key
     * a service's {@link Auth} uses.
     *
     * <p>The account roster ({@code credentialsPoolRef}) and the discovery account
     * ({@code discoveryAccountRef}) are references and only references: there is deliberately no value twin
     * for either, because a roster routed through the Spring {@code Environment} would sit there for the
     * life of the context. There is no {@code credentialsPool} / {@code discoveryAccount} setter, so the
     * value spelling binds nothing rather than binding something weaker.
     *
     * <p>{@code credentialsUsername}/{@code credentialsPassword} name one account directly, for an
     * application that has exactly one; they exclude {@code credentialsPoolRef}. <strong>What that pair
     * means depends on the declared format version</strong>, and the rule lives in one place —
     * {@code EnvironmentRegistryFactory.uiCredential}, which the file front-end mirrors exactly:
     *
     * <ul>
     *   <li><b>Version 4:</b> the bare field is a REFERENCE — the name of an environment variable — and
     *       {@code *Ref} does not exist yet. Such a document keeps that meaning forever.</li>
     *   <li><b>From version 5:</b> the bare field is the VALUE, resolved by Spring like {@code baseUrl} and
     *       every other twin, while {@code credentialsUsernameRef}/{@code credentialsPasswordRef} carry the
     *       reference. This is what makes {@code credentials-username: ${web_username:tks_Admin}} work here
     *       at all: Spring expands the placeholder before the SDK sees the field, so a value and a variable
     *       name arrive as the same string and cannot share one key.</li>
     * </ul>
     *
     * <p>The placeholder trap therefore applies to every {@code *Ref} field on this surface, this pair
     * included: <strong>a {@code *Ref} takes a bare variable NAME, never {@code ${VAR:default}}</strong>.
     * Spring expands the placeholder at context startup, and the SDK then reads the expanded text as the
     * name of a variable that does not exist — a rejected sign-in that looks like a wrong password. The
     * {@code ${VAR:value}} spelling belongs in the value field here, and in either field of
     * {@code stand-test-environments.yml}, whose loader expands placeholders itself.
     */
    public static class UiAuth {

        private UiAuthScheme scheme;

        private String credentialsPoolRef;

        private String credentialsUsername;

        private String credentialsPassword;

        private String credentialsUsernameRef;

        private String credentialsPasswordRef;

        private final List<String> roles = new ArrayList<>();

        private String discoveryAccountRef;

        private UiLogin login;

        private UiLoginChallenge challenge;

        public UiAuthScheme getScheme() {
            return scheme;
        }

        public void setScheme(UiAuthScheme scheme) {
            this.scheme = scheme;
        }

        public String getCredentialsPoolRef() {
            return credentialsPoolRef;
        }

        public void setCredentialsPoolRef(String credentialsPoolRef) {
            this.credentialsPoolRef = credentialsPoolRef;
        }

        public String getCredentialsUsername() {
            return credentialsUsername;
        }

        public void setCredentialsUsername(String credentialsUsername) {
            this.credentialsUsername = credentialsUsername;
        }

        public String getCredentialsPassword() {
            return credentialsPassword;
        }

        public void setCredentialsPassword(String credentialsPassword) {
            this.credentialsPassword = credentialsPassword;
        }

        /**
         * The NAME of the environment variable holding the login, when the value must not pass through the
         * Spring Environment. From format version 5 this is the reference spelling; the bare
         * {@code credentials-username} beside it is the value itself.
         */
        public String getCredentialsUsernameRef() {
            return credentialsUsernameRef;
        }

        public void setCredentialsUsernameRef(String credentialsUsernameRef) {
            this.credentialsUsernameRef = credentialsUsernameRef;
        }

        /**
         * The NAME of the environment variable holding the password. Preferred over the value twin for a
         * secret: a resolved value materialises in the Spring Environment, which actuator, a heap dump and
         * a context report can all show.
         */
        public String getCredentialsPasswordRef() {
            return credentialsPasswordRef;
        }

        public void setCredentialsPasswordRef(String credentialsPasswordRef) {
            this.credentialsPasswordRef = credentialsPasswordRef;
        }

        public List<String> getRoles() {
            return roles;
        }

        public String getDiscoveryAccountRef() {
            return discoveryAccountRef;
        }

        public void setDiscoveryAccountRef(String discoveryAccountRef) {
            this.discoveryAccountRef = discoveryAccountRef;
        }

        public UiLogin getLogin() {
            return login;
        }

        public void setLogin(UiLogin login) {
            this.login = login;
        }

        public UiLoginChallenge getChallenge() {
            return challenge;
        }

        public void setChallenge(UiLoginChallenge challenge) {
            this.challenge = challenge;
        }
    }

    /**
     * The sign-in form of a {@link UiApplication}: where it is and which elements it consists of.
     *
     * <p>Every value is a locator <em>expression</em> in the UI adapter's grammar ({@code testId=…},
     * {@code role=button:Sign in}, {@code label=…}, {@code text=…}, {@code css=…}) — not a reference and not
     * a credential. {@code signedIn} is the element present only once signed in; it is what tells a
     * completed sign-in from rejected credentials, and a live reused session from an expired one.
     */
    public static class UiLogin {

        private String path;

        private String usernameLocator;

        private String passwordLocator;

        private String submitLocator;

        private String signedInLocator;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getUsernameLocator() {
            return usernameLocator;
        }

        public void setUsernameLocator(String usernameLocator) {
            this.usernameLocator = usernameLocator;
        }

        public String getPasswordLocator() {
            return passwordLocator;
        }

        public void setPasswordLocator(String passwordLocator) {
            this.passwordLocator = passwordLocator;
        }

        public String getSubmitLocator() {
            return submitLocator;
        }

        public void setSubmitLocator(String submitLocator) {
            this.submitLocator = submitLocator;
        }

        public String getSignedInLocator() {
            return signedInLocator;
        }

        public void setSignedInLocator(String signedInLocator) {
            this.signedInLocator = signedInLocator;
        }
    }

    /**
     * A logical REST/HTTP service endpoint. {@code baseUrlRef} is a reference (an env-var name), never a
     * URL value. {@code baseUrl} is its value twin: resolved by Spring at context startup (real
     * {@code ${VAR:}} placeholders), mutually exclusive with {@code baseUrlRef}.
     */
    public static class Service {

        private String baseUrl;

        private String baseUrlRef;

        private Correlation correlation;

        private Auth auth;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

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

        public Auth getAuth() {
            return auth;
        }

        public void setAuth(Auth auth) {
            this.auth = auth;
        }
    }

    /**
     * A logical datasource. {@code urlRef}/{@code userRef}/{@code passwordRef} are secret references
     * (env-var names, resolved lazily by the SDK); writes are opt-in ({@code writeAllowed}) and
     * confined to {@code allowedSchemas}. {@code url}/{@code user}/{@code password} are the value
     * twins: resolved by Spring at context startup (real {@code ${VAR}} placeholders), each mutually
     * exclusive with its {@code *-ref}. Note: a credential supplied through {@code user}/{@code
     * password} DOES materialise in the Spring Environment — prefer {@code ${ENV_VAR}} there and
     * never inline a literal secret; keep the {@code *-ref} spelling when a secret must never enter
     * the Environment.
     */
    public static class Datasource {

        private String url;

        private String urlRef;

        private String user;

        private String userRef;

        private String password;

        private String passwordRef;

        private final List<String> allowedSchemas = new ArrayList<>();

        private boolean writeAllowed;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUrlRef() {
            return urlRef;
        }

        public void setUrlRef(String urlRef) {
            this.urlRef = urlRef;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getUserRef() {
            return userRef;
        }

        public void setUserRef(String userRef) {
            this.userRef = userRef;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
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

        private String cluster;

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

        public String getCluster() {
            return cluster;
        }

        public void setCluster(String cluster) {
            this.cluster = cluster;
        }
    }

    /**
     * A logical gRPC target. {@code targetRef} is a reference (an env-var name), never a {@code host:port}
     * value. {@code target} is its value twin: resolved by Spring at context startup, mutually exclusive
     * with {@code targetRef}.
     */
    public static class GrpcTarget {

        private String target;

        private String targetRef;

        private Correlation correlation;

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

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
     * {@code bootstrapServers}/{@code securityProtocol}/{@code saslJaasConfig} are value twins resolved
     * by Spring at context startup, each mutually exclusive with its {@code *-ref} twin (idiom:
     * {@code security-protocol: ${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}}). Note: a {@code saslJaasConfig}
     * value materialises in the Spring Environment — prefer {@code ${ENV_VAR}} and never inline a
     * literal secret; keep {@code saslJaasConfigRef} when a secret must never enter the Environment.
     */
    public static class KafkaCluster {

        private String bootstrapServers;

        private String bootstrapServersRef;

        private String securityProtocol;

        private String securityProtocolRef;

        private String saslJaasConfig;

        private String saslJaasConfigRef;

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getBootstrapServersRef() {
            return bootstrapServersRef;
        }

        public void setBootstrapServersRef(String bootstrapServersRef) {
            this.bootstrapServersRef = bootstrapServersRef;
        }

        public String getSecurityProtocol() {
            return securityProtocol;
        }

        public void setSecurityProtocol(String securityProtocol) {
            this.securityProtocol = securityProtocol;
        }

        public String getSecurityProtocolRef() {
            return securityProtocolRef;
        }

        public void setSecurityProtocolRef(String securityProtocolRef) {
            this.securityProtocolRef = securityProtocolRef;
        }

        public String getSaslJaasConfig() {
            return saslJaasConfig;
        }

        public void setSaslJaasConfig(String saslJaasConfig) {
            this.saslJaasConfig = saslJaasConfig;
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

    /**
     * Service-level authentication: an {@link AuthScheme} plus credentials —
     * {@code usernameRef}/{@code passwordRef} for BASIC, {@code tokenRef} for BEARER. The adapter
     * resolves them and injects the {@code Authorization} header at execution time. {@code username}/
     * {@code password}/{@code token} are the value twins resolved by Spring at context startup, each
     * mutually exclusive with its {@code *-ref}. Note: a credential supplied through a value twin
     * materialises in the Spring Environment — prefer {@code ${ENV_VAR}} and never inline a literal
     * secret; keep the {@code *-ref} spelling when a secret must never enter the Environment.
     */
    public static class Auth {

        private AuthScheme scheme;

        private String username;

        private String usernameRef;

        private String password;

        private String passwordRef;

        private String token;

        private String tokenRef;

        public AuthScheme getScheme() {
            return scheme;
        }

        public void setScheme(AuthScheme scheme) {
            this.scheme = scheme;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getUsernameRef() {
            return usernameRef;
        }

        public void setUsernameRef(String usernameRef) {
            this.usernameRef = usernameRef;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPasswordRef() {
            return passwordRef;
        }

        public void setPasswordRef(String passwordRef) {
            this.passwordRef = passwordRef;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTokenRef() {
            return tokenRef;
        }

        public void setTokenRef(String tokenRef) {
            this.tokenRef = tokenRef;
        }
    }
}
