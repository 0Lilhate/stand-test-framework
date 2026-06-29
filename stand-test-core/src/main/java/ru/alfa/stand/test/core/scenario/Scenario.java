package ru.alfa.stand.test.core.scenario;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ru.alfa.stand.test.core.identifier.ScenarioId;

/**
 * Immutable scenario definition built by the Java DSL lazy builder.
 *
 * <p>Building a scenario never performs IO and never executes steps — it only assembles the model.
 * Structural validation (non-empty steps, unique step ids, …) is a separate, explicit step performed
 * by a {@code ScenarioValidator}. The {@code steps} list and {@code tags} set are defensively copied
 * and exposed as immutable.
 */
public final class Scenario {

    private final ScenarioId id;
    private final String environment;
    private final List<ScenarioStep> steps;
    private final Set<String> tags;
    private final String title;
    private final String description;

    private Scenario(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "scenario id must not be null");
        // The model is permissive about environment (it may be blank here); the ScenarioValidator
        // is the single place that flags a blank environment. See docs/arch §3 (Model -> Validator).
        this.environment = (builder.environment == null) ? "" : builder.environment;
        this.steps = List.copyOf(builder.steps);
        this.tags = Set.copyOf(builder.tags);
        this.title = builder.title;
        this.description = builder.description;
    }

    /**
     * Starts a builder for a scenario with the given id.
     *
     * @param id the scenario id
     * @return a new builder
     */
    public static Builder builder(ScenarioId id) {
        return new Builder(id);
    }

    /**
     * Starts a builder for a scenario with the given id value.
     *
     * @param id the scenario id value
     * @return a new builder
     */
    public static Builder builder(String id) {
        return new Builder(ScenarioId.of(id));
    }

    public ScenarioId id() {
        return id;
    }

    public String environment() {
        return environment;
    }

    public List<ScenarioStep> steps() {
        return steps;
    }

    public Set<String> tags() {
        return tags;
    }

    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Scenario other)) {
            return false;
        }
        return id.equals(other.id)
                && environment.equals(other.environment)
                && steps.equals(other.steps)
                && tags.equals(other.tags)
                && Objects.equals(title, other.title)
                && Objects.equals(description, other.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, environment, steps, tags, title, description);
    }

    @Override
    public String toString() {
        return "Scenario{id=" + id + ", environment=" + environment + ", steps=" + steps.size() + ", tags=" + tags + "}";
    }

    /**
     * Mutable builder that assembles an immutable {@link Scenario}. The builder performs no IO.
     */
    public static final class Builder {

        private final ScenarioId id;
        private final List<ScenarioStep> steps = new ArrayList<>();
        private final Set<String> tags = new LinkedHashSet<>();
        private String environment;
        private String title;
        private String description;

        private Builder(ScenarioId id) {
            this.id = Objects.requireNonNull(id, "scenario id must not be null");
        }

        /**
         * Sets the logical environment name.
         *
         * @param environment the environment name
         * @return this builder
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Appends a step to the scenario.
         *
         * @param step the step to append
         * @return this builder
         */
        public Builder step(ScenarioStep step) {
            this.steps.add(Objects.requireNonNull(step, "step must not be null"));
            return this;
        }

        /**
         * Appends all the given steps to the scenario.
         *
         * @param steps the steps to append
         * @return this builder
         */
        public Builder steps(Collection<? extends ScenarioStep> steps) {
            Objects.requireNonNull(steps, "steps must not be null");
            steps.forEach(this::step);
            return this;
        }

        /**
         * Adds a free-form tag.
         *
         * @param tag the tag to add
         * @return this builder
         */
        public Builder tag(String tag) {
            this.tags.add(Objects.requireNonNull(tag, "tag must not be null"));
            return this;
        }

        /**
         * Sets the optional title.
         *
         * @param title the title
         * @return this builder
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the optional description.
         *
         * @param description the description
         * @return this builder
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Builds the immutable scenario.
         *
         * @return the assembled scenario
         */
        public Scenario build() {
            return new Scenario(this);
        }
    }
}
