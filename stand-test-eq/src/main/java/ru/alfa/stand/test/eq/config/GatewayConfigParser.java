package ru.alfa.stand.test.eq.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.environment.SectionEntry;

/** Structural parser for the gateway entry; references remain lazy. */
final class GatewayConfigParser {

    private static final String CASH_ACCOUNTS = "cash-accounts";
    private static final Set<String> FIELDS = Set.of("kind", "write-allowed", "base-url-ref", "auth", "unit", "branch",
            "inn-region-code", "inn-tax-offices", CASH_ACCOUNTS, "timeouts", "serialization",
            "unit-phase", "visibility", "defaults");

    private GatewayConfigParser() {
    }

    static GatewayBackendConfig parse(String environment, SectionEntry entry) {
        EqConfigReader reader = new EqConfigReader(EqBackendConfigParser.location(environment, entry));
        Map<String, Object> fields = entry.fields();
        reader.keys(fields, FIELDS, "");
        boolean writeAllowed = reader.bool(fields, "write-allowed");
        String baseUrlRef = reader.reference(fields, "base-url-ref");
        AuthConfig auth = auth(reader, fields.get("auth"));
        ConfiguredValue unit = reader.scalarValue(fields.get("unit"), "unit");
        ConfiguredValue branch = reader.scalarValue(fields.get("branch"), "branch");
        ConfiguredValue region = reader.scalarValue(fields.get("inn-region-code"), "inn-region-code");
        ConfiguredValue offices = reader.listValue(fields.get("inn-tax-offices"), "inn-tax-offices");
        Map<String, ConfiguredValue> cash = cashAccounts(reader, fields.get(CASH_ACCOUNTS));
        Map<String, Object> timeouts = reader.optionalMap(fields, "timeouts", Set.of("connect", "response"));
        Duration connect = reader.duration(timeouts, "connect", Duration.ofSeconds(10));
        Duration response = reader.duration(timeouts, "response", Duration.ofSeconds(60));
        Map<String, Object> serialization = reader.optionalMap(fields, "serialization", Set.of("acquire-timeout"));
        Duration acquire = reader.duration(serialization, "acquire-timeout", Duration.ofMinutes(10));
        GatewayBackendConfig.UnitPhase phase = unitPhase(reader, fields.get("unit-phase"));
        VisibilityConfig visibility = VisibilityConfigParser.parse(reader, fields.get("visibility"));
        EqDefaults defaults = defaults(reader, fields.get("defaults"));
        return new GatewayBackendConfig(entry.alias(), writeAllowed, baseUrlRef, auth, unit, branch, region, offices,
                cash, connect, response, acquire, phase, visibility, defaults);
    }

    private static AuthConfig auth(EqConfigReader reader, Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = reader.map(value, "auth");
        reader.keys(fields, Set.of("scheme", "username-ref", "password-ref", "token-ref"), "auth.");
        String scheme = reader.string(fields, "scheme").trim().toUpperCase(java.util.Locale.ROOT);
        String username = optionalReference(reader, fields, "username-ref");
        String password = optionalReference(reader, fields, "password-ref");
        String token = optionalReference(reader, fields, "token-ref");
        try {
            if ("BASIC".equals(scheme)) {
                return AuthConfig.basic(username, password);
            }
            if ("BEARER".equals(scheme)) {
                return AuthConfig.bearer(token);
            }
        } catch (IllegalArgumentException invalid) {
            throw reader.invalid("auth.scheme", invalid.getMessage());
        }
        throw reader.invalid("auth.scheme", "must be BASIC or BEARER");
    }

    private static String optionalReference(EqConfigReader reader, Map<String, Object> fields, String field) {
        return fields.containsKey(field) ? reader.reference(fields, field) : null;
    }

    private static Map<String, ConfiguredValue> cashAccounts(EqConfigReader reader, Object value) {
        Map<String, Object> raw = reader.map(value, CASH_ACCOUNTS);
        if (raw.isEmpty()) {
            throw reader.invalid(CASH_ACCOUNTS, "must contain at least one currency");
        }
        Map<String, ConfiguredValue> result = new LinkedHashMap<>();
        raw.forEach((currency, account) -> {
            if (!currency.matches("[A-Z]{3}")) {
                throw reader.invalid("cash-accounts." + currency, "currency must have three upper-case letters");
            }
            result.put(currency, reader.scalarValue(account, "cash-accounts." + currency));
        });
        return Map.copyOf(result);
    }

    private static GatewayBackendConfig.UnitPhase unitPhase(EqConfigReader reader, Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = reader.map(value, "unit-phase");
        reader.keys(fields, Set.of("system-ref", "username-ref", "password-ref", "allowed", "cache-ttl"),
                "unit-phase.");
        List<String> allowed = reader.stringList(fields.get("allowed"), "unit-phase.allowed");
        if (allowed.isEmpty()) {
            throw reader.invalid("unit-phase.allowed", "must contain at least one phase");
        }
        return new GatewayBackendConfig.UnitPhase(reader.reference(fields, "system-ref"),
                reader.reference(fields, "username-ref"), reader.reference(fields, "password-ref"), allowed,
                reader.duration(fields, "cache-ttl", Duration.ofMinutes(5)));
    }

    static EqDefaults defaults(EqConfigReader reader, Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> fields = reader.map(value, "defaults");
        reader.keys(fields, Set.of("organisation", "account", "individual"), "defaults.");
        Map<String, Object> organisation = reader.optionalMap(fields, "organisation",
                Set.of("name-prefix", "type"));
        Map<String, Object> account = reader.optionalMap(fields, "account",
                Set.of("type-organisation", "type-individual", "currency", "top-up",
                        "package-registration", "package-duration"));
        Map<String, Object> individual = reader.optionalMap(fields, "individual",
                Set.of("last-name", "first-name", "middle-name", "document-type", "service-package"));
        return new EqDefaults(reader.optionalString(organisation, "name-prefix"),
                reader.optionalString(organisation, "type"),
                reader.optionalString(account, "type-organisation"), reader.optionalString(account, "type-individual"),
                reader.optionalString(account, "currency"), reader.optionalDecimal(account, "top-up"),
                reader.optionalString(account, "package-registration"), reader.optionalString(account, "package-duration"),
                individualDefaults(reader, individual));
    }

    private static EqDefaults.IndividualDefaults individualDefaults(EqConfigReader reader, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return null;
        }
        return new EqDefaults.IndividualDefaults(reader.optionalString(fields, "last-name"),
                reader.optionalString(fields, "first-name"), reader.optionalString(fields, "middle-name"),
                reader.optionalString(fields, "document-type"), reader.optionalString(fields, "service-package"));
    }
}
