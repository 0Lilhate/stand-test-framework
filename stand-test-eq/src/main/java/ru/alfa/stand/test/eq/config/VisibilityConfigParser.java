package ru.alfa.stand.test.eq.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict parser for the optional visibility probe on either backend. */
final class VisibilityConfigParser {

    private VisibilityConfigParser() {
    }

    static VisibilityConfig parse(EqConfigReader reader, Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = reader.map(value, "visibility");
        reader.keys(fields, Set.of("probe", "timeout", "poll-interval"), "visibility.");
        Map<String, Object> probe = reader.map(fields.get("probe"), "visibility.probe");
        reader.keys(probe, Set.of("service", "path", "query", "expect-status", "expect-body"),
                "visibility.probe.");
        Map<String, String> query = query(reader, probe);
        Map<String, Object> body = reader.map(probe.get("expect-body"), "visibility.probe.expect-body");
        reader.keys(body, Set.of("path", "equals"), "visibility.probe.expect-body.");
        String bodyPath = reader.string(body, "path");
        if (!bodyPath.startsWith("$.")) {
            throw reader.invalid("visibility.probe.expect-body.path", "must be a $. JSONPath");
        }
        int status = reader.integer(probe, "expect-status");
        if (status < 100 || status > 599) {
            throw reader.invalid("visibility.probe.expect-status", "must be an HTTP status");
        }
        Duration timeout = reader.duration(fields, "timeout", Duration.ofSeconds(120));
        Duration poll = reader.duration(fields, "poll-interval", Duration.ofSeconds(2));
        if (poll.compareTo(timeout) > 0) {
            throw reader.invalid("visibility.poll-interval", "must not exceed timeout");
        }
        return new VisibilityConfig(reader.alias(probe, "service"), reader.path(probe, "path"), query,
                status, bodyPath, reader.string(body, "equals"), timeout, poll);
    }

    private static Map<String, String> query(EqConfigReader reader, Map<String, Object> probe) {
        Object raw = probe.get("query");
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> values = reader.map(raw, "visibility.probe.query");
        Map<String, String> query = new LinkedHashMap<>();
        values.forEach((name, value) -> query.put(name, reader.stringValue(value, "visibility.probe.query." + name)));
        return Map.copyOf(query);
    }
}
