package ru.alfa.stand.test.eq;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable account request; absent values are supplied by the selected environment backend. */
public record EqAccount(String type, String currency, BigDecimal topUp, String servicePackage, LocalDate openedAt) {

    public EqAccount {
        requireNonBlankIfPresent(type, "type");
        requireNonBlankIfPresent(currency, "currency");
        requireNonBlankIfPresent(servicePackage, "servicePackage");
        if (topUp != null && topUp.signum() < 0) {
            throw new IllegalArgumentException("topUp must not be negative");
        }
    }

    /** Creates an account request whose values all come from the backend defaults. */
    public static EqAccount create() {
        return new EqAccount(null, null, null, null, null);
    }

    /** Creates an account request with an explicit account type. */
    public static EqAccount type(String type) {
        return create().withType(type);
    }

    /** Returns a copy with the given account type. */
    public EqAccount withType(String value) {
        return new EqAccount(value, currency, topUp, servicePackage, openedAt);
    }

    /** Returns a copy with the given currency. */
    public EqAccount currency(String value) {
        return new EqAccount(type, value, topUp, servicePackage, openedAt);
    }

    /** Returns a copy with the given amount to credit. */
    public EqAccount topUp(BigDecimal value) {
        return new EqAccount(type, currency, value, servicePackage, openedAt);
    }

    /** Returns a copy with the given service package. */
    public EqAccount servicePackage(String value) {
        return new EqAccount(type, currency, topUp, value, openedAt);
    }

    /** Returns a copy with the given opening date. */
    public EqAccount openedAt(LocalDate value) {
        return new EqAccount(type, currency, topUp, servicePackage, value);
    }

    Map<String, Object> toParameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        putIfPresent(parameters, EqStepParameters.TYPE, type);
        putIfPresent(parameters, EqStepParameters.CURRENCY, currency);
        if (topUp != null) {
            parameters.put(EqStepParameters.TOP_UP, topUp.toPlainString());
        }
        putIfPresent(parameters, EqStepParameters.SERVICE_PACKAGE, servicePackage);
        if (openedAt != null) {
            parameters.put(EqStepParameters.OPENED_AT, openedAt.toString());
        }
        return Map.copyOf(parameters);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void requireNonBlankIfPresent(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
