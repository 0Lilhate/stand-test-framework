package ru.alfa.stand.test.core.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the SDK copies a reportable diagnostics map into an immutable value type.
 *
 * <p>{@code Map.copyOf} is the obvious spelling and the wrong one here, for two reasons that both show up
 * only in a report:
 *
 * <ul>
 *   <li><strong>It loses the order.</strong> A diagnostics map is assembled deliberately — the await
 *   engine writes {@code await}, {@code timeout}, {@code pollInterval}, {@code attempts}, {@code elapsed},
 *   {@code lastValue}, and an adapter appends its own keys after them — and that order is what a reader
 *   sees as key/value rows. {@code Map.copyOf} returns a hash-ordered map, so seven carefully sequenced
 *   entries arrive shuffled.</li>
 *   <li><strong>It throws on a null VALUE.</strong> "The last value observed was null" is an ordinary
 *   thing for a step to report, and a step that says it would be reclassified BROKEN by the very act of
 *   describing itself — or, worse, would replace an already-thrown failure with a
 *   {@link NullPointerException} from the reporting branch while the runner records it.</li>
 * </ul>
 *
 * <p>So the copy is a {@link LinkedHashMap} behind {@link Collections#unmodifiableMap}: order preserved,
 * null values carried, and still a defensive copy the caller cannot mutate afterwards.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /**
     * Returns an ordered, immutable, defensive copy of a diagnostics map.
     *
     * @param diagnostics the map to copy; null and empty alike yield an empty map
     * @return an unmodifiable copy preserving iteration order, tolerating null values
     */
    public static Map<String, Object> immutable(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
    }
}
