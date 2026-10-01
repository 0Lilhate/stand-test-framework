package ru.alfa.stand.test.eq.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only, whole-suite journal of clients created in EQ, in JSON Lines format (BR-43).
 *
 * <p>One line per seeded object, written under a file lock so parallel classes never interleave a line.
 * The file lives under the directory named by the JVM system property {@code stand.test.eq.artifacts.dir}
 * (default {@code build/stand-test/eq}) and is a <strong>maintenance artefact of the suite</strong>, not an
 * Allure attachment: it keeps changing while scenarios run in parallel and its default location is outside
 * {@code RunArtifacts.directory}, so a reporting sink would (correctly) refuse it as a file attachment.
 * Each {@code eq.seed} attaches its own immutable TEXT snapshot instead ({@link SeedLog}).
 *
 * <p>The journal deliberately records the identifiers a maintainer needs to find and clean up a client —
 * environment, {@code testRunId}, PIN, accounts, INN and time. It is never attached to a report, so the
 * NFR-05 attachment allowlist does not apply to it; the stored values are the ones issued by EQ, never
 * request/response bodies, headers, params or credentials.
 */
public final class SeedJournal {

    /** JVM system property naming the journal directory. Deliberately not an {@code application.yml} key. */
    public static final String DIRECTORY_PROPERTY = "stand.test.eq.artifacts.dir";

    /** Default journal directory when the property is unset. */
    public static final String DEFAULT_DIRECTORY = "build/stand-test/eq";

    private static final String JOURNAL_FILE = "eq-seeded.jsonl";
    private static final Object JVM_LOCK = new Object();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile SeedJournal shared;

    private final Path file;

    /** Creates a journal writing to {@code eq-seeded.jsonl} inside the given directory. */
    public SeedJournal(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        this.file = directory.resolve(JOURNAL_FILE);
    }

    /** Resolves the journal directory from a property source, for pure-function tests. */
    public static Path directory(UnaryOperator<String> source) {
        Objects.requireNonNull(source, "source must not be null");
        String configured = source.apply(DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Paths.get(DEFAULT_DIRECTORY);
        }
        return Paths.get(configured.trim());
    }

    /** Returns the JVM-wide journal, resolving its directory from the system property on first use. */
    public static SeedJournal shared() {
        SeedJournal current = shared;
        if (current == null) {
            synchronized (JVM_LOCK) {
                current = shared;
                if (current == null) {
                    current = new SeedJournal(directory(System::getProperty));
                    shared = current;
                }
            }
        }
        return current;
    }

    /** Appends one record as a single JSON line, blocking until the line is flushed and unlocked. */
    public void append(SeedRecord record) {
        Objects.requireNonNull(record, "record must not be null");
        String line = JSON.writeValueAsString(record.toJson()) + System.lineSeparator();
        synchronized (JVM_LOCK) {
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                     FileLock lock = channel.lock()) {
                    channel.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                    channel.force(false);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException("Failed to append to EQ seed journal '" + file + "'", failure);
            }
        }
    }

    /** The journal file this instance writes to, for README references and tests. */
    public Path file() {
        return file;
    }

    /**
     * One created client, rendered into the journal.
     *
     * @param environment the scenario environment
     * @param testRunId the run marker
     * @param pin the client PIN
     * @param accounts the account numbers, in creation order
     * @param inn the generated INN, or null on a backend that does not issue one
     * @param createdAt when the client was confirmed
     */
    public record SeedRecord(String environment, String testRunId, String pin, List<String> accounts, String inn,
                             Instant createdAt) {

        public SeedRecord {
            Objects.requireNonNull(environment, "environment must not be null");
            Objects.requireNonNull(testRunId, "testRunId must not be null");
            Objects.requireNonNull(pin, "pin must not be null");
            accounts = List.copyOf(accounts == null ? List.of() : accounts);
            Objects.requireNonNull(createdAt, "createdAt must not be null");
        }

        Map<String, Object> toJson() {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("environment", environment);
            object.put("testRunId", testRunId);
            object.put("pin", pin);
            object.put("accounts", accounts);
            if (inn != null) {
                object.put("inn", inn);
            }
            object.put("createdAt", createdAt.toString());
            return object;
        }
    }
}