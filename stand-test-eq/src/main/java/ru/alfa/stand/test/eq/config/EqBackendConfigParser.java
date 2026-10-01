package ru.alfa.stand.test.eq.config;

import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.environment.SectionEntry;
import ru.alfa.stand.test.core.environment.SecretReferences;
import ru.alfa.stand.test.core.exception.StandTestException;

/** Parses only the selected backend's opaque registry entry. */
public final class EqBackendConfigParser {

    private static final Set<String> SHOWCASES_FIELDS = Set.of("kind", "service", "path", "visibility", "defaults");

    private EqBackendConfigParser() {
    }

    /** Parses only the selected entry; unrelated environments remain unresolved. */
    public static EqBackendConfig parse(String environment, SectionEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        Object kind = entry.fields().get("kind");
        if ("showcases".equals(kind)) {
            return parseShowcases(environment, entry);
        }
        if ("gateway".equals(kind)) {
            return GatewayConfigParser.parse(environment, entry);
        }
        throw invalid(location(environment, entry), "kind", "expected showcases or gateway");
    }

    /** Validates the showcases entry without touching other backend aliases or environments. */
    public static ShowcasesBackendConfig parseShowcases(String environment, SectionEntry entry) {
        if (environment == null || environment.isBlank()) {
            throw new IllegalArgumentException("environment must not be blank");
        }
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        String location = location(environment, entry);
        Map<String, Object> fields = entry.fields();
        for (String field : fields.keySet()) {
            if (!SHOWCASES_FIELDS.contains(field)) {
                throw invalid(location, field, "unknown field");
            }
        }
        String kind = requiredString(fields, "kind", location);
        if (!"showcases".equals(kind)) {
            throw invalid(location, "kind", "expected showcases");
        }
        String service = requiredString(fields, "service", location);
        if (service.contains("://") || service.contains("/") || service.contains("${")) {
            throw invalid(location, "service", "must be a logical service alias");
        }
        String path = requiredString(fields, "path", location);
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            throw invalid(location, "path", "must be an absolute path, not a URL");
        }
        EqConfigReader reader = new EqConfigReader(location);
        VisibilityConfig visibility = VisibilityConfigParser.parse(reader, fields.get("visibility"));
        EqDefaults defaults = GatewayConfigParser.defaults(reader, fields.get("defaults"));
        return new ShowcasesBackendConfig(entry.alias(), service, path, visibility, defaults);
    }

    private static String requiredString(Map<String, Object> fields, String field, String location) {
        Object value = fields.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(location, field, "must be a non-blank string");
        }
        SecretReferences.rejectLiteralMarkerInValue(text, field, location);
        return text;
    }

    private static StandTestException invalid(String location, String field, String reason) {
        return new StandTestException("Invalid " + location + ", field '" + field + "': " + reason);
    }

    static String location(String environment, SectionEntry entry) {
        return "environment '" + environment + "', eq-backends.'" + entry.alias() + "'";
    }
}
