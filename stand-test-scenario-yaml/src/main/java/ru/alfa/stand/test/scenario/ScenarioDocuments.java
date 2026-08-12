package ru.alfa.stand.test.scenario;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * The scenario-level reading both declarative surfaces share: id, environment, title, description, tags,
 * and the "a scenario must declare at least one step" rule.
 *
 * <p>{@link YamlScenarioParser} and {@link AiScenarioParser} differ in how they spell the environment field
 * ({@code env} vs {@code environment}) and in how they lay out steps ({@code given}/{@code then} phases vs a
 * flat {@code steps} list). Everything else was the same twenty lines, written twice — which is how two
 * surfaces of one model drift apart on the metadata neither of them is really about.
 */
final class ScenarioDocuments {

    private ScenarioDocuments() {
    }

    /**
     * Starts a {@link Scenario.Builder} from the document root, reading the shared metadata.
     *
     * @param root the document root mapping
     * @param environmentField the surface name of the environment field ({@code env} or {@code environment})
     * @return a builder with id, environment and any declared title/description/tags applied
     */
    static Scenario.Builder startBuilder(Map<String, Object> root, String environmentField) {
        Scenario.Builder builder = Scenario.builder(SurfaceValues.requireString(root, "id", "<document>"))
                .environment(SurfaceValues.requireString(root, environmentField, "<document>"));
        applyIfPresent(SurfaceValues.optionalString(root, "title", "<document>"), builder::title);
        applyIfPresent(SurfaceValues.optionalString(root, "description", "<document>"), builder::description);
        tags(root).forEach(builder::tag);
        return builder;
    }

    private static void applyIfPresent(String value, Consumer<String> target) {
        if (value != null) {
            target.accept(value);
        }
    }

    private static List<String> tags(Map<String, Object> root) {
        Object declared = root.get("tags");
        if (declared == null) {
            return List.of();
        }
        return SurfaceValues.asList(declared, "tags").stream()
                .map(ScenarioDocuments::requireTag)
                .toList();
    }

    private static String requireTag(Object tag) {
        if (!(tag instanceof String text) || text.isBlank()) {
            throw new StandTestException("Each entry in 'tags' must be a non-blank string, but found " + tag);
        }
        return text;
    }

    /**
     * Translates the nodes of one step sequence, giving each its positional location.
     *
     * @param nodes the loaded step nodes
     * @param translator maps one node and its index to a step
     * @return the translated steps, in document order
     */
    static List<GenericStep> translateSteps(List<Object> nodes, StepAt translator) {
        return IntStream.range(0, nodes.size())
                .mapToObj(index -> translator.translate(nodes.get(index), index))
                .toList();
    }

    /** Refuses a document that declares no step at all, naming the surface's own field. */
    static void requireAtLeastOneStep(List<GenericStep> steps, String where) {
        if (steps.isEmpty()) {
            throw new StandTestException("A scenario must declare at least one step under " + where);
        }
    }

    /** Translates one step node at a known position. */
    @FunctionalInterface
    interface StepAt {

        GenericStep translate(Object node, int index);
    }
}
