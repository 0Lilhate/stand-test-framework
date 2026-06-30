package ru.alfa.stand.test.allure.lifecycle;

import java.util.List;
import java.util.Map;

/**
 * Thin seam over the Allure lifecycle.
 *
 * <p>The publisher and mappers depend on this interface, never on {@code io.qameta.allure.*} statics, so
 * the whole mapping is unit-testable against an in-memory fake without an Allure runtime and without
 * coupling to JUnit/Allure extension ordering (plan §9). {@link DefaultAllureLifecycleFacade} is the
 * production implementation; a test double records the calls.
 *
 * <p>All methods are best-effort by contract: an implementation must never throw in a way that changes a
 * test outcome — reporting is a side-channel (plan §17). The publisher additionally guards every call.
 */
public interface AllureLifecycleFacade {

    /**
     * Starts a step under the current Allure context.
     *
     * @param uuid the unique step id used to address the step in later calls
     * @param name the step name shown in the report
     */
    void startStep(String uuid, String name);

    /**
     * Updates a previously started step with its outcome and parameters.
     *
     * @param uuid the step id from {@link #startStep(String, String)}
     * @param status the step status
     * @param statusMessage an optional status message (may be null)
     * @param statusTrace an optional status trace (may be null)
     * @param parameters an ordered map of step parameters (already masked)
     */
    void updateStep(String uuid, AllureStatus status, String statusMessage, String statusTrace, Map<String, String> parameters);

    /**
     * Attaches textual evidence to the current step.
     *
     * @param name the attachment name
     * @param type the media type (for example {@code application/json})
     * @param fileExtension the file extension without a leading dot (for example {@code json})
     * @param content the textual content
     */
    void addAttachment(String name, String type, String fileExtension, String content);

    /**
     * Stops a previously started step.
     *
     * @param uuid the step id from {@link #startStep(String, String)}
     */
    void stopStep(String uuid);

    /**
     * Applies labels and parameters to the current Allure test case (the one opened by the JUnit/Allure
     * integration). A no-op at the Allure level if no test case is active.
     *
     * @param labels the labels to add (for example {@code tag} labels)
     * @param parameters an ordered map of test-case parameters (already masked)
     */
    void updateTestCase(List<AllureLabel> labels, Map<String, String> parameters);
}
