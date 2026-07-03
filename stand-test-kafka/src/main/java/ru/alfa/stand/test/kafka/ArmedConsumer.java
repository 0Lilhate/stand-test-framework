package ru.alfa.stand.test.kafka;

import java.time.Duration;
import java.util.ArrayList;
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
 * out-of-order, same-correlationId streams disambiguate correctly. The unmatched buffer is bounded by
 * {@link #MAX_BUFFERED}: on a busy shared topic a selection that matches nothing would otherwise grow
 * memory for the whole run, so breaching the bound is an immediate infrastructure error rather than a
 * slow timeout. It is single-threaded: a run is driven on one thread, consistent with
 * {@code KafkaConsumer} not being thread-safe.
 */
final class ArmedConsumer implements AutoCloseable {

    /** Upper bound of buffered UNMATCHED records; breaching it fails fast instead of growing memory. */
    static final int MAX_BUFFERED = 10_000;

    private final Consumer<String, String> consumer;
    private final String topicAlias;
    private final String realTopic;
    private final List<ConsumerRecord<String, String>> buffer = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private List<TopicPartition> partitions = List.of();
    private int seenCount;

    ArmedConsumer(Consumer<String, String> consumer, String topicAlias, String realTopic) {
        this.consumer = consumer;
        this.topicAlias = topicAlias;
        this.realTopic = realTopic;
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
            if (!this.selectedKeys.contains(recordKey(record))) {
                this.buffer.add(record);
            }
        }
        if (this.buffer.size() > MAX_BUFFERED) {
            throw new StandTestException("Kafka expect on topic '" + this.realTopic + "' (alias '" + this.topicAlias + "') buffered more than "
                    + MAX_BUFFERED + " unmatched messages — the selection matches nothing on a busy topic; narrow the correlation/key selection or use a more specific topic");
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

    private static String recordKey(ConsumerRecord<String, String> record) {
        return record.partition() + ":" + record.offset();
    }

    int messagesSeen() {
        return this.seenCount;
    }

    /**
     * Returns up to {@code max} of the most recently buffered records (in arrival order), for timeout
     * diagnostics — the sample of last-seen messages required by plan §4.
     */
    List<ConsumerRecord<String, String>> recentlySeen(int max) {
        int from = Math.max(0, this.buffer.size() - max);
        return List.copyOf(this.buffer.subList(from, this.buffer.size()));
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
