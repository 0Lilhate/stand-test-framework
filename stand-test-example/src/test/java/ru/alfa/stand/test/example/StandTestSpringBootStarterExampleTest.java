package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.execution.ScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.starter.StandTestAutoConfiguration;

/**
 * The Spring-consumer view of the SDK: dropping {@code stand-test-spring-boot-starter} on the classpath
 * auto-configures the same object graph the {@code @StandTest} extension assembles — checked offline
 * through {@link ApplicationContextRunner} (no bootable app, no real context, no IO). The starter's own
 * module tests cover the conditional matrix exhaustively; this example shows the four facts a consuming
 * team relies on: beans appear by default, {@code stand.test.enabled=false} turns everything off,
 * {@code stand.test.environments.*} properties bind into the registry, and a user-declared bean wins.
 */
class StandTestSpringBootStarterExampleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StandTestAutoConfiguration.class));

    @Test
    @DisplayName("the starter wires an injectable StandClient with every adapter executor on this classpath")
    void starter_wiresStandClientByDefault() {
        this.runner.run(context -> {
            assertThat(context).hasSingleBean(StandClient.class);
            assertThat(context).hasSingleBean(ScenarioRunner.class);
            assertThat(context).hasSingleBean(EnvironmentRegistry.class);
            assertThat(context).getBeans(StepExecutor.class).hasSize(4);
            assertThat(context).hasSingleBean(Awaiter.class);
            assertThat(context).hasSingleBean(AwaitPolicy.class);
        });
    }

    @Test
    @DisplayName("stand.test.enabled=false contributes no SDK beans")
    void starter_disabledContributesNothing() {
        this.runner.withPropertyValues("stand.test.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(StandClient.class);
            assertThat(context).doesNotHaveBean(ScenarioRunner.class);
            assertThat(context).doesNotHaveBean(EnvironmentRegistry.class);
            assertThat(context).doesNotHaveBean(StepExecutor.class);
        });
    }

    @Test
    @DisplayName("the example environment binds from stand.test.environments.* into the registry")
    void starter_bindsExampleEnvironmentProperties() {
        this.runner.withPropertyValues(
                "stand.test.environments.example.services.example-rest-service.base-url-ref=EXAMPLE_SERVICE_URL",
                "stand.test.environments.example.datasources.example-db.url-ref=EXAMPLE_DB_URL",
                "stand.test.environments.example.datasources.example-db.user-ref=EXAMPLE_DB_USER",
                "stand.test.environments.example.datasources.example-db.password-ref=EXAMPLE_DB_PASSWORD",
                "stand.test.environments.example.topics.example-request-topic.name=example.requests.v1",
                "stand.test.environments.example.grpc-targets.example-grpc-service.target-ref=EXAMPLE_GRPC_TARGET").run(context -> {
                    EnvironmentRegistry registry = context.getBean(EnvironmentRegistry.class);
                    assertThat(registry.environment("example")).hasValueSatisfying(environment -> {
                        assertThat(environment.service("example-rest-service"))
                                .hasValueSatisfying(service -> assertThat(service.baseUrlRef()).isEqualTo("EXAMPLE_SERVICE_URL"));
                        assertThat(environment.datasource("example-db"))
                                .hasValueSatisfying(datasource -> assertThat(datasource.urlRef()).isEqualTo("EXAMPLE_DB_URL"));
                        assertThat(environment.topic("example-request-topic"))
                                .hasValueSatisfying(topic -> assertThat(topic.name()).isEqualTo("example.requests.v1"));
                        assertThat(environment.grpcTarget("example-grpc-service"))
                                .hasValueSatisfying(target -> assertThat(target.targetRef()).isEqualTo("EXAMPLE_GRPC_TARGET"));
                    });
                });
    }

    @Test
    @DisplayName("a user-declared StandClient bean is kept; the starter never overrides it")
    void starter_keepsUserStandClient() {
        this.runner.withUserConfiguration(UserStandClientConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(StandClient.class);
            assertThat(context.getBean(StandClient.class)).isSameAs(UserStandClientConfiguration.USER_CLIENT);
        });
    }

    @Configuration
    static class UserStandClientConfiguration {

        static final StandClient USER_CLIENT = new UserStandClient();

        @Bean
        StandClient userStandClient() {
            return USER_CLIENT;
        }
    }

    private static final class UserStandClient implements StandClient {

        @Override
        public ScenarioResult run(Scenario scenario) {
            throw new UnsupportedOperationException("the example user client is never executed");
        }
    }
}
