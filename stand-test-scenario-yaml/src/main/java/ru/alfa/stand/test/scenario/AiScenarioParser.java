package ru.alfa.stand.test.scenario;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.Scenario;

/**
 * Parses an AI-format declarative scenario — the flat {@code steps: [{ id, type, ... }]} surface described
 * by the JSON Schema shipped in {@code stand-test-ai-schema} — into the same generic core {@link Scenario}
 * model the runtime executes (the second declarative input alongside {@link YamlScenarioParser}).
 *
 * <p>It normalizes the AI ergonomic fields onto the yaml-surface field map the existing translators read
 * (so a document accepted by the schema reaches a runnable {@code Scenario}), then delegates to the same
 * {@code *StepTranslator}s to emit the exact {@code GenericStep} wire keys. It builds nothing else, opens no
 * connections, reads no environment, and leaves {@code ${...}} placeholders for the runtime resolver.
 *
 * <p>Fail-closed like {@link YamlScenarioParser}: unknown fields, unknown/unsupported step types, and
 * constructs no adapter can execute yet (inline JSON bodies, non-{@code equals} matchers,
 * {@code expect.rowExists}, {@code grpc.unary}) are rejected as config-class {@link StandTestException}.
 * JSON is a subset of YAML, so the document may be JSON or YAML. Stateless and thread-safe.
 */
public final class AiScenarioParser {

    private static final Set<String> KNOWN_TOP_LEVEL = Set.of("id", "environment", "title", "description", "tags", "steps");

    /**
     * Parses an AI-format scenario document (JSON or YAML).
     *
     * @param document the document text
     * @return the built {@link Scenario}
     * @throws StandTestException if the document is structurally invalid or uses a not-yet-executable construct
     */
    public Scenario parse(String document) {
        Objects.requireNonNull(document, "document must not be null");
        Map<String, Object> root = SurfaceValues.asMap(SafeYaml.load(document), "<document>");
        SurfaceValues.checkKnownKeys(root, KNOWN_TOP_LEVEL, "<document>");

        var builder = Scenario.builder(SurfaceValues.requireString(root, "id", "<document>"))
                .environment(SurfaceValues.requireString(root, "environment", "<document>"));
        String title = SurfaceValues.optionalString(root, "title", "<document>");
        if (title != null) {
            builder.title(title);
        }
        String description = SurfaceValues.optionalString(root, "description", "<document>");
        if (description != null) {
            builder.description(description);
        }
        for (Object tag : tags(root)) {
            if (!(tag instanceof String text) || text.isBlank()) {
                throw new StandTestException("Each entry in 'tags' must be a non-blank string, but found " + tag);
            }
            builder.tag(text);
        }

        List<Object> nodes = SurfaceValues.asList(requireSteps(root), "steps");
        List<GenericStep> steps = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            steps.add(translateStep(nodes.get(index), index));
        }
        if (steps.isEmpty()) {
            throw new StandTestException("A scenario must declare at least one step under 'steps'");
        }
        builder.steps(steps);
        return builder.build();
    }

    /**
     * Parses an AI-format scenario from a classpath resource.
     *
     * @param classpathResource the resource path
     * @return the built {@link Scenario}
     * @throws StandTestException if the resource is missing, unreadable or structurally invalid
     */
    public Scenario parseResource(String classpathResource) {
        Objects.requireNonNull(classpathResource, "classpathResource must not be null");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new StandTestException("AI scenario resource not found on the classpath: " + classpathResource);
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException readFailure) {
            throw new StandTestException("Failed to read AI scenario resource: " + classpathResource, readFailure);
        }
    }

    private static GenericStep translateStep(Object node, int index) {
        String base = "steps[" + index + "]";
        Map<String, Object> fields = SurfaceValues.asMap(node, base);
        String type = SurfaceValues.requireString(fields, "type", base);
        String location = base + "(" + type + ")";
        String id = SurfaceValues.optionalString(fields, "id", location);
        if (id == null || id.isBlank()) {
            id = type + "#" + index;
        }
        Map<String, Object> surface = AiStepNormalizer.normalize(type, fields, location);
        Map<String, Object> params = translateParams(type, surface, location);
        String description = SurfaceValues.optionalString(fields, "description", location);
        String describe = (description != null && !description.isBlank())
                ? description : StepNodeTranslator.describe(type, params);
        return new GenericStep(id, type, describe, params);
    }

    private static Map<String, Object> translateParams(String type, Map<String, Object> surface, String location) {
        if (type.startsWith("rest.")) {
            return RestStepTranslator.params(type, surface, location);
        }
        if ("kafka.send".equals(type) || "kafka.expect".equals(type)) {
            return KafkaStepTranslator.params(type, surface, location);
        }
        if ("grpc.unary".equals(type)) {
            return GrpcStepTranslator.params(type, surface, location);
        }
        return DbStepTranslator.params(type, surface, location);
    }

    private static Object requireSteps(Map<String, Object> root) {
        Object steps = root.get("steps");
        if (steps == null) {
            throw new StandTestException("A scenario must declare 'steps'");
        }
        return steps;
    }

    private static List<Object> tags(Map<String, Object> root) {
        Object tags = root.get("tags");
        return (tags == null) ? List.of() : SurfaceValues.asList(tags, "tags");
    }
}
