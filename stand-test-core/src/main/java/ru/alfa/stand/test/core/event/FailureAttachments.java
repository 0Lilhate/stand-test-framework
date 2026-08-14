package ru.alfa.stand.test.core.event;

import java.util.List;
import java.util.Map;

/**
 * Opt-in carrier that lets a step failure bring reporting {@link Attachment}s with it into the report.
 *
 * <p>The run short-circuits on the first failing step (plan §8.3): an executor signals a failure either
 * by returning a failing {@code StepResult} or by throwing, and the failing step's
 * {@code StepResult}/{@code StepEvent} is what a reporting sink renders. As long as the failure is
 * <em>thrown</em>, the executor that throws has no other way to attach its evidence — a screenshot, a
 * console log — to the step that failed. This marker is that way.
 *
 * <p>An executor that wants an artefact on a failing step throws a runtime failure that implements this
 * interface (for example a UI assertion error carrying a PNG screenshot); {@code DefaultScenarioRunner}
 * reads {@link #failureAttachments()} while it records the failure and includes them in the step's
 * result and its reporting event, exactly as if the executor had returned them in the success path.
 *
 * <p>It is deliberately optional and deliberately transport-agnostic: core has no adapter, so nothing
 * but the marker and the {@link Attachment} value-type that any adapter already understands crosses this
 * boundary. An executor that does not implement it loses nothing — an empty attachment list is the
 * pre-existing behaviour.
 *
 * <h2>Empty, never null</h2>
 *
 * <p>Both methods must return an empty collection rather than null. An implementation that returns null
 * anyway does not fail the run: the runner reads this marker while recording a step that ALREADY failed,
 * so throwing there would replace the run's real reason for failing with a failure of the reporting
 * branch. The runner therefore logs a WARN naming the offending class and drops that piece of evidence —
 * a misbehaving adopter loses its screenshot, never the run its diagnosis. Do not rely on that
 * tolerance: it exists so a broken contract degrades honestly, not as a second spelling of "empty".
 */
public interface FailureAttachments {

    /**
     * The evidence the failing step wants attached to its report, or an empty list when there is none.
     *
     * @return immutable list of attachment to carry on the failing step
     */
    List<Attachment> failureAttachments();

    /**
     * The diagnostics the failing step wants folded into its report, or an empty map when there is none.
     *
     * <p>The run short-circuits on the first failing step, and {@code DefaultScenarioRunner} builds the
     * failing step's {@code diagnostics} from the thrown cause alone. A step that has more to say — how many
     * sensitive zones it masked before the screenshot, what state an element was in when an assertion failed
     * — says it here, and the runner merges it into the failing step's reportable diagnostics. Defaults to
     * nothing so that adopters that only carry attachments gain nothing they did not ask for.
     *
     * @return immutable map of diagnostic values, possibly empty
     */
    default Map<String, Object> failureDiagnostics() {
        return Map.of();
    }
}
