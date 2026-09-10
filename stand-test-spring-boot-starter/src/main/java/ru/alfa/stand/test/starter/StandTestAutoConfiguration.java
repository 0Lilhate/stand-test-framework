package ru.alfa.stand.test.starter;

import java.util.List;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.DefaultStandClient;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.core.validation.ScenarioValidator;
import ru.alfa.stand.test.db.DbStepExecutor;
import ru.alfa.stand.test.grpc.GrpcStepExecutor;
import ru.alfa.stand.test.kafka.KafkaStepExecutor;
import ru.alfa.stand.test.rest.RestStepExecutor;

/**
 * Spring Boot auto-configuration that assembles the stand-test SDK from beans instead of the
 * {@code ServiceLoader} used by {@code StandTestExtension}. It wires the exact same object graph
 * {@code StandTestExtension.buildStandClient()} builds:
 *
 * <pre>
 *   StepExecutor[] + ScenarioValidator + EnvironmentRegistry + ReportingEventPublisher
 *     -&gt; DefaultScenarioRunner -&gt; DefaultStandClient
 * </pre>
 *
 * <p>Adapter executors are discovered by classpath presence ({@code @ConditionalOnClass}) — each adapter
 * on the classpath contributes its executor bean — and every bean is {@code @ConditionalOnMissingBean},
 * so a consumer can override any part by declaring its own. The whole configuration is gated by
 * {@code stand.test.enabled} (default {@code true}); setting it to {@code false} contributes no beans.
 *
 * <p><strong>Structure constraint (do not flatten).</strong> Every bean whose signature mentions an
 * optional module's type lives in a nested {@code @Configuration} class gated by a class-level
 * {@code @ConditionalOnClass}. The methods of this outer class may reference only always-present types
 * (core, starter, Spring, JDK): Spring's condition evaluation reflects over the declared methods of a
 * configuration class it processes, and resolving a method signature whose return type is missing from
 * the classpath throws {@code NoClassDefFoundError} before any {@code @ConditionalOnClass} on that
 * method is consulted. A nested class that fails its class-level condition is skipped from ASM metadata
 * and its methods are never reflected, so a consumer with only some adapters on the classpath starts
 * cleanly.
 *
 * <p>This class contains no transport/business logic: it only collects and wires the SDK's existing
 * contracts.
 */
