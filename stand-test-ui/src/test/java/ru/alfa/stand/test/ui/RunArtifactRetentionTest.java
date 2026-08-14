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
        Path aged = ageFile(tempDir.resolve(UiRunArtifacts.screenshotName()), Duration.ofDays(2));

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(aged)).as("the aged artefact must be gone").isFalse();
    }

    @Test
    @DisplayName("an artefact younger than the retention is kept")
    void freshArtefactIsKept() throws IOException {
        Path fresh = tempDir.resolve(UiRunArtifacts.screenshotName());
        Files.write(fresh, new byte[] {1, 2, 3});

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).isZero();
        assertThat(Files.exists(fresh)).as("the fresh artefact must survive").isTrue();
    }

    @Test
    @DisplayName("a mixed directory keeps the young and removes only the aged")
    void onlyAgedFilesAreRemoved() throws IOException {
        Path aged = ageFile(tempDir.resolve(UiRunArtifacts.traceName()), Duration.ofDays(3));
        Path fresh = tempDir.resolve(UiRunArtifacts.screenshotName());
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
        ageFile(tempDir.resolve(UiRunArtifacts.screenshotName()), Duration.ofDays(2));

        int deleted = retention.sweep(tempDir);

        assertThat(Files.exists(session)).as("storage-state is not an artefact and must outlive the sweep").isTrue();
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    @DisplayName("a file this SDK did not write is never deleted, however old it is — the artefacts directory may be shared")
    void foreignFileIsNeverSwept() throws IOException {
        // The property is consumer-configured. Pointed at a reports or build directory, a sweep that deleted
        // by age alone took the consumer's own files with it — silently, which is the worst way to lose data.
        Path foreign = ageFile(tempDir.resolve("quarterly-report.pdf"), Duration.ofDays(400));
        Path alsoForeign = ageFile(tempDir.resolve("screenshot-of-something-else.txt"), Duration.ofDays(400));
        Path ours = ageFile(tempDir.resolve(UiRunArtifacts.screenshotName()), Duration.ofDays(2));

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).as("only the SDK's own artefact is swept").isEqualTo(1);
        assertThat(Files.exists(foreign)).as("a foreign file must outlive the sweep").isTrue();
        assertThat(Files.exists(alsoForeign)).as("a near-miss name is still not ours: the extension is part of the shape").isTrue();
        assertThat(Files.exists(ours)).isFalse();
    }

    @Test
    @DisplayName("nothing nested is swept: the sweep reads the top level and never descends")
    void nestedTreesAreNeverEntered() throws IOException {
        Path nested = tempDir.resolve("previous-run").resolve("inner");
        Files.createDirectories(nested);
        // Named exactly like ours, aged well past the retention, and still untouched — because it is not
        // where this SDK writes. Descending bought nothing and put every neighbouring tree at risk.
        Path deepArtefact = ageFile(nested.resolve(UiRunArtifacts.screenshotName()), Duration.ofDays(90));

        int deleted = retention.sweep(tempDir);

        assertThat(deleted).isZero();
        assertThat(Files.exists(deepArtefact)).as("a nested tree is outside the sweep's reach entirely").isTrue();
    }

    @Test
    @DisplayName("every name the driver writes is a name the sweep owns — producing and recognising are one place")
    void everyProducedNameIsRecognised() {
        // The anti-drift device. Before UiRunArtifacts the driver invented its names and the retention
        // invented its rule, so neither could tell it had stopped matching the other; this test fails the
        // moment a new artefact kind is produced without being made sweepable.
        assertThat(UiRunArtifacts.isRunArtifact(UiRunArtifacts.screenshotName()))
                .as("a screenshot the driver writes must be one the retention may delete").isTrue();
        assertThat(UiRunArtifacts.isRunArtifact(UiRunArtifacts.traceName()))
                .as("a trace the driver writes must be one the retention may delete").isTrue();
        assertThat(UiRunArtifacts.isRunArtifact("account.json"))
                .as("a saved session is not an artefact — SEC-05 keeps its own lifecycle").isFalse();
        assertThat(UiRunArtifacts.isRunArtifact(null)).isFalse();
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