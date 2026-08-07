package ru.alfa.stand.test.ui;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces the run-artefact lifetime of the artefacts directory (UITG-S018, SEC-09): before a run starts
 * it deletes the failure artefacts that are older than the configured retention, so they do not pile up on
 * a shared runner or on a developer's machine.
 *
 * <p>The sweep is deliberately <em>shallow about semantics and flat about structure</em>: failure artefacts
 * (screenshot, trace) are plain files written directly under the artefacts root, so the sweep deletes
 * regular files by their last-modified time and nothing else. It never touches the {@code storage-state}
 * subtree — those session files are secrets that live by their own rule and must not be recycled by the
 * artefact retention, however old they age (card negative: "удаление затрагивает каталог storage-state —
 * запрещено").
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
     * Deletes the artefacts under the root that are older than the retention, returning how many were
     * removed. The {@code storage-state} subtree is never entered. Missing or unwritable roots delete
     * nothing and are logged, never thrown.
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
        AtomicInteger deleted = new AtomicInteger();
        try {
            Files.walkFileTree(artifactsDirectory, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
                    new Sweeper(artifactsDirectory, cutoff, deleted));
        } catch (IOException failure) {
            // A sweep that throws on the way is still partially done; report what it did reach and move on —
            // the run must start regardless of the housekeeping around it.
            LOG.warn("Artefact retention sweep over {} aborted: {}", artifactsDirectory, failure.toString());
        }
        int count = deleted.get();
        if (count > 0) {
            LOG.info("Deleted {} artefact(s) older than {} under {}", count, retention, artifactsDirectory);
        }
        return count;
    }

    private final class Sweeper extends SimpleFileVisitor<Path> {

        private static final String STORAGE_STATE = StorageStateStore.DIRECTORY;

        private final Path root;

        private final Instant cutoff;

        private final AtomicInteger deleted;

        private Sweeper(Path root, Instant cutoff, AtomicInteger deleted) {
            this.root = root;
            this.cutoff = cutoff;
            this.deleted = deleted;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            // The storage-state subtree is not an artefact and never subject to the retention: it holds
            // session cookies that live by their own lifecycle. Skipping the whole subtree (without visiting
            // it) is also the only reason the walk stays flat about secrets — not one of its files is ever
            // named or aged by the retention.
            Path relative = root.relativize(dir);
            if (relative.getNameCount() >= 1 && STORAGE_STATE.equals(relative.getName(0).toString())) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            Instant modified = attrs.lastModifiedTime().toInstant();
            if (modified.isAfter(cutoff)) {
                return FileVisitResult.CONTINUE;
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException cannotDelete) {
                // Best-effort identical to the capture side: one artefact that refuses to go must not rob
                // the run of a valid start, and the next sweep will retry it.
                LOG.warn("Could not delete aged artefact {}: {}", file, cannotDelete.toString());
                return FileVisitResult.CONTINUE;
            }
            deleted.incrementAndGet();
            LOG.debug("Deleted aged artefact {}", file);
            return FileVisitResult.CONTINUE;
        }
    }
}