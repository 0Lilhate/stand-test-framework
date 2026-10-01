package ru.alfa.stand.test.config;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.alfa.stand.test.core.environment.EnvironmentConfigFormat;
import ru.alfa.stand.test.core.environment.EnvironmentSection;
import ru.alfa.stand.test.core.environment.SectionEntry;
import ru.alfa.stand.test.core.exception.StandTestException;

/** Maps explicitly supported extension sections without interpreting their fields. */
final class EnvironmentSections {

    private static final String BACKENDS = "eq-backends";

    private EnvironmentSections() {
    }

    static Map<String, EnvironmentSection> fromFile(Object value, String environmentLocation, int version) {
        if (value == null) {
            return Map.of();
        }
        String location = environmentLocation + "." + BACKENDS;
        EnvironmentConfigFormat.requireSectionSupported(version, BACKENDS, EnvironmentConfigFormat.SECTIONS_SINCE_VERSION, location);
        if (!(value instanceof Map<?, ?> rawEntries)) {
            throw new StandTestException("Expected a mapping at " + location);
        }
        Map<String, SectionEntry> entries = new LinkedHashMap<>();
        rawEntries.forEach((key, raw) -> {
            String alias = String.valueOf(key);
            if (!(raw instanceof Map<?, ?> fields)) {
                throw new StandTestException("Expected a mapping at " + location + "." + alias);
            }
            Map<String, Object> namedFields = new LinkedHashMap<>();
            fields.forEach((field, fieldValue) -> namedFields.put(String.valueOf(field), fieldValue));
            entries.put(alias, new SectionEntry(alias, FilePlaceholders.resolveFields(namedFields, location + "." + alias)));
        });
        return Map.of(BACKENDS, new EnvironmentSection(BACKENDS, entries));
    }
}
