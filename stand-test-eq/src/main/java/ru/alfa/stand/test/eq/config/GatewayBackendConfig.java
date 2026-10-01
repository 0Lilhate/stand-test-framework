package ru.alfa.stand.test.eq.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import ru.alfa.stand.test.core.environment.AuthConfig;

/** Gateway settings, retained as typed values and lazy secret references. */
public record GatewayBackendConfig(
        String alias,
        boolean writeAllowed,
        String baseUrlReference,
        AuthConfig auth,
        ConfiguredValue unit,
        ConfiguredValue branch,
        ConfiguredValue innRegionCode,
        ConfiguredValue innTaxOffices,
        Map<String, ConfiguredValue> cashAccounts,
        Duration connectTimeout,
        Duration responseTimeout,
        Duration acquireTimeout,
        UnitPhase unitPhase,
        VisibilityConfig visibility,
        EqDefaults defaults) implements EqBackendConfig {

    public GatewayBackendConfig {
        cashAccounts = Map.copyOf(cashAccounts);
    }

    public record UnitPhase(String systemReference, String usernameReference, String passwordReference,
                            List<String> allowed, Duration cacheTtl) {
        public UnitPhase {
            allowed = List.copyOf(allowed);
        }
    }

}
