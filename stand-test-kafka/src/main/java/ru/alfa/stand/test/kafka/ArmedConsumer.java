package ru.alfa.stand.test.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * A run-scoped Kafka consumer, pre-armed to the end of its topic and shared by all {@code kafka.expect}
 * steps on that topic within a scenario run (plan §8.7).
 *
 * <p>It owns the {@code start-from-now} positioning ({@code assign}/{@code seekToEnd}, done in
 * {@link #arm()} during the runner's prepare phase, before any step triggers an effect) and the
 * consume-and-advance selection: polled records are buffered until selected, and {@link #pollAndSelect}
 * returns the first buffered record that passes the selection predicate, marking it consumed and
 * removing it from the buffer (compaction — {@code selectedKeys} still prevents a re-polled duplicate
 * from re-entering). Because the buffer (not the raw consumer position) tracks what has been selected,
 * a later expect can still pick a lower-offset message a discriminator skipped earlier — so
 * out-of-order, same-correlationId streams disambiguate correctly.
 *
 * <p><strong>Foreign-record eviction (busy shared topics).</strong> A polled record that carries a
 * correlation id in the topic's HEADER carrier that is NOT this run's SDK-owned id belongs to another run
 * (correlation ids are unique per run) and can never be selected by any expect in this run, so it is
 * counted (in {@code messagesSeen}) but NOT buffered. This keeps the retained buffer to records that could
 * still be this run's, so sustained foreign traffic on a shared IFT topic cannot balloon memory or trip
 * the bound. A record with NO correlation header is retained (it could be a key-selected message the
 * system under test did not echo the id onto). The retained buffer is still bounded by
 * {@link #MAX_BUFFERED}: breaching it is an immediate infrastructure error rather than a slow timeout. It
 * is single-threaded: a run is driven on one thread, consistent with {@code KafkaConsumer} not being
 * thread-safe.
 */
final class ArmedConsumer implements AutoCloseable {

    /** Upper bound of buffered records that could be this run's; breaching it fails fast instead of growing memory. */
    static final int MAX_BUFFERED = 10_000;

    /** Bounded ring of the most recently polled records (foreign or not), kept only for timeout diagnostics. */
    private static final int RECENT_SEEN_CAP = 64;

    private final Consumer<String, String> consumer;
    private final String topicAlias;
    private final String realTopic;
    private final String correlationHeaderName;
    private final String runCorrelationId;
    private final List<ConsumerRecord<String, String>> buffer = new ArrayList<>();
    private final Deque<ConsumerRecord<String, String>> recentSeen = new ArrayDeque<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private List<TopicPartition> partitions = List.of();
    private int seenCount;

    /**
     * @param consumer the run-scoped consumer
     * @param topicAlias the logical topic alias
     * @param realTopic the resolved physical topic
     * @param correlationHeaderName the topic's HEADER correlation carrier name, or {@code null} when the
     *     topic declares none (then no foreign-record eviction is applied)
     * @param runCorrelationId this run's SDK-owned correlation id, used to evict other runs' records
     */
    ArmedConsumer(Consumer<String, String> consumer, String topicAlias, String realTopic, String correlationHeaderName, String runCorrelationId) {
        this.consumer = consumer;
        this.topicAlias = topicAlias;
        this.realTopic = realTopic;
        this.correlationHeaderName = correlationHeaderName;
        this.runCorrelationId = runCorrelationId;
    }

    void arm() {
        List<PartitionInfo> infos = this.consumer.partitionsFor(this.realTopic);
        if (infos == null || infos.isEmpty()) {
            throw new StandTestException("Kafka topic '" + this.realTopic + "' (alias '" + this.topicAlias + "') has no partitions to assign");
        }
        List<TopicPartition> assigned = new ArrayList<>();
        for (PartitionInfo info : infos) {
            assigned.add(new TopicPartition(info.topic(), info.partition()));
        }
        this.partitions = List.copyOf(assigned);
        this.consumer.assign(this.partitions);
        this.consumer.seekToEnd(this.partitions);
        // Force the lazy seekToEnd to resolve now, so the consumer is positioned at the log end before
        // any triggering step runs (plan §8.7 — removes KAFKA-SEEK-RACE).
        for (TopicPartition partition : this.partitions) {
            this.consumer.position(partition);
        }
    }

    Optional<ConsumerRecord<String, String>> pollAndSelect(Duration pollTimeout, Predicate<ConsumerRecord<String, String>> selection) {
        ConsumerRecords<String, String> polled = this.consumer.poll(pollTimeout);
        for (ConsumerRecord<String, String> record : polled) {
            this.seenCount++;
            // Keep a bounded ring of everything polled (foreign included) for timeout diagnostics — this is
            // the "what actually arrived" sample and must survive foreign eviction.
            this.recentSeen.addLast(record);
            if (this.recentSeen.size() > RECENT_SEEN_CAP) {
                this.recentSeen.removeFirst();
            }
            if (isForeign(record)) {
                // Another run's record (a different SDK-owned correlation id): no expect in this run can
                // ever select it, so evict it from the selection buffer rather than retaining foreign
                // traffic for the whole run.
                continue;
            }
            if (!this.selectedKeys.contains(recordKey(record))) {
                this.buffer.add(record);
            }
        }
        if (this.buffer.size() > MAX_BUFFERED) {
            throw new StandTestException("Kafka expect on topic '" + this.realTopic + "' (alias '" + this.topicAlias + "') buffered more than "
                    + MAX_BUFFERED + " unmatched messages that could be this run's (other runs' correlated records are already evicted) — the selection matches nothing; narrow the correlation/key selection or use a more specific topic");
        }
        Iterator<ConsumerRecord<String, String>> records = this.buffer.iterator();
        while (records.hasNext()) {
            ConsumerRecord<String, String> record = records.next();
            if (selection.test(record)) {
                this.selectedKeys.add(recordKey(record));
                records.remove();
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /**
     * A record is foreign when the topic declares a HEADER correlation carrier and the record carries a
     * correlation id in it that is not this run's. A record with no correlation header is NOT foreign — it
     * could be a key-selected message the system under test did not echo the correlation id onto.
     */
    private boolean isForeign(ConsumerRecord<String, String> record) {
        if (this.correlationHeaderName == null || this.runCorrelationId == null) {
            return false;
        }
        String value = headerValue(record, this.correlationHeaderName);
        return value != null && !this.runCorrelationId.equals(value);
    }

    private static String headerValue(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return (header == null || header.value() == null) ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String recordKey(ConsumerRecord<String, String> record) {
        return record.partition() + ":" + record.offset();
    }

    int messagesSeen() {
        return this.seenCount;
    }

    /**
     * Returns up to {@code max} of the most recently polled records (in arrival order), for timeout
     * diagnostics — the sample of last-seen messages required by plan §4. Drawn from the bounded
     * {@link #recentSeen} ring (not the selection buffer), so it still reports foreign records that were
     * evicted from selection — exactly the "a different correlation id arrived" signal a debugger needs.
     */
    List<ConsumerRecord<String, String>> recentlySeen(int max) {
        List<ConsumerRecord<String, String>> snapshot = new ArrayList<>(this.recentSeen);
        int from = Math.max(0, snapshot.size() - max);
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    String realTopic() {
        return this.realTopic;
    }

    List<TopicPartition> partitions() {
        return this.partitions;
    }

    @Override
    public void close() {
        this.consumer.close();
    }
}
