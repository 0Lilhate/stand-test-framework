package ru.alfa.stand.test.eq.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SeedJournalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void directoryDefaultsAndHonoursTheSystemProperty() {
        assertThat(SeedJournal.directory(name -> null)).isEqualTo(Path.of(SeedJournal.DEFAULT_DIRECTORY));
        assertThat(SeedJournal.directory(name -> " /tmp/eq-out ")).isEqualTo(Path.of("/tmp/eq-out"));
    }

    @Test
    void parallelAppendsProduceOneWellFormedJsonLinePerRecord() throws Exception {
        SeedJournal journal = new SeedJournal(tempDir);
        int writers = 8;
        int perWriter = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int writer = 0; writer < writers; writer++) {
            int id = writer;
            tasks.add(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int index = 0; index < perWriter; index++) {
                    journal.append(new SeedJournal.SeedRecord("ift", "run-" + id, "T" + id + index,
                            List.of("40702810" + id + index), null, Instant.parse("2026-09-28T12:00:00Z")));
                }
            });
        }
        tasks.forEach(pool::submit);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> lines = Files.readAllLines(journal.file(), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(writers * perWriter);
        assertThat(lines).allSatisfy(line -> {
            Object parsed = JSON.readValue(line, Object.class);
            assertThat(parsed).isInstanceOf(java.util.Map.class);
            assertThat(((java.util.Map<?, ?>) parsed).get("pin").toString()).startsWith("T");
        });
    }
}