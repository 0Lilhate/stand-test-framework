package ru.alfa.stand.test.eq.backend.showcases;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Positional showcases v1 records matching the existing organisation fixtures. */
public final class ShowcasesRecords {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ShowcasesRecords() {
    }

    /** Builds ACLM v1 and ACLN v2 for an organisation. */
    public static List<Record> organisation(String pin, String registrationNumber, String name) {
        require(pin, "pin");
        require(registrationNumber, "registrationNumber");
        require(name, "name");
        return List.of(
                new Record("ACLM", null, List.of(
                        text(pin), nil(""), text("U"), text("GB"), text(registrationNumber), text("RU"))),
                new Record("ACLN", 2, List.of(
                        text(pin), nil(""), text(name), text(pin), text(pin), text(pin))));
    }

    /** Builds UACM v1 for one account. */
    public static Record account(String account, String pin, String type, String currency, LocalDate openedAt) {
        require(account, "account");
        require(pin, "pin");
        require(type, "type");
        require(currency, "currency");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        return new Record("UACM", null, List.of(
                text(account), text(pin), text("4101"), nil("U"), text(type), text("RU"), text(currency),
                new Field("timestamp", openedAt.atStartOfDay().toString()), nil(""), bool(true), bool(true),
                text("02"), bool(true), text("40702")));
    }

    /** Builds UDLM v1 for an account package. */
    public static Record deal(String dealId, String pin, String account, String servicePackage, LocalDate startedAt) {
        require(dealId, "dealId");
        require(pin, "pin");
        require(account, "account");
        require(servicePackage, "servicePackage");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        return new Record("UDLM", null, List.of(
                text(dealId), text(pin), text(account), text(servicePackage),
                new Field("timestamp", startedAt.atStartOfDay().toString()), text("A")));
    }

    /** Serializes the positional records through Jackson 3, omitting an absent version. */
    public static String toJson(List<Record> records) {
        Objects.requireNonNull(records, "records must not be null");
        List<Map<String, Object>> payload = records.stream().map(record -> {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("businessCode", record.businessCode());
            if (record.version() != null) {
                object.put("version", record.version());
            }
            object.put("fields", record.fields().stream()
                    .map(field -> Map.of("type", field.type(), "value", field.value()))
                    .toList());
            return object;
        }).toList();
        return JSON.writeValueAsString(payload);
    }

    private static Field text(String value) {
        return new Field("string", value);
    }

    private static Field nil(String value) {
        return new Field("nil", value);
    }

    private static Field bool(boolean value) {
        return new Field("boolean", value);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record Field(String type, Object value) {
    }

    public record Record(String businessCode, Integer version, List<Field> fields) {
        public Record {
            fields = List.copyOf(fields);
        }
    }
}
