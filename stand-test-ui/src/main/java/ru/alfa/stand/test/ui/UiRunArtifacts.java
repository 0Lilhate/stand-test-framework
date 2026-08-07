package ru.alfa.stand.test.ui;

import java.util.UUID;

/**
 * The names a run's failure artefacts are written under, and the only place that recognises them again.
 *
 * <p>It exists because two parties must agree and had no way to: the driver writes the screenshot and the
 * trace, and {@link RunArtifactRetention} decides which files it may delete. While the sweep deleted every
 * regular file older than the retention, that agreement was not needed — and that was the defect. The
 * artefacts directory is consumer-configured ({@code stand.test.ui.artifacts.dir}); pointed at a shared
 * location it would take unrelated files with it, silently and by age alone. A sweep must remove what this
 * SDK wrote, not what it found.
 *
 * <p>So producing a name and recognising one are the same object. A new kind of artefact adds a factory here
 * and is swept by that very act; it cannot be added on one side only, which is the drift that a pair of
 * hand-copied string prefixes would have invited.
 *
 * <p><strong>For drivers outside this module:</strong> {@link UiDriver} is a public seam, so an artefact you
 * write is swept only if you name it through this class. One named otherwise is not deleted — it is left for
 * whoever owns it, which is the safe direction of the trade.
 *
 * <p>The random component is not decoration: several runs of one JVM can fail at the same instant (the module
 * runs test classes concurrently), and a timestamp would let the second capture overwrite the first — one
 * failure's evidence lost without an error.
 */
public final class UiRunArtifacts {

    /** The failure screenshot: {@code screenshot-<uuid>.png}. */
    private static final String SCREENSHOT_PREFIX = "screenshot-";

    /** The failure trace: {@code trace-<uuid>.zip}. */
    private static final String TRACE_PREFIX = "trace-";

    private UiRunArtifacts() {
    }

    /**
     * A fresh file name for a failure screenshot.
     *
     * @return a name unique within the artefacts directory
     */
    public static String screenshotName() {
        return SCREENSHOT_PREFIX + UUID.randomUUID() + ".png";
    }

    /**
     * A fresh file name for a failure trace.
     *
     * @return a name unique within the artefacts directory
     */
    public static String traceName() {
        return TRACE_PREFIX + UUID.randomUUID() + ".zip";
    }

    /**
     * Whether a file name is one this SDK wrote as a run artefact, and may therefore be swept.
     *
     * @param fileName the bare file name, without a directory
     * @return true when the retention owns the file
     */
    public static boolean isRunArtifact(String fileName) {
        if (fileName == null) {
            return false;
        }
        return (fileName.startsWith(SCREENSHOT_PREFIX) && fileName.endsWith(".png"))
                || (fileName.startsWith(TRACE_PREFIX) && fileName.endsWith(".zip"));
    }
}
