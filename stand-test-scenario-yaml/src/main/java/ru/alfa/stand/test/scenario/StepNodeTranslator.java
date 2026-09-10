package ru.alfa.stand.test.scenario;

import java.util.Map;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;

/**
 * Translates one surface step node — a single-key mapping {@code {stepType: {fields...}}} — into a generic
 * {@link GenericStep}, dispatching to the per-family translator by the type prefix ({@code rest.}/
 * {@code kafka.}/{@code db.}). An explicit {@code id} field wins; otherwise a readable, position-unique id
 * {@code <phase>.<type>#<index>} is generated (step ids must be non-blank and unique — the validator
 * enforces uniqueness).
 */
final class StepNodeTranslator {

    private StepNodeTranslator() {
    }

    static GenericStep translate(Object stepNode, String phase, int index) {
        String base = phase + "[" + index + "]";
        Map<String, Object> single = SurfaceValues.asMap(stepNode, base);
        if (single.size() != 1) {
            throw new StandTestException("Each step at " + base + " must be a single-key mapping {stepType: {...}}, but had keys "
                    + single.keySet());
        }
        Map.Entry<String, Object> entry = single.entrySet().iterator().next();
        String type = entry.getKey();
        String location = base + "." + type;
        Map<String, Object> fields = SurfaceValues.asMap(entry.getValue(), location);
        String id = SurfaceValues.optionalString(fields, "id", location);
        if (id == null || id.isBlank()) {
            id = phase + "." + type + "#" + index;
        }
        Map<String, Object> params;
        if (type.startsWith("rest.")) {
            params = RestStepTranslator.params(type, fields, location);
        } else if ("kafka.send".equals(type) || "kafka.expect".equals(type)) {
            params = KafkaStepTranslator.params(type, fields, location);
        } else if (type.startsWith("db.")) {
            params = DbStepTranslator.params(type, fields, location);
        } else {
            throw new StandTestException("Unknown step type '" + type + "' at " + location
                    + " (expected rest.*/kafka.send/kafka.expect/db.*)");
        }
        return new GenericStep(id, type, describe(type, params), params);
    }

    /**
     * Builds a readable step description (the Allure step subtitle), mirroring the Java DSL: REST as
     * {@code "<METHOD> <service> <path>"}, Kafka/DB as {@code "<type> <topic|datasource>"}. Package-private
     * so {@link AiScenarioParser} produces the same subtitle from the same wire parameters.
     */
    static String describe(String type, Map<String, Object> params) {
        if (type.startsWith("rest.")) {
            return params.get(YamlStepKeys.METHOD) + " " + params.get(YamlStepKeys.SERVICE) + " " + params.get(YamlStepKeys.PATH);
        }
        if (type.startsWith("kafka.")) {
            return type + " " + params.get(YamlStepKeys.TOPIC);
        }
        if (type.startsWith("grpc.")) {
            return type + " " + params.get(YamlStepKeys.TARGET) + " " + params.get(YamlStepKeys.METHOD_FULL_NAME);
        }
        return type + " " + params.get(YamlStepKeys.DATASOURCE);
    }
}
