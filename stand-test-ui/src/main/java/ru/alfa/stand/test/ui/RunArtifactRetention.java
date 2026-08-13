package ru.alfa.stand.test.ui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces the run-artefact lifetime of the artefacts directory (UITG-S018, SEC-09): before a run starts
 * it deletes the failure artefacts that are older than the configured retention, so they do not pile up on
 * a shared runner or on a developer's machine.
 *
 * <p>The sweep deletes <strong>only what this SDK wrote, and only where it wrote it</strong>: regular files
 * directly under the artefacts root whose names {@link UiRunArtifacts#isRunArtifact} recognises. Both halves
 * of that are load-bearing, and both were once missing.
 *
 * <ul>
 *   <li><em>Only its own files.</em> The directory is consumer-configured
 *       ({@code stand.test.ui.artifacts.dir}). Deleting every regular file older than the retention meant
 *       that a consumer who pointed it at a shared location — a reports directory, a build output — lost
 *       unrelated files by age alone, silently. A retention that cannot name what it owns owns nothing.
 *   <li><em>Only the top level.</em> Artefacts are written flat into the root, so a single directory read
 *       finds all of them; descending gained nothing and put every nested tree under the same blanket rule.
 * </ul>
 *
 * <p>The {@code storage-state} subtree is therefore protected twice over: it is a directory, so a top-level
 * sweep of regular files never enters it, and its session files would not be recognised as artefacts anyway.
 * Those files are secrets living by their own lifecycle and must not be recycled by the artefact retention,
 * however old they age (card negative: "удаление затрагивает каталог storage-state — запрещено"). The
 * explicit test for that boundary stays, because protection that is only incidental is protection nobody
 * notices losing.
 *
 * <p>The trade this makes is deliberate and points the safe way: an artefact written under a name
 * {@link UiRunArtifacts} does not know — by a driver implemented outside this module — is never swept rather
 * than swept by accident. Leaving a stray file costs disk; deleting somebody else's costs their data.
 *
 * <p>Best-effort on the same principle as every other artefact capture (plan §17): a directory that is
 * missing or unreadable, a file that cannot be deleted — each is a WARN and never an error. A run that
 * cannot sweep its own history must start, not fail (card negative: "каталог недоступен на запись -> WARN,
 * прогон не падает"), and a throwing retention would betray exactly the reporter that already swallowed
 * the original failure.
 */
final class RunArtifactRetention {

    private static final Logger LOG = LoggerFactory.getLogger(RunArtifactRetention.class);

    private final Duration retention;

    RunArtifactRetention(Duration retention) {
        this.retention = Objects.requireNonNull(retention, "retention must not be null");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be strictly positive, but was " + retention);
        }
    }

    /**
     * Deletes this SDK's own artefacts directly under the root that are older than the retention, returning
     * how many were removed. Files it does not recognise, anything nested (including {@code storage-state})
     * and unreadable roots are left alone and logged, never thrown.
     *
     * @param artifactsDirectory the run's artefacts root
     * @return the number of files deleted
     */
    int sweep(Path artifactsDirectory) {
        if (artifactsDirectory == null || !Files.isDirectory(artifactsDirectory)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Not sweeping the artefacts directory: nothing at {} to clean", artifactsDirectory);
            }
            return 0;
        }
        Instant cutoff = Instant.now().minus(retention);
        int deleted = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(artifactsDirectory)) {
            for (Path entry : entries) {
                if (sweepable(entry, cutoff)) {
                    deleted += delete(entry);
                }
            }
        } catch (IOException failure) {
            LOG.warn("Artefact retention sweep over {} aborted: {}", artifactsDirectory, failure.toString());
        }
        if (deleted > 0) {
            LOG.info("Deleted {} artefact(s) older than {} under {}", deleted, retention, artifactsDirectory);
        }
        return deleted;
    }

    /** Whether one directory entry is an artefact of this SDK that has outlived the retention. */
    private static boolean sweepable(Path entry, Instant cutoff) {
        Path name = entry.getFileName();
        if (name == null || !UiRunArtifacts.isRunArtifact(name.toString())) {
            return false;
        }
        try {
            if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return !Files.getLastModifiedTime(entry).toInstant().isAfter(cutoff);
        } catch (IOException unreadable) {
            LOG.warn("Could not read the age of {}; leaving it alone: {}", entry, unreadable.toString());
            return false;
        }
    }

    private static int delete(Path artefact) {
        try {
            Files.deleteIfExists(artefact);
        } catch (IOException cannotDelete) {
            LOG.warn("Could not delete aged artefact {}: {}", artefact, cannotDelete.toString());
            return 0;
        }
        LOG.debug("Deleted aged artefact {}", artefact);
        return 1;
    }
}
