package ru.alfa.stand.test.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Small helpers that read and coerce the loaded YAML tree ({@code Map}/{@code List}/scalar) into the
 * exact Java types the adapter executors expect, raising {@link StandTestException} with a location on any
 * malformed input. All failures are config-class (plan §8.3) and fail-closed: unknown or ill-typed input
 * is rejected, never silently ignored.
 *
 * <p>It also owns the assertion-matcher vocabulary, in one place. That vocabulary used to be spelled out
 * five times across two classes — the surface→wire map, the allowed-key set, the normalizer's own copy,
 * a hand-listed "any matcher but equals" test and the text of an error message — so adding a matcher meant
 * five edits and any missed one was a silent divergence between what the surface accepts and what it
 * executes.
 */
final class SurfaceValues {

    /**
     * Surface matcher name → the wire matcher constant the adapters read, in the order a message lists
     * them. Ordered on purpose: a {@code Map.of} here made the "but found [...]" of a multi-matcher
     * rejection come out in a different order on different JVMs.
     */
    private static final Map<String, String> ASSERT_MATCHERS;

    /** The keys an assertion item may carry: {@code path} plus exactly one matcher. */
    static final Set<String> ASSERT_ITEM_KEYS;

    /** The matcher names as a message renders them, e.g. {@code equals/contains/exists/notNull/matches}. */
    private static final String MATCHER_LIST;

    /** The one matcher every adapter can execute; the rest are refused on the equals-only surfaces. */
    private static final String EQUALS = "equals";

    static {
        Map<String, String> matchers = new LinkedHashMap<>();
        matchers.put(EQUALS, "EQUALS");
        matchers.put("contains", "CONTAINS");
        matchers.put("exists", "EXISTS");
        matchers.put("notNull", "NOT_NULL");
        matchers.put("matches", "MATCHES");
        ASSERT_MATCHERS = Map.copyOf(matchers);
        MATCHER_LIST = String.join("/", matchers.keySet());
        ASSERT_ITEM_KEYS = Stream.concat(Stream.of("path"), matchers.keySet().stream()).collect(Collectors.toUnmodifiableSet());
    }

    private SurfaceValues() {
    }

