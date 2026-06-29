/**
 * Immutable scenario model.
 *
 * <p>{@link ru.alfa.stand.test.core.scenario.Scenario} is the immutable definition built by the Java
 * DSL lazy builder (and, later, by the YAML parser). {@link ru.alfa.stand.test.core.scenario.ScenarioStep}
 * is the generic step contract; concrete typed steps (REST/Kafka/DB/gRPC) belong to the adapter
 * modules, while {@link ru.alfa.stand.test.core.scenario.GenericStep} is the core's generic
 * representation that both DSL inputs converge to.
 */
package ru.alfa.stand.test.core.scenario;