@AutoConfiguration
@EnableConfigurationProperties(StandTestProperties.class)
@ConditionalOnProperty(prefix = "stand.test", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StandTestAutoConfiguration implements BeanClassLoaderAware {

    /**
     * The context's class loader, used to discover step executors registered through the SPI.
     *
     * <p>Taken through {@link BeanClassLoaderAware} rather than as a parameter of the runner
     * {@code @Bean} method, so that method's public signature stays exactly as consumers have always
     * seen it. Anyone who calls it directly — the starter cannot know whether someone does — keeps
     * compiling, and the discovery added by ADR-UI-008 costs them nothing.
     */
    private ClassLoader beanClassLoader;

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.beanClassLoader = classLoader;
    }

    /**
     * Structural + guardrail validator (empty-registry, destructive-SQL, whitelist checks).
     *
     * @return the default scenario validator
     */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioValidator standTestScenarioValidator() {
        return new DefaultScenarioValidator();
    }

    /**
     * Environment registry built from {@code stand.test.environments} (empty when none is configured).
     *
     * @param properties the bound stand-test properties
     * @return the environment registry
     */
    @Bean
    @ConditionalOnMissingBean
    public EnvironmentRegistry standTestEnvironmentRegistry(StandTestProperties properties) {
        return EnvironmentRegistryFactory.build(properties);
    }

    /**
     * No-op reporting fallback used when no other {@link ReportingEventPublisher} is present (Allure
     * absent or disabled, and no user-supplied publisher). Nested configurations are processed before
     * this class's own bean methods, so the Allure publisher — when present — wins this
     * {@code @ConditionalOnMissingBean}.
     *
     * @return the no-op reporting event publisher
     */
    @Bean
    @ConditionalOnMissingBean(ReportingEventPublisher.class)
    public ReportingEventPublisher standTestNoOpReportingEventPublisher() {
        return NoOpReportingEventPublisher.INSTANCE;
    }

    /**
     * Scenario runner over every registered {@link StepExecutor} plus the validator, registry and
     * publisher.
     *
     * <p>The executor list is the declared beans <em>and</em> whatever the context's class loader
     * registers through {@code META-INF/services} — see {@link StepExecutorDiscovery} for why, and for
     * the rule that a declared bean always wins. This is what lets an adapter that ships only an SPI
     * registration, {@code stand-test-ui} being the one that prompted it, work on the Spring path with
     * no bean of its own, exactly as it already did on plain JUnit (ADR-UI-008).
     *
     * @param executors all step executors declared as beans on the context (empty when no adapter is present)
     * @param validator the scenario validator
     * @param environmentRegistry the environment registry
     * @param reportingEventPublisher the reporting publisher
     * @return the scenario runner
     */
    @Bean
    @ConditionalOnMissingBean
    public ScenarioRunner standTestScenarioRunner(
            List<StepExecutor> executors,
            ScenarioValidator validator,
            EnvironmentRegistry environmentRegistry,
            ReportingEventPublisher reportingEventPublisher) {
        List<StepExecutor> merged = StepExecutorDiscovery.merge(executors, beanClassLoader);
        return new DefaultScenarioRunner(merged, validator, environmentRegistry, reportingEventPublisher);
    }

    /**
     * The public facade consumers inject: a thin {@link DefaultStandClient} over the runner.
     *
     * @param runner the scenario runner
     * @return the stand client
     */
    @Bean
    @ConditionalOnMissingBean
    public StandClient standTestStandClient(ScenarioRunner runner) {
        return new DefaultStandClient(runner);
    }

    /**
     * REST executor contribution, active only when {@code stand-test-rest} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestStepExecutor.class)
    static class RestConfiguration {

        /**
         * REST step executor.
         *
         * @return the REST step executor
         */
        @Bean
        @ConditionalOnMissingBean(RestStepExecutor.class)
        public RestStepExecutor standTestRestStepExecutor() {
            return new RestStepExecutor();
        }
    }

    /**
     * Kafka executor contribution, active only when {@code stand-test-kafka} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaStepExecutor.class)
    static class KafkaConfiguration {

        /**
         * Kafka step executor.
         *
         * @return the Kafka step executor
         */
        @Bean
        @ConditionalOnMissingBean(KafkaStepExecutor.class)
        public KafkaStepExecutor standTestKafkaStepExecutor() {
            return new KafkaStepExecutor();
        }
    }

    /**
     * DB executor contribution, active only when {@code stand-test-db} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DbStepExecutor.class)
    static class DbConfiguration {

        /**
         * DB step executor.
         *
         * @return the DB step executor
         */
        @Bean
        @ConditionalOnMissingBean(DbStepExecutor.class)
        public DbStepExecutor standTestDbStepExecutor() {
            return new DbStepExecutor();
        }
    }

    /**
     * gRPC executor contribution, active only when {@code stand-test-grpc} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(GrpcStepExecutor.class)
    static class GrpcConfiguration {

        /**
         * gRPC step executor.
         *
         * @return the gRPC step executor
         */
        @Bean
        @ConditionalOnMissingBean(GrpcStepExecutor.class)
        public GrpcStepExecutor standTestGrpcStepExecutor() {
            return new GrpcStepExecutor();
        }
    }

    /**
     * Allure reporting contribution, active only when {@code stand-test-allure} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AllureReportingEventPublisher.class)
    static class AllureConfiguration {

        /**
         * Allure reporting publisher, preferred when neither the global
         * {@code stand.test.reporting.enabled} nor the specific {@code stand.test.reporting.allure.enabled}
         * toggle is {@code false}. Registered from a nested configuration, which Spring processes before
         * the outer class's no-op fallback, so its {@code @ConditionalOnMissingBean} yields to it.
         *
         * @return the Allure reporting event publisher
         */
        @Bean
        @ConditionalOnProperty(prefix = "stand.test.reporting", name = {"enabled", "allure.enabled"}, havingValue = "true",
                matchIfMissing = true)
        @ConditionalOnMissingBean(ReportingEventPublisher.class)
        public ReportingEventPublisher standTestAllureReportingEventPublisher() {
            return new AllureReportingEventPublisher();
        }
    }

    /**
     * Await contribution, active only when {@code stand-test-await} is on the classpath.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Awaiter.class)
    static class AwaitConfiguration {

        /**
         * A fresh system-backed {@link Awaiter} for ad-hoc waits.
         *
         * @return the awaiter
         */
        @Bean
        @ConditionalOnMissingBean
        public Awaiter standTestAwaiter() {
            return Awaiter.create();
        }

        /**
         * A reusable default {@link AwaitPolicy} built from {@code stand.test.await.*}. A per-await
         * policy is normally built at the call site; this bean is a convenient, configuration-driven
         * default.
         *
         * @param properties the bound stand-test properties
         * @return the default await policy
         */
        @Bean
        @ConditionalOnMissingBean
        public AwaitPolicy standTestAwaitPolicy(StandTestProperties properties) {
            StandTestProperties.Await await = properties.getAwait();
            return AwaitPolicy.builder("stand-test default await")
                    .timeout(await.getTimeout())
                    .pollInterval(await.getPollInterval())
                    .build();
        }
    }
}
