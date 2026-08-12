package ru.alfa.stand.test.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.alfa.stand.test.core.event.FailureAttachments;

/**
 * Assembles the diagnostics a UI failure carries into the report.
 *
 * <p>The UI executor catches every failure of a step and re-throws it wrapped, so that the artefacts it
 * captured at that moment travel with it. Wrapping is where diagnostics get lost: a
 * {@code ui.expectEventually} that timed out already threw a
 * {@link ru.alfa.stand.test.core.exception.DiagnosticAssertionError} carrying the await's attempts,
 * elapsed time and last observed value, and the wrapper is what the runner actually reads. So the
 * wrapper republishes what its cause was carrying, then adds its own.
 *
 * <p>The wrapper's own keys are written last and win a collision, because they describe the capture that
 * just happened rather than the expectation that failed before it.
 */
final class UiFailureDiagnostics {

    private UiFailureDiagnostics() {
    }

    /**
     * Merges the diagnostics of a wrapped cause with the wrapper's masked-zone count.
     *
     * @param cause the wrapped failure, possibly null and possibly carrying nothing
     * @param maskedZonesKey the reportable key for the masked-zone count
     * @param maskedZones how many sensitive zones were painted over before the screenshot; 0 omits the key
     * @return an ordered, possibly empty diagnostics map
     */
    static Map<String, Object> merge(Throwable cause, String maskedZonesKey, int maskedZones) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (cause instanceof FailureAttachments carrier) {
            Map<String, Object> inherited = carrier.failureDiagnostics();
            if (inherited != null) {
                merged.putAll(inherited);
            }
        }
        if (maskedZones != 0) {
            merged.put(maskedZonesKey, maskedZones);
        }
        return merged;
    }
}
