package ru.alfa.stand.test.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.identifier.CorrelationId;
import ru.alfa.stand.test.core.identifier.ScenarioId;
import ru.alfa.stand.test.core.identifier.TestRunId;

class ScenarioContextTest {

    @Test
    @DisplayName("start generates a run id, correlation id and creation instant")
    void start_generatesMetadata() {
        ScenarioContext context = ScenarioContext.start(ScenarioId.of("flow"), "ift");

        assertThat(context.scenarioId().value()).isEqualTo("flow");
        assertThat(context.environment()).isEqualTo("ift");
        assertThat(context.testRunId()).isNotNull();
        assertThat(context.correlationId()).isNotNull();
        assertThat(context.createdAt()).isNotNull();
        assertThat(context.tags()).isEmpty();
    }

    @Test
    @DisplayName("two starts produce different run and correlation ids")
    void start_producesUniqueIds() {
        ScenarioContext first = ScenarioContext.start(ScenarioId.of("flow"), "ift");
        ScenarioContext second = ScenarioContext.start(ScenarioId.of("flow"), "ift");

        assertThat(first.testRunId()).isNotEqualTo(second.testRunId());
        assertThat(first.correlationId()).isNotEqualTo(second.correlationId());
    }

    @Test
    @DisplayName("tags are defensively copied and exposed as immutable")
    void tags_areImmutableAndDefensivelyCopied() {
        Set<String> source = new HashSet<>();
        source.add("smoke");
        ScenarioContext context = ScenarioContext.start(ScenarioId.of("flow"), "ift", source);

        source.add("mutated");

        assertThat(context.tags()).containsExactly("smoke");
        assertThatThrownBy(() -> context.tags().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("start with an explicit clock makes createdAt deterministic")
    void start_withFixedClock_isDeterministic() {
        Instant fixed = Instant.parse("2026-07-03T10:15:30Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

        ScenarioContext context = ScenarioContext.start(ScenarioId.of("flow"), "ift", Set.of(), clock);

        assertThat(context.createdAt()).isEqualTo(fixed);
    }

    @Test
    @DisplayName("blank environment is rejected")
    void blankEnvironment_isRejected() {
        assertThatThrownBy(() -> ScenarioContext.start(ScenarioId.of("flow"), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null required metadata is rejected")
    void nullMetadata_isRejected() {
        assertThatThrownBy(() -> new ScenarioContext(
                null,
                TestRunId.generate(),
                CorrelationId.generate(),
                "ift",
                Set.of(),
                Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
