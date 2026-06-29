package ru.alfa.stand.test.core.scenario;

/**
 * Generic contract for a single scenario step.
 *
 * <p>Core only defines this generic contract plus {@link GenericStep}. Concrete typed steps for
 * REST/Kafka/DB/gRPC live in their respective adapter modules and produce instances that the runner
 * dispatches by {@link #type()} through the step-executor SPI.
 */
public interface ScenarioStep {

    /**
     * Returns the step id, unique within a scenario.
     *
     * @return the non-blank step id
     */
    String id();

    /**
     * Returns the step type used to dispatch to a step executor (for example {@code rest.post}).
     *
     * @return the non-blank step type
     */
    String type();

    /**
     * Returns a human-readable description of the step.
     *
     * @return the description, possibly empty but never null
     */
    String description();
}
