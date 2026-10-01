package ru.alfa.stand.test.eq.ids;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/** Deterministic, run-scoped identifiers for the showcases backend. */
public final class EqIdGenerator {

    private static final Map<String, String> CURRENCY_CODES = Map.of("RUR", "810", "USD", "840", "EUR", "978");

    private final String testRunId;
    private final int stepNumber;

    public EqIdGenerator(String testRunId, int stepNumber) {
        if (testRunId == null || testRunId.isBlank()) {
            throw new IllegalArgumentException("testRunId must not be blank");
        }
        if (stepNumber < 0) {
            throw new IllegalArgumentException("stepNumber must not be negative");
        }
        this.testRunId = testRunId;
        this.stepNumber = stepNumber;
    }

    /** Returns {@code T} followed by five upper-case base-36 characters. */
    public String pin() {
        String base36 = hash("pin").toString(36).toUpperCase(Locale.ROOT);
        return "T" + base36.substring(0, 5);
    }

    /** Returns a 20-digit account number with balance and currency prefixes. */
    public String account(int index, String currency) {
        if (index < 0) {
            throw new IllegalArgumentException("account index must not be negative");
        }
        String code = CURRENCY_CODES.get(currency);
        if (code == null) {
            throw new IllegalArgumentException("Unsupported account currency: " + currency);
        }
        return "40702" + code + decimalDigits("account:" + index + ':' + currency, 12);
    }

    /** Reports whether this showcases account format knows the given currency code. */
    public static boolean supportsCurrency(String currency) {
        return CURRENCY_CODES.containsKey(currency);
    }

    /** Returns the per-run 12-digit client registration attribute. */
    public String registrationNumber() {
        return decimalDigits("registration", 12);
    }

    /** Returns the package deal id used by showcases. */
    public String deal(String servicePackage) {
        if (servicePackage == null || servicePackage.isBlank()) {
            throw new IllegalArgumentException("servicePackage must not be blank");
        }
        return pin() + '_' + servicePackage;
    }

    private String decimalDigits(String purpose, int count) {
        String digits = hash(purpose).toString(10);
        return digits.substring(0, count);
    }

    private BigInteger hash(String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = testRunId + ':' + stepNumber + ':' + purpose;
            return new BigInteger(1, digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