    /**
     * Coerces a loaded YAML mapping to {@code Map<String, Object>}, keeping document order.
     *
     * <p>Deliberately a loop rather than {@code Collectors.toMap}: a collector calls {@code Map.merge},
     * which throws {@link NullPointerException} on a null VALUE whatever map factory it is given — and a
     * null value is legal here. A document may write {@code key:} with nothing after it, and that is
     * rejected later, per field, with a message naming the field. Collecting would turn that diagnosis
     * into an NPE thrown from inside the JDK.
     */
    static Map<String, Object> asMap(Object value, String location) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new StandTestException("Expected a mapping at " + location + ", but found " + describe(value));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /**
     * Coerces a loaded YAML sequence to {@code List<Object>}.
     *
     * <p>{@code List.copyOf} would be the shorter spelling and, like {@link #asMap}, the wrong one: it
     * rejects a null ELEMENT, and a document may write a bare {@code -} with nothing after it. That is an
     * error, but it belongs to the element's own check, which names the position — not to an NPE here.
     */
    static List<Object> asList(Object value, String location) {
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("Expected a list at " + location + ", but found " + describe(value));
        }
        return new ArrayList<>(list);
    }

    static void checkKnownKeys(Map<String, Object> fields, Set<String> known, String location) {
        fields.keySet().stream()
                .filter(key -> !known.contains(key))
                .findFirst()
                .ifPresent(key -> {
                    throw new StandTestException("Unknown field '" + key + "' at " + location + " (allowed: " + known + ")");
                });
    }

    static String requireString(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("Field '" + key + "' at " + location + " must be a non-blank string");
        }
        return text;
    }

    static String optionalString(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new StandTestException("Field '" + key + "' at " + location + " must be a string");
        }
        return text;
    }

    static boolean boolFlag(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new StandTestException("Field '" + key + "' at " + location + " must be a boolean");
        }
        return flag;
    }

    static Integer requireInteger(Map<String, Object> fields, String key, String location) {
        Object value = fields.get(key);
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException notANumber) {
                throw new StandTestException("Field '" + key + "' at " + location + " must be an integer, but was '" + text + "'");
            }
        }
        throw new StandTestException("Field '" + key + "' at " + location + " must be an integer");
    }

    static Map<String, String> stringMap(Object value, String location) {
        return mapOfNonNullValues(value, location, entry -> String.valueOf(entry.getValue()));
    }

    static Map<String, Object> objectMap(Object value, String location) {
        return mapOfNonNullValues(value, location, Map.Entry::getValue);
    }

    /**
     * Reads a mapping whose values must all be present, applying {@code valueMapper} to each. Safe to
     * collect — unlike {@link #asMap}, a null value is refused here first, and refused by name.
     */
    private static <V> Map<String, V> mapOfNonNullValues(Object value, String location, Function<Map.Entry<String, Object>, V> valueMapper) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> raw = asMap(value, location);
        raw.forEach((key, entryValue) -> {
            if (entryValue == null) {
                throw new StandTestException("Value for '" + key + "' at " + location + " must not be null");
            }
        });
        // unmodifiableMap over a LinkedHashMap rather than Map.copyOf: the copy must keep document order,
        // which Map.copyOf does not, while staying immutable, which the collector's LinkedHashMap is not.
        return Collections.unmodifiableMap(raw.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, valueMapper, (first, second) -> second, LinkedHashMap::new)));
    }

    static Long durationMillis(Object value, String location) {
        long millis;
        if (value instanceof Number number) {
            if (!(number instanceof Integer) && !(number instanceof Long)) {
                throw new StandTestException("Duration at " + location + " must be a whole number of milliseconds, not a floating-point value: " + value);
            }
            millis = number.longValue();
        } else if (value instanceof String text) {
            millis = parseDuration(text.trim(), location);
        } else {
            throw new StandTestException("Duration at " + location + " must be a number of ms or '<n>ms'/'<n>s'/'<n>m'");
        }
        if (millis <= 0) {
            throw new StandTestException("Duration at " + location + " must be positive, but was " + millis + "ms");
        }
        return millis;
    }

    private static long parseDuration(String text, String location) {
        try {
            if (text.endsWith("ms")) {
                return Long.parseLong(text.substring(0, text.length() - 2).trim());
            }
            if (text.endsWith("s")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 1000L;
            }
            if (text.endsWith("m")) {
                return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 60_000L;
            }
            return Long.parseLong(text);
        } catch (NumberFormatException notANumber) {
            throw new StandTestException("Invalid duration '" + text + "' at " + location + " (use '<n>ms', '<n>s', '<n>m' or a number of ms)");
        }
    }

    static List<Map<String, Object>> assertions(Object value, String location) {
        return requireMapping(value, location, "'assert' at " + location + " must be a mapping of {\"jsonPath\": expectedValue}")
                .entrySet().stream()
                .map(entry -> {
                    if (entry.getValue() == null) {
                        throw new StandTestException("Assertion for '" + entry.getKey() + "' at " + location + " must have a non-null expected value");
                    }
                    return Map.of(YamlStepKeys.JSON_PATH, entry.getKey(), YamlStepKeys.EXPECTED_VALUE, entry.getValue());
                })
                .toList();
    }

    /**
     * Reads a REST {@code assert} block in either surface form: the map shorthand
     * {@code {"$.path": expectedValue}} (equals-only, identical to {@link #assertions}) or the list form
     * {@code [{path: "$.x", contains: "v"}, ...]} where each item declares exactly one matcher besides
     * {@code path}. Only the REST family and {@code grpc.unary} accept the list form — kafka stays on the
     * map shorthand because its executor runs equals only.
     */
    static List<Map<String, Object>> assertionsWithMatchers(Object value, String location) {
        if (value instanceof Map<?, ?>) {
            return assertions(value, location);
        }
        if (!(value instanceof List<?> items)) {
            throw new StandTestException("'assert' at " + location + " must be a {\"jsonPath\": expectedValue} mapping or a list of {path, <matcher>} items, but found " + describe(value));
        }
        return IntStream.range(0, items.size())
                .mapToObj(index -> matcherAssertion(items.get(index), location + "[" + index + "]"))
                .toList();
    }

    private static Map<String, Object> matcherAssertion(Object node, String itemLoc) {
        Map<String, Object> item = asMap(node, itemLoc);
        checkKnownKeys(item, ASSERT_ITEM_KEYS, itemLoc);
        String path = requireString(item, "path", itemLoc);
        List<String> present = ASSERT_MATCHERS.keySet().stream()
                .filter(item::containsKey)
                .toList();
        if (present.size() != 1) {
            throw new StandTestException("Assertion at " + itemLoc + " must declare exactly one matcher besides 'path' (" + MATCHER_LIST + "), but found " + present);
        }
        String surfaceMatcher = present.get(0);
        Object expected = item.get(surfaceMatcher);
        if (expected == null) {
            throw new StandTestException("Assertion at " + itemLoc + " must have a non-null '" + surfaceMatcher + "' value");
        }
        return EQUALS.equals(surfaceMatcher)
                ? Map.of(YamlStepKeys.JSON_PATH, path, YamlStepKeys.EXPECTED_VALUE, expected)
                : Map.of(YamlStepKeys.JSON_PATH, path, YamlStepKeys.EXPECTED_VALUE, expected, YamlStepKeys.MATCHER, ASSERT_MATCHERS.get(surfaceMatcher));
    }

    /**
     * Whether an assertion item declares a matcher the equals-only surfaces ({@code kafka.expect}) cannot
     * execute. Derived from the one vocabulary, so a sixth matcher is refused there the day it is added.
     */
    static boolean declaresNonEqualsMatcher(Map<String, Object> item) {
        return ASSERT_MATCHERS.keySet().stream()
                .filter(matcher -> !EQUALS.equals(matcher))
                .anyMatch(item::containsKey);
    }

    static List<Map<String, Object>> captures(Object value, String valueKey, String location) {
        return requireMapping(value, location, "'capture' at " + location + " must be a mapping of {variableName: " + valueKey + "}")
                .entrySet().stream()
                .map(entry -> {
                    if (!(entry.getValue() instanceof String selector) || selector.isBlank()) {
                        throw new StandTestException("Capture '" + entry.getKey() + "' at " + location + " must map to a non-blank " + valueKey);
                    }
                    return Map.<String, Object>of(YamlStepKeys.VARIABLE_NAME, entry.getKey(), valueKey, selector);
                })
                .toList();
    }

    private static Map<String, Object> requireMapping(Object value, String location, String expectation) {
        if (!(value instanceof Map<?, ?>)) {
            throw new StandTestException(expectation + ", but found " + describe(value));
        }
        return asMap(value, location);
    }

    private static String describe(Object value) {
        return (value == null) ? "nothing" : value.getClass().getSimpleName();
    }

    /**
     * Writes an inline value (under {@code inlineKey}) or a classpath-resource reference (under
     * {@code resourceKey}) from the two mutually exclusive surface fields, e.g. {@code body}/{@code bodyResource}
     * or {@code sql}/{@code sqlResource}.
     */
    static void putInlineOrResource(Map<String, Object> params, Map<String, Object> fields,
            String inlineField, String resourceField, String inlineKey, String resourceKey,
            boolean required, String location) {
        String inline = optionalString(fields, inlineField, location);
        String resource = optionalString(fields, resourceField, location);
        if (inline != null && resource != null) {
            throw new StandTestException("Specify only one of '" + inlineField + "'/'" + resourceField + "' at " + location);
        }
        if (inline != null) {
            params.put(inlineKey, inline);
        } else if (resource != null) {
            params.put(resourceKey, resource);
        } else if (required) {
            throw new StandTestException("One of '" + inlineField + "'/'" + resourceField + "' is required at " + location);
        }
    }

    /**
     * Writes a boolean wire flag only when the surface declared it.
     *
     * <p>The absence of the flag must stay distinguishable from an explicit {@code false}: absent means
     * "use the default" (inject when the service/topic/target declares a correlation carrier), while
     * {@code false} is an opt-out the author asked for. Writing a default here would erase that
     * difference for all three adapters at once.
     */
    static void putOptionalFlag(Map<String, Object> params, Map<String, Object> fields, String surfaceField, String wireKey, String location) {
        if (fields.containsKey(surfaceField)) {
            params.put(wireKey, boolFlag(fields, surfaceField, location));
        }
    }

    /** Writes a bounded duration wire key only when the surface declared it. */
    static void putOptionalDuration(Map<String, Object> params, Map<String, Object> fields, String surfaceField, String wireKey, String location) {
        if (fields.containsKey(surfaceField)) {
            params.put(wireKey, durationMillis(fields.get(surfaceField), location + "." + surfaceField));
        }
    }

    /** Writes the assertion list, empty when the surface declared none. {@code withMatchers} picks the surface form. */
    static void putAssertions(Map<String, Object> params, Map<String, Object> fields, boolean withMatchers, String location) {
        Object declared = fields.get("assert");
        params.put(YamlStepKeys.ASSERTIONS, !fields.containsKey("assert") ? List.of()
                : withMatchers ? assertionsWithMatchers(declared, location + ".assert") : assertions(declared, location + ".assert"));
    }

    /** Writes the capture list, empty when the surface declared none. {@code valueKey} is the selector kind (JSONPath or column). */
    static void putCaptures(Map<String, Object> params, Map<String, Object> fields, String valueKey, String location) {
        params.put(YamlStepKeys.CAPTURES, fields.containsKey("capture")
                ? captures(fields.get("capture"), valueKey, location + ".capture") : List.of());
    }
}
