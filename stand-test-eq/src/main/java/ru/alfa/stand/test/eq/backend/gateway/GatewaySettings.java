package ru.alfa.stand.test.eq.backend.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import ru.alfa.stand.test.core.environment.AuthConfig;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.eq.config.ConfiguredValue;
import ru.alfa.stand.test.eq.config.EqDefaults;
import ru.alfa.stand.test.eq.config.GatewayBackendConfig;

/**
 * The gateway backend's configuration with every lazy reference resolved for this run.
 *
 * <p>Resolution happens only when the gateway backend is selected, so an IFT run never requires the
 * test-stand variables (BR-46). Values that fail to resolve or do not match their declared type are a
 * configuration error before any gateway call.
 */
public record GatewaySettings(
        String alias,
        boolean writeAllowed,
        String baseUrl,
        AuthConfig auth,
        String unit,
        String branch,
        String region,
        List<String> taxOffices,
        Map<String, String> cashAccounts,
        Duration connectTimeout,
        Duration responseTimeout,
        Duration acquireTimeout,
        GatewayBackendConfig.UnitPhase unitPhase,
        EqDefaults defaults) {

    public GatewaySettings {
        taxOffices = List.copyOf(taxOffices == null ? List.of() : taxOffices);
        cashAccounts = Map.copyOf(cashAccounts == null ? Map.of() : cashAccounts);
    }

    /** Resolves the typed config through the given lookup (typically {@code System::getenv}). */
    public static GatewaySettings resolve(GatewayBackendConfig config, UnaryOperator<String> lookup) {
        String baseUrl = resolveString(config.baseUrlReference(), "base-url-ref", lookup);
        String unit = resolveRequired(config.unit(), "unit", lookup);
        String branch = resolveRequired(config.branch(), "branch", lookup);
        String region = resolveRequired(config.innRegionCode(), "inn-region-code", lookup);
        List<String> offices = requireStrings(config.innTaxOffices().resolveStringList(lookup), "inn-tax-offices");
        Map<String, String> cash = new LinkedHashMap<>();
        config.cashAccounts().forEach((currency, value) ->
                cash.put(currency, resolveRequired(value, "cash-accounts." + currency, lookup)));
        if (cash.isEmpty()) {
            throw new StandTestException("EQ gateway cash-accounts must declare at least one currency");
        }
        return new GatewaySettings(config.alias(), config.writeAllowed(), baseUrl, config.auth(), unit, branch, region, offices, cash,
                config.connectTimeout(), config.responseTimeout(), config.acquireTimeout(), config.unitPhase(),
                config.defaults());
    }

    private static String resolveString(String reference, String field, UnaryOperator<String> lookup) {
        Object value = new ConfiguredValue(null, reference).resolve(lookup);
        return requireText(value, field);
    }

    private static String resolveRequired(ConfiguredValue value, String field, UnaryOperator<String> lookup) {
        return requireText(value.resolve(lookup), field);
    }

    private static String requireText(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new StandTestException("EQ gateway field '" + field + "' did not resolve to a value");
        }
        return value.toString();
    }

    private static List<String> requireStrings(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new StandTestException("EQ gateway field '" + field + "' did not resolve to a non-empty list");
        }
        return List.copyOf(values);
    }
}