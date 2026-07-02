package ru.alfa.stand.test.starter;

import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
 * <p>This class contains no transport/business logic: it only collects and wires the SDK's existing
 * contracts. gRPC is not wired yet — the adapter is a skeleton with no executor.
 */
@AutoConfiguration
@EnableConfigurationProperties(StandTestProperties.class)
@ConditionalOnProperty(prefix = "stand.test", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StandTestAutoConfiguration {

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
     * REST step executor, contributed when {@code stand-test-rest} is on the classpath.
     *
     * @return the REST step executor
     */
    @Bean
    @ConditionalOnClass(RestStepExecutor.class)
    @ConditionalOnMissingBean(RestStepExecutor.class)
    public RestStepExecutor standTestRestStepExecutor() {
        return new RestStepExecutor();
    }

    /**
     * Kafka step executor, contributed when {@code stand-test-kafka} is on the classpath.
     *
     * @return the Kafka step executor
     */
    @Bean
    @ConditionalOnClass(KafkaStepExecutor.class)
    @ConditionalOnMissingBean(KafkaStepExecutor.class)
    public KafkaStepExecutor standTestKafkaStepExecutor() {
        return new KafkaStepExecutor();
    }

    /**
     * DB step executor, contributed when {@code stand-test-db} is on the classpath.
     *
     * @return the DB step executor
     */
    @Bean
    @ConditionalOnClass(DbStepExecutor.class)
    @ConditionalOnMissingBean(DbStepExecutor.class)
    public DbStepExecutor standTestDbStepExecutor() {
        return new DbStepExecutor();
    }

    /**
     * Allure reporting publisher, preferred when {@code stand-test-allure} is on the classpath and
     * {@code stand.test.reporting.allure.enabled} is not {@code false}. Declared before the no-op
     * fallback so its {@code @ConditionalOnMissingBean} yields to it.
     *
     * @return the Allure reporting event publisher
     */
    @Bean
    @ConditionalOnClass(AllureReportingEventPublisher.class)
    @ConditionalOnProperty(prefix = "stand.test.reporting.allure", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(ReportingEventPublisher.class)
    public ReportingEventPublisher standTestAllureReportingEventPublisher() {
        return new AllureReportingEventPublisher();
    }

    /**
     * No-op reporting fallback used when no other {@link ReportingEventPublisher} is present (Allure
     * absent or disabled, and no user-supplied publisher).
     *
     * @return the no-op reporting event publisher
     */
    @Bean
    @ConditionalOnMissingBean(ReportingEventPublisher.class)
    public ReportingEventPublisher standTestNoOpReportingEventPublisher() {
        return NoOpReportingEventPublisher.INSTANCE;
    }

    /**
     * Scenario runner over every registered {@link StepExecutor} (an empty list when no adapter is
     * present) plus the validator, registry and publisher.
     *
     * @param executors all step executors on the context
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
        return new DefaultScenarioRunner(executors, validator, environmentRegistry, reportingEventPublisher);
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
     * A fresh system-backed {@link Awaiter} for ad-hoc waits, contributed when {@code stand-test-await}
     * is on the classpath.
     *
     * @return the awaiter
     */
    @Bean
    @ConditionalOnClass(Awaiter.class)
    @ConditionalOnMissingBean
    public Awaiter standTestAwaiter() {
        return Awaiter.create();
    }

    /**
     * A reusable default {@link AwaitPolicy} built from {@code stand.test.await.*}, contributed when
     * {@code stand-test-await} is on the classpath. A per-await policy is normally built at the call
     * site; this bean is a convenient, configuration-driven default.
     *
     * @param properties the bound stand-test properties
     * @return the default await policy
     */
    @Bean
    @ConditionalOnClass(AwaitPolicy.class)
    @ConditionalOnMissingBean
    public AwaitPolicy standTestAwaitPolicy(StandTestProperties properties) {
        StandTestProperties.Await await = properties.getAwait();
        return AwaitPolicy.builder("stand-test default await")
                .timeout(await.getTimeout())
                .pollInterval(await.getPollInterval())
                .build();
    }
}
