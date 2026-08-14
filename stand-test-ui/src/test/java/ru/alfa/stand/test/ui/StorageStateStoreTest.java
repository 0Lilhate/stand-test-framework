package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageStateStoreTest {

    private static final String ENVIRONMENT = "ift";

    @Test
    @DisplayName("the session file is keyed by account, not by suite — two accounts of one application get two files")
    void storageStateIsKeyedByAccountNotBySuite(@TempDir Path artifacts) {
        StorageStateStore store = new StorageStateStore(artifacts);

        Path first = store.pathFor(ENVIRONMENT, "client-portal", "portal-client-1");
        Path second = store.pathFor(ENVIRONMENT, "client-portal", "portal-client-2");
        Path otherApplication = store.pathFor(ENVIRONMENT, "back-office", "portal-client-1");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).isNotEqualTo(otherApplication);
        assertThat(first.getFileName().toString()).isEqualTo("portal-client-1.json");
        assertThat(first.getParent().getFileName().toString()).isEqualTo("client-portal");
        assertThat(first.getParent().getParent().getFileName().toString()).isEqualTo(ENVIRONMENT);
        assertThat(first.getParent().getParent().getParent().getFileName().toString()).isEqualTo(StorageStateStore.DIRECTORY);
        assertThat(artifacts.relativize(first).toString()).startsWith(StorageStateStore.DIRECTORY);
    }

    @Test
    @DisplayName("the environment is part of the path: the pool leases the same account id independently per environment, so two stands must not share one session file")
    void storageStateIsKeyedByEnvironmentToo(@TempDir Path artifacts) {
        StorageStateStore store = new StorageStateStore(artifacts);

        Path ift = store.pathFor("ift", "client-portal", "portal-client-1");
        Path dev = store.pathFor("dev", "client-portal", "portal-client-1");

        assertThat(ift).isNotEqualTo(dev);
    }

    @Test
    @DisplayName("an account id or alias carrying path characters cannot escape the artefacts directory")
    void pathComponentsAreSanitised(@TempDir Path artifacts) {
        StorageStateStore store = new StorageStateStore(artifacts);
        Path root = artifacts.resolve(StorageStateStore.DIRECTORY).resolve(ENVIRONMENT);

        // Compared as text: AssertJ's Path.startsWith resolves the real path, and these files do not exist.
        assertThat(store.pathFor(ENVIRONMENT, "../../etc", "../../../passwd").normalize().toString()).startsWith(root.toString());
        // A component that reduces to nothing but dots would step out of the directory on its own.
        assertThat(store.pathFor(ENVIRONMENT, "..", "..").normalize()).isEqualTo(root.resolve("_").resolve("_.json"));
        assertThat(store.pathFor(ENVIRONMENT, ".", " ").normalize()).isEqualTo(root.resolve("_").resolve("_.json"));
        // Dots inside a real id stay: 'portal.client.1' is an ordinary account name.
        assertThat(store.pathFor(ENVIRONMENT, "client-portal", "portal.client.1").getFileName().toString()).isEqualTo("portal.client.1.json");
    }

    @Test
    @DisplayName("a missing file is simply absent; a well-formed one is usable")
    void usableRecognisesAWellFormedState(@TempDir Path artifacts) throws IOException {
        StorageStateStore store = new StorageStateStore(artifacts);
        Path state = store.pathFor(ENVIRONMENT, "client-portal", "portal-client-1");

        assertThat(store.usable(state)).isFalse();

        store.prepareFor(state);
        Files.writeString(state, "{\"cookies\":[],\"origins\":[]}", StandardCharsets.UTF_8);

        assertThat(store.usable(state)).isTrue();
    }

    @Test
    @DisplayName("a malformed session file is deleted rather than retried: a cache nobody can read must cost one sign-in, not every run")
    void malformedStateIsDiscarded(@TempDir Path artifacts) throws IOException {
        StorageStateStore store = new StorageStateStore(artifacts);
        Path state = store.pathFor(ENVIRONMENT, "client-portal", "portal-client-1");
        store.prepareFor(state);
        Files.writeString(state, "not json at all", StandardCharsets.UTF_8);

        assertThat(store.usable(state)).isFalse();
        assertThat(state).doesNotExist();
    }

    @Test
    @DisplayName("an empty session file is discarded too — a half-written state is not a session")
    void emptyStateIsDiscarded(@TempDir Path artifacts) throws IOException {
        StorageStateStore store = new StorageStateStore(artifacts);
        Path state = store.pathFor(ENVIRONMENT, "client-portal", "portal-client-1");
        store.prepareFor(state);
        Files.writeString(state, "   ", StandardCharsets.UTF_8);

        assertThat(store.usable(state)).isFalse();
        assertThat(state).doesNotExist();
    }

    @Test
    @DisplayName("deleting a file that is not there is not an error: the store is a cache, and a cache miss is normal")
    void deleteIsQuiet(@TempDir Path artifacts) {
        StorageStateStore store = new StorageStateStore(artifacts);

        store.delete(store.pathFor(ENVIRONMENT, "client-portal", "never-existed"));

        assertThat(store.pathFor(ENVIRONMENT, "client-portal", "never-existed")).doesNotExist();
    }
}
