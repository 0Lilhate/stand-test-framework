package ru.alfa.stand.test.core.scenario;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generic, transport-agnostic scenario step: a type plus typed parameters.
 *
 * <p>This is the core representation that both DSL inputs converge to. It carries no IO and no
 * adapter logic. The {@code parameters} map is defensively copied and exposed as immutable — deeply:
 * nested {@code Map}/{@code List}/{@code Set} values (headers/query maps, assertion/capture entry
 * lists) are copied too, so a caller retaining a reference to a nested collection cannot mutate a
 * built step. Values must not be null at any depth.
 *
 * @param id the non-blank step id
 * @param type the non-blank step type
 * @param description the description (empty if null was supplied)
 * @param parameters the immutable parameter map
 */
public record GenericStep(String id, String type, String description, Map<String, Object> parameters)
        implements ScenarioStep {

    public GenericStep {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("step id must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("step type must not be blank");
        }
        description = (description == null) ? "" : description;
        parameters = (parameters == null) ? Map.of() : deepImmutableMap(parameters);
    }

    private static Map<String, Object> deepImmutableMap(Map<?, ?> source) {
        return source.entrySet().stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(entry -> String.valueOf(entry.getKey()), entry -> deepImmutable(entry.getValue())),
                        Map::copyOf));
    }

    private static Object deepImmutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepImmutableMap(map);
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list.stream().map(GenericStep::deepImmutable).toList());
        }
        if (value instanceof Set<?> set) {
            return Set.copyOf(set.stream().map(GenericStep::deepImmutable).collect(Collectors.toSet()));
        }
        return value;
    }

    /**
     * Creates a step with no description and no parameters.
     *
     * @param id the non-blank step id
     * @param type the non-blank step type
     * @return a new generic step
     */
    public static GenericStep of(String id, String type) {
        return new GenericStep(id, type, "", Map.of());
    }

    /**
     * Creates a step with a description and no parameters.
     *
     * @param id the non-blank step id
     * @param type the non-blank step type
     * @param description the description
     * @return a new generic step
     */
    public static GenericStep of(String id, String type, String description) {
        return new GenericStep(id, type, description, Map.of());
    }
}
