package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.alfa.stand.test.allure.AllureReportingEventPublisher;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.event.ScenarioEvent;
import ru.alfa.stand.test.core.event.StepEvent;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.core.validation.ScenarioValidator;
import ru.alfa.stand.test.db.DbStepExecutor;
import ru.alfa.stand.test.grpc.GrpcStepExecutor;
import ru.alfa.stand.test.kafka.KafkaStepExecutor;
import ru.alfa.stand.test.rest.RestStepExecutor;

/**
 * Application-context tests for {@link StandTestAutoConfiguration}. These are offline: they only assert
 * bean presence/type, property binding and conditional/override behaviour through an
 * {@link ApplicationContextRunner} — no real stand, no REST/Kafka/DB/gRPC IO, no {@code Thread.sleep}.
 */
class StandTestAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StandTestAutoConfiguration.class));

    @Test
    @DisplayName("default context wires StandClient plus all adapter executors on the classpath")
    void defaultContext_wiresStandClientAndExecutors() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(StandClient.class);
            assertThat(context).hasSingleBean(ScenarioRunner.class);
            assertThat(context).hasSingleBean(ScenarioValidator.class);
            assertThat(context).hasSingleBean(EnvironmentRegistry.class);
            assertThat(context).hasSingleBean(RestStepExecutor.class);
            assertThat(context).hasSingleBean(KafkaStepExecutor.class);
            assertThat(context).hasSingleBean(DbStepExecutor.class);
            assertThat(context).hasSingleBean(GrpcStepExecutor.class);
            assertThat(context).getBeans(StepExecutor.class).hasSize(4);
            assertThat(context).hasSingleBean(Awaiter.class);
            assertThat(context).hasSingleBean(AwaitPolicy.class);
        });
    }

    @Test
    @DisplayName("stand.test.enabled=false contributes no SDK beans")
    void disabled_contributesNoBeans() {
        runner.withPropertyValues("stand.test.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(StandClient.class);
            assertThat(context).doesNotHaveBean(ScenarioRunner.class);
            assertThat(context).doesNotHaveBean(EnvironmentRegistry.class);
            assertThat(context).doesNotHaveBean(StepExecutor.class);
        });
    }

    @Test
    @DisplayName("environments properties bind into the EnvironmentRegistry")
    void environmentProperties_bindIntoRegistry() {
        runner.withPropertyValues(
                "stand.test.environments.ift.services.client-service.base-url-ref=CLIENT_SERVICE_URL",
                "stand.test.environments.ift.services.client-service.correlation.source=HEADER",
                "stand.test.environments.ift.services.client-service.correlation.name=X-Correlation-Id",
                "stand.test.environments.ift.datasources.main-db.url-ref=MAIN_DB_URL",
                "stand.test.environments.ift.datasources.main-db.user-ref=MAIN_DB_USER",
                "stand.test.environments.ift.datasources.main-db.password-ref=MAIN_DB_PASSWORD",
                "stand.test.environments.ift.datasources.main-db.allowed-schemas[0]=test_data",
                "stand.test.environments.ift.datasources.main-db.write-allowed=true",
                "stand.test.environments.ift.topics.events.name=ift.events.v1",
                "stand.test.environments.ift.topics.events.correlation.source=KEY",
                "stand.test.environments.ift.topics.events.correlation.name=corrId",
                "stand.test.environments.ift.grpc-targets.accounts.target-ref=ACCOUNTS_GRPC",
                "stand.test.environments.ift.kafka-cluster.bootstrap-servers-ref=KAFKA_BOOTSTRAP",
                "stand.test.environments.ift.kafka-cluster.security-protocol-ref=KAFKA_SECURITY").run(context -> {
                    EnvironmentRegistry registry = context.getBean(EnvironmentRegistry.class);
                    assertThat(registry.environment("ift")).hasValueSatisfying(StandTestAutoConfigurationTest::assertIftEnvironment);
                });
    }

    private static void assertIftEnvironment(EnvironmentDefinition ift) {
        assertThat(ift.service("client-service")).hasValueSatisfying(service -> {
            assertThat(service.baseUrlRef()).isEqualTo("CLIENT_SERVICE_URL");
            assertThat(service.correlation().name()).isEqualTo("X-Correlation-Id");
        });
        assertThat(ift.datasource("main-db")).hasValueSatisfying(datasource -> {
            assertThat(datasource.urlRef()).isEqualTo("MAIN_DB_URL");
            assertThat(datasource.writeAllowed()).isTrue();
            assertThat(datasource.isSchemaAllowed("test_data")).isTrue();
        });
        assertThat(ift.topic("events")).hasValueSatisfying(topic -> assertThat(topic.name()).isEqualTo("ift.events.v1"));
        assertThat(ift.grpcTarget("accounts")).hasValueSatisfying(target -> assertThat(target.targetRef()).isEqualTo("ACCOUNTS_GRPC"));
        assertThat(ift.kafkaCluster()).isNotNull();
        assertThat(ift.kafkaCluster().bootstrapServersRef()).isEqualTo("KAFKA_BOOTSTRAP");
        assertThat(ift.kafkaCluster().securityProtocolReference()).contains("KAFKA_SECURITY");
    }

    @Test
    @DisplayName("an unknown property key under stand.test fails the context (fail-closed binding)")
    void unknownPropertyKey_failsContext() {
        runner.withPropertyValues("stand.test.awiat.timeout=10s").run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a *-ref value that is obviously a resolved endpoint fails the context")
    void valueShapedReference_failsContext() {
        runner.withPropertyValues(
                "stand.test.environments.ift.services.client-service.base-url-ref=https://real-stand.example").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("reference NAME");
                });
    }

    @Test
    @DisplayName("a blank reference in an environment fails the context with a contextual message")
    void blankReference_failsContext() {
        runner.withPropertyValues(
                "stand.test.environments.ift.services.broken.correlation.source=HEADER",
                "stand.test.environments.ift.services.broken.correlation.name=X-Correlation-Id").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("baseUrlRef must not be blank");
                });
    }

    @Test
    @DisplayName("custom await timeout and poll interval bind into the default AwaitPolicy")
    void customAwaitProperties_bindIntoPolicy() {
        runner.withPropertyValues(
                "stand.test.await.timeout=10s",
                "stand.test.await.poll-interval=250ms").run(context -> {
                    AwaitPolicy policy = context.getBean(AwaitPolicy.class);
                    assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(policy.pollInterval()).isEqualTo(Duration.ofMillis(250));
                });
    }

    @Test
    @DisplayName("defaults expose enabled reporting and 30s/500ms await on the properties bean")
    void propertiesBean_hasExpectedDefaults() {
        runner.run(context -> {
            StandTestProperties properties = context.getBean(StandTestProperties.class);
            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getReporting().isEnabled()).isTrue();
            assertThat(properties.getReporting().getAllure().isEnabled()).isTrue();
            assertThat(properties.getAwait().getTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getAwait().getPollInterval()).isEqualTo(Duration.ofMillis(500));
        });
    }

    @Test
    @DisplayName("a user StandClient bean is not overridden by the auto-configuration")
    void userStandClient_isNotOverridden() {
        runner.withUserConfiguration(CustomStandClientConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(StandClient.class);
            assertThat(context.getBean(StandClient.class)).isSameAs(CustomStandClientConfiguration.CLIENT);
        });
    }

    @Test
    @DisplayName("a user AwaitPolicy bean is not overridden by the default")
    void userAwaitPolicy_isNotOverridden() {
        runner.withUserConfiguration(CustomAwaitPolicyConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(AwaitPolicy.class);
            assertThat(context.getBean(AwaitPolicy.class)).isSameAs(CustomAwaitPolicyConfiguration.POLICY);
        });
    }

    @Test
    @DisplayName("Allure publisher is preferred when Allure is on the classpath and enabled")
    void allurePresent_publisherIsAllure() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ReportingEventPublisher.class);
            assertThat(context.getBean(ReportingEventPublisher.class)).isInstanceOf(AllureReportingEventPublisher.class);
        });
    }

    @Test
    @DisplayName("disabling Allure falls back to the no-op publisher")
    void allureDisabled_fallsBackToNoOp() {
        runner.withPropertyValues("stand.test.reporting.allure.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(ReportingEventPublisher.class);
            assertThat(context.getBean(ReportingEventPublisher.class)).isInstanceOf(NoOpReportingEventPublisher.class);
        });
    }

    @Test
    @DisplayName("the global stand.test.reporting.enabled=false falls back to the no-op publisher even with Allure present and enabled")
    void reportingDisabledGlobally_fallsBackToNoOp() {
        runner.withPropertyValues("stand.test.reporting.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(ReportingEventPublisher.class);
            assertThat(context.getBean(ReportingEventPublisher.class)).isInstanceOf(NoOpReportingEventPublisher.class);
        });
    }

    @Test
    @DisplayName("removing Allure from the classpath falls back to the no-op publisher")
    void allureAbsent_fallsBackToNoOp() {
        runner.withClassLoader(new FilteredClassLoader(AllureReportingEventPublisher.class)).run(context -> {
            assertThat(context).hasSingleBean(ReportingEventPublisher.class);
            assertThat(context.getBean(ReportingEventPublisher.class)).isInstanceOf(NoOpReportingEventPublisher.class);
        });
    }

    @Test
    @DisplayName("a user ReportingEventPublisher bean wins over both Allure and the no-op fallback")
    void userReportingPublisher_isNotOverridden() {
        runner.withUserConfiguration(CustomPublisherConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(ReportingEventPublisher.class);
            assertThat(context.getBean(ReportingEventPublisher.class)).isSameAs(CustomPublisherConfiguration.PUBLISHER);
        });
    }

    @Test
    @DisplayName("removing the REST adapter drops only its executor; the client still wires")
    void restAdapterAbsent_executorNotRegistered() {
        runner.withClassLoader(new FilteredClassLoader(RestStepExecutor.class)).run(context -> {
            assertThat(context).doesNotHaveBean("standTestRestStepExecutor");
            assertThat(context).getBeans(StepExecutor.class).hasSize(3);
            assertThat(context).hasSingleBean(StandClient.class);
        });
    }

    @Test
    @DisplayName("removing the gRPC adapter drops only its executor; the client still wires")
    void grpcAdapterAbsent_executorNotRegistered() {
        runner.withClassLoader(new FilteredClassLoader(GrpcStepExecutor.class)).run(context -> {
            assertThat(context).doesNotHaveBean("standTestGrpcStepExecutor");
            assertThat(context).getBeans(StepExecutor.class).hasSize(3);
            assertThat(context).hasSingleBean(StandClient.class);
        });
    }

    @Test
    @DisplayName("with no adapters on the classpath the client still wires with zero executors")
    void allAdaptersAbsent_clientWiresWithNoExecutors() {
        runner.withClassLoader(new FilteredClassLoader(
                RestStepExecutor.class, KafkaStepExecutor.class, DbStepExecutor.class, GrpcStepExecutor.class)).run(context -> {
                    assertThat(context).getBeans(StepExecutor.class).isEmpty();
                    assertThat(context).hasSingleBean(StandClient.class);
                    assertThat(context).hasSingleBean(ScenarioRunner.class);
                });
    }

    @Test
    @DisplayName("removing the await module drops the Awaiter and AwaitPolicy beans")
    void awaitAbsent_noAwaitBeans() {
        runner.withClassLoader(new FilteredClassLoader(Awaiter.class, AwaitPolicy.class)).run(context -> {
            assertThat(context).doesNotHaveBean("standTestAwaiter");
            assertThat(context).doesNotHaveBean("standTestAwaitPolicy");
            assertThat(context).hasSingleBean(StandClient.class);
        });
    }

    @Configuration
    static class CustomStandClientConfiguration {

        static final StandClient CLIENT = new MarkerStandClient();

        @Bean
        StandClient customStandClient() {
            return CLIENT;
        }
    }

    @Configuration
    static class CustomAwaitPolicyConfiguration {

        static final AwaitPolicy POLICY = AwaitPolicy.ofSeconds("custom", 5);

        @Bean
        AwaitPolicy customAwaitPolicy() {
            return POLICY;
        }
    }

    @Configuration
    static class CustomPublisherConfiguration {

        static final ReportingEventPublisher PUBLISHER = new MarkerReportingEventPublisher();

        @Bean
        ReportingEventPublisher customReportingEventPublisher() {
            return PUBLISHER;
        }
    }

    private static final class MarkerStandClient implements StandClient {

        @Override
        public ScenarioResult run(Scenario scenario) {
            throw new UnsupportedOperationException("marker client is never executed");
        }
    }

    private static final class MarkerReportingEventPublisher implements ReportingEventPublisher {

        @Override
        public void publish(ScenarioEvent event) {
            // marker publisher: no-op
        }

        @Override
        public void publish(StepEvent event) {
            // marker publisher: no-op
        }
    }
}
