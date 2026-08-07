package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The run-artefact lifetime (UITG-S018, SEC-09): the sweep deletes what is older than the retention, leaves
 * the {@code storage-state} subtree — session files that are secrets and live by their own rule — entirely
 * alone, and is best-effort, so housekeeping can never rob a run of its start.
 */
class RunArtifactRetentionTest {

    private final RunArtifactRetention retention = new RunArtifactRetention(Duration.ofDays(1));

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an artefact older than the retention is deleted")
    void agedArtefactIsDeleted() throws IOException {
        Path aged = ageFile(tempDir.resolve("ui-screenshot-old.png"), Duration.ofDays(2));

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(aged)).as("the aged artefact must be gone").isFalse();
    }

    @Test
    @DisplayName("an artefact younger than the retention is kept")
    void freshArtefactIsKept() throws IOException {
        Path fresh = tempDir.resolve("ui-screenshot-new.png");
        Files.write(fresh, new byte[] {1, 2, 3});

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).isZero();
        assertThat(Files.exists(fresh)).as("the fresh artefact must survive").isTrue();
    }

    @Test
    @DisplayName("a mixed directory keeps the young and removes only the aged")
    void onlyAgedFilesAreRemoved() throws IOException {
        Path aged = ageFile(tempDir.resolve("trace.zip"), Duration.ofDays(3));
        Path fresh = tempDir.resolve("console.log");
        Files.write(fresh, new byte[] {9});

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(aged)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    @DisplayName("the storage-state subtree is never touched, however old its session files grow")
    void storageStateIsNeverSwept() throws IOException {
        Path stateDir = tempDir.resolve(StorageStateStore.DIRECTORY).resolve("dev").resolve("apps");
        Files.createDirectories(stateDir);
        Path session = ageFile(stateDir.resolve("account.json"), Duration.ofDays(30));
        ageFile(tempDir.resolve("ui-screenshot.png"), Duration.ofDays(2));

        int deleted = retention.sweep(tempDir);

        assertThat(Files.exists(session)).as("storage-state is not an artefact and must outlive the sweep").isTrue();
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing or non-directory root deletes nothing instead of failing the run")
    void absentRootSweepsNothing() {
        int deleted = retention.sweep(tempDir.resolve("does-not-exist"));

        assertThat(deleted).isZero();
    }

    @Test
    @DisplayName("storageState store directory name is the very boundary the sweep must respect")
    void storageStateNameIsTheSweepBoundary() {
        assertThat(StorageStateStore.DIRECTORY).isEqualTo("storage-state");
    }

    /**
     * Writes a file and rewinds its last-modified time, so an age can be simulated without sleeping.
     */
    private static Path ageFile(Path path, Duration age) throws IOException {
        Files.write(path, new byte[] {1});
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age.toMillis(), ChronoUnit.MILLIS)));
        return path;
    }
}