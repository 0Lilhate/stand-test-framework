package ru.alfa.stand.test.example;

import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.DefaultStandClient;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.DatasourceDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentDefinition;
import ru.alfa.stand.test.core.environment.EnvironmentRegistry;
import ru.alfa.stand.test.core.environment.InMemoryEnvironmentRegistry;
import ru.alfa.stand.test.core.environment.KafkaClusterDefinition;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.environment.TopicDefinition;
import ru.alfa.stand.test.core.event.NoOpReportingEventPublisher;
import ru.alfa.stand.test.core.event.ReportingEventPublisher;
import ru.alfa.stand.test.core.execution.DefaultScenarioRunner;
import ru.alfa.stand.test.core.execution.StepExecutor;
import ru.alfa.stand.test.core.validation.DefaultScenarioValidator;
import ru.alfa.stand.test.db.DbStepExecutor;
import ru.alfa.stand.test.kafka.KafkaStepExecutor;
import ru.alfa.stand.test.rest.RestStepExecutor;
import ru.alfa.stand.test.rest.WebClientHttpCaller;

/**
 * Wiring shared by the examples: a whitelisted environment and a {@link StandClient} backed by the real
 * REST and DB executors pointed at in-process doubles. This mirrors how a consuming team assembles the
 * SDK, except the endpoints resolve to local doubles — REST through a passthrough base-URL resolver
 * (the {@code baseUrlRef} is the live server URL), DB through the no-arg executor whose default resolver
 * reads the run's environment refs (the seam ctor is package-private, so env-ref is the cross-module path).
 */
final class ExampleStand {

    static final String ENVIRONMENT = "ift";
    static final String SERVICE = "client-service";
    static final String DATASOURCE = "mainDb";
    static final String SCHEMA = "test_data";
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String TOPIC = "events";
    static final String TOPIC_NAME = "stand-test-example-events";

    private ExampleStand() {
    }

    static EnvironmentRegistry registry(String restBaseUrl) {
        ServiceEndpointDefinition service = new ServiceEndpointDefinition(
                SERVICE, restBaseUrl, new CorrelationConfig(CorrelationSource.HEADER, CORRELATION_HEADER));
        EnvironmentDefinition environment = new EnvironmentDefinition(
                ENVIRONMENT, Map.of(SERVICE, service), Map.of(), Map.of(DATASOURCE, datasource()), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static EnvironmentRegistry dbRegistry() {
        EnvironmentDefinition environment = new EnvironmentDefinition(
                ENVIRONMENT, Map.of(), Map.of(), Map.of(DATASOURCE, datasource()), Map.of());
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static EnvironmentRegistry kafkaRegistry() {
        TopicDefinition topic = new TopicDefinition(
                TOPIC, TOPIC_NAME, new CorrelationConfig(CorrelationSource.HEADER, CORRELATION_HEADER));
        EnvironmentDefinition environment = new EnvironmentDefinition(
                ENVIRONMENT, Map.of(), Map.of(TOPIC, topic), Map.of(), Map.of(),
                KafkaClusterDefinition.of("KAFKA_BOOTSTRAP_SERVERS"));
        return new InMemoryEnvironmentRegistry(Map.of(ENVIRONMENT, environment));
    }

    static StandClient stand(EnvironmentRegistry registry) {
        return stand(registry, NoOpReportingEventPublisher.INSTANCE);
    }

    static StandClient stand(EnvironmentRegistry registry, ReportingEventPublisher publisher) {
        List<StepExecutor> executors = List.of(
                new RestStepExecutor(new WebClientHttpCaller(), reference -> reference),
                new DbStepExecutor());
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                executors, new DefaultScenarioValidator(), registry, publisher);
        return new DefaultStandClient(runner);
    }

    static StandClient kafkaStand(EnvironmentRegistry registry) {
        List<StepExecutor> executors = List.of(new KafkaStepExecutor());
        DefaultScenarioRunner runner = new DefaultScenarioRunner(
                executors, new DefaultScenarioValidator(), registry, NoOpReportingEventPublisher.INSTANCE);
        return new DefaultStandClient(runner);
    }

    private static DatasourceDefinition datasource() {
        return new DatasourceDefinition(
                DATASOURCE, "MAIN_DB_URL", "MAIN_DB_USER", "MAIN_DB_PASSWORD", Set.of(SCHEMA), true);
    }
}
