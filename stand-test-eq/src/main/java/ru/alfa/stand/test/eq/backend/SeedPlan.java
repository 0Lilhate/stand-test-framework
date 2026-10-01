package ru.alfa.stand.test.eq.backend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.core.scenario.GenericStep;
import ru.alfa.stand.test.core.scenario.ScenarioStep;
import ru.alfa.stand.test.eq.EqAttribute;
import ru.alfa.stand.test.eq.EqStepParameters;
import ru.alfa.stand.test.eq.config.EqDefaults;

/** Typed view of a validated lazy eq.seed step. */
public record SeedPlan(String alias, String backend, String clientKind, String name, String servicePackage,
                       List<Account> accounts, Set<EqAttribute> approximations) {

    private static final Set<String> STEP_FIELDS = Set.of(EqStepParameters.ALIAS, EqStepParameters.BACKEND,
            EqStepParameters.CLIENT_KIND, EqStepParameters.NAME, EqStepParameters.SERVICE_PACKAGE,
            EqStepParameters.ACCOUNTS, EqStepParameters.ALLOW_APPROXIMATION);
    private static final Set<String> ACCOUNT_FIELDS = Set.of(EqStepParameters.TYPE, EqStepParameters.CURRENCY,
            EqStepParameters.TOP_UP, EqStepParameters.SERVICE_PACKAGE, EqStepParameters.OPENED_AT);

    public SeedPlan {
        accounts = List.copyOf(accounts);
        approximations = Set.copyOf(approximations);
    }

    public static SeedPlan from(ScenarioStep step) {
        if (!(step instanceof GenericStep generic) || !EqStepParameters.STEP_TYPE.equals(step.type())) {
            throw new StandTestException("Expected an eq.seed generic step");
        }
        Map<String, Object> fields = generic.parameters();
        rejectUnknown(fields, STEP_FIELDS, "eq.seed");
        String alias = required(fields, EqStepParameters.ALIAS);
        if (!alias.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw new StandTestException("eq.seed alias must be a variable prefix");
        }
        String backend = required(fields, EqStepParameters.BACKEND);
        String kind = required(fields, EqStepParameters.CLIENT_KIND);
        if (!"organisation".equals(kind) && !"individual".equals(kind)) {
            throw new StandTestException("eq.seed clientKind must be organisation or individual");
        }
        Object accountsValue = fields.get(EqStepParameters.ACCOUNTS);
        if (!(accountsValue instanceof List<?> list) || list.isEmpty()) {
            throw new StandTestException("eq.seed accounts must be a non-empty list");
        }
        List<Account> accounts = list.stream().map(SeedPlan::account).toList();
        Object approximationValue = fields.get(EqStepParameters.ALLOW_APPROXIMATION);
        Set<EqAttribute> approximations = approximationValue == null ? Set.of() : approximations(approximationValue);
        String servicePackage = optional(fields, EqStepParameters.SERVICE_PACKAGE);
        if ("individual".equals(kind) && accounts.stream().anyMatch(account -> account.servicePackage() != null)) {
            throw new StandTestException("eq.seed individual service package belongs to the client");
        }
        if ("organisation".equals(kind) && servicePackage != null) {
            throw new StandTestException("eq.seed organisation service package belongs to an account");
        }
        return new SeedPlan(alias, backend, kind, optional(fields, EqStepParameters.NAME),
                servicePackage, accounts, approximations);
    }

    /** Applies registry defaults only where the DSL left an attribute absent. */
    public SeedPlan withDefaults(EqDefaults defaults, String runMarker) {
        if (defaults == null) {
            return this;
        }
        String resolvedName = name;
        if (name == null && "organisation".equals(clientKind) && defaults.organisationNamePrefix() != null) {
            resolvedName = defaults.organisationNamePrefix() + ' ' + runMarker;
        }
        List<Account> resolvedAccounts = accounts.stream().map(account -> {
            String defaultType = "organisation".equals(clientKind)
                    ? defaults.organisationAccountType() : defaults.individualAccountType();
            return new Account(account.type() == null ? defaultType : account.type(),
                    account.currency() == null ? defaults.currency() : account.currency(),
                    account.topUp() == null ? defaults.topUp() : account.topUp(),
                    account.servicePackage(), account.openedAt());
        }).toList();
        return new SeedPlan(alias, backend, clientKind, resolvedName, servicePackage, resolvedAccounts, approximations);
    }

    private static Account account(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new StandTestException("eq.seed account must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) raw;
        rejectUnknown(fields, ACCOUNT_FIELDS, "eq.seed account");
        String amount = optional(fields, EqStepParameters.TOP_UP);
        BigDecimal topUp;
        try {
            topUp = amount == null ? null : new BigDecimal(amount);
        } catch (NumberFormatException failure) {
            throw new StandTestException("eq.seed account topUp must be a number");
        }
        if (topUp != null && topUp.signum() < 0) {
            throw new StandTestException("eq.seed account topUp must not be negative");
        }
        String date = optional(fields, EqStepParameters.OPENED_AT);
        LocalDate openedAt;
        try {
            openedAt = date == null ? null : LocalDate.parse(date);
        } catch (RuntimeException failure) {
            throw new StandTestException("eq.seed account openedAt must be an ISO date");
        }
        return new Account(optional(fields, EqStepParameters.TYPE), optional(fields, EqStepParameters.CURRENCY),
                topUp, optional(fields, EqStepParameters.SERVICE_PACKAGE), openedAt);
    }

    private static Set<EqAttribute> approximations(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new StandTestException("eq.seed allowApproximation must be a list");
        }
        try {
            return list.stream().map(item -> EqAttribute.valueOf(item.toString())).collect(java.util.stream.Collectors.toSet());
        } catch (IllegalArgumentException failure) {
            throw new StandTestException("eq.seed allowApproximation contains an unknown attribute");
        }
    }

    private static String required(Map<String, Object> fields, String key) {
        String value = optional(fields, key);
        if (value == null) {
            throw new StandTestException("eq.seed parameter '" + key + "' is required");
        }
        return value;
    }

    private static void rejectUnknown(Map<String, Object> fields, Set<String> allowed, String location) {
        for (String key : fields.keySet()) {
            if (!allowed.contains(key)) {
                throw new StandTestException("Unknown " + location + " parameter '" + key + "'");
            }
        }
    }

    private static String optional(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StandTestException("eq.seed parameter '" + key + "' must be a non-blank string");
        }
        return text;
    }

    public record Account(String type, String currency, BigDecimal topUp, String servicePackage, LocalDate openedAt) {
    }
}
