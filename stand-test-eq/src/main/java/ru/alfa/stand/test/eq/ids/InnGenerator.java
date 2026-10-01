package ru.alfa.stand.test.eq.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates run-unique Russian INNs with valid control digits (BR-41, Г-6).
 *
 * <p>A 10-digit INN is {@code region(2) + taxOffice(2) + serial(5) + check(1)}; a 12-digit INN is
 * {@code region(2) + taxOffice(2) + serial(6) + check(2)}. The serial is salted from {@code testRunId}
 * and advanced by a JVM-wide counter, so it is unique within a run and unlikely to repeat across
 * sequential runs — milliseconds alone, as the reference library used, are not acceptable.
 *
 * <p>Capacity is finite: the 5-digit organisation serial allows 10⁵ numbers per
 * {@code (region, taxOffice)} pair, and EQ clients are never deleted, so the tax office is selected
 * across the configured list rather than fixed. When EQ rejects a duplicate INN, the caller asks again
 * for a fresh one (BR-25); each call advances the counter.
 *
 * <p>The region code and the tax-office list are non-secret registry values; neither the test nor the
 * generated identifiers are configuration.
 */
public final class InnGenerator {

    private static final int[] CHECK_10 = {2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] CHECK_11 = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] CHECK_12 = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};

    private final String testRunId;
    private final String regionCode;
    private final List<String> taxOffices;
    private final AtomicInteger counter = new AtomicInteger();

    public InnGenerator(String testRunId, String regionCode, List<String> taxOffices) {
        if (testRunId == null || testRunId.isBlank()) {
            throw new IllegalArgumentException("testRunId must not be blank");
        }
        if (regionCode == null || !regionCode.matches("[0-9]{2}")) {
            throw new IllegalArgumentException("regionCode must be two digits");
        }
        if (taxOffices == null || taxOffices.isEmpty()) {
            throw new IllegalArgumentException("at least one tax office is required");
        }
        taxOffices.forEach(office -> {
            if (!office.matches("[0-9]{2}")) {
                throw new IllegalArgumentException("tax office codes must be two digits");
            }
        });
        this.testRunId = testRunId;
        this.regionCode = regionCode;
        this.taxOffices = List.copyOf(taxOffices);
    }

    /** Returns the next run-unique 10-digit organisation INN. */
    public String organisation() {
        int index = counter.getAndIncrement();
        String office = taxOffices.get(index % taxOffices.size());
        int serial = serial(index, office, 100_000);
        String base = regionCode + office + fiveDigits(serial);
        return base + checkDigit10(base);
    }

    /** Returns the next run-unique 12-digit individual INN. */
    public String individual() {
        int index = counter.getAndIncrement();
        String office = taxOffices.get(index % taxOffices.size());
        int serial = serial(index, office, 1_000_000);
        String base = regionCode + office + sixDigits(serial);
        String with11 = base + checkDigit(base, CHECK_11);
        return with11 + checkDigit(with11, CHECK_12);
    }

    private int serial(int index, String office, int capacity) {
        long base = hash(office) % capacity;
        return (int) ((base + index) % capacity);
    }

    private static String fiveDigits(int value) {
        return String.format("%05d", value);
    }

    private static String sixDigits(int value) {
        return String.format("%06d", value);
    }

    private long hash(String purpose) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((testRunId + ':' + purpose).getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                result = (result << 8) | (bytes[index] & 0xFFL);
            }
            return Math.abs(result);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static char checkDigit10(String base) {
        return checkDigit(base, CHECK_10);
    }

    private static char checkDigit(String base, int[] coefficients) {
        int sum = 0;
        for (int index = 0; index < coefficients.length; index++) {
            sum += (base.charAt(index) - '0') * coefficients[index];
        }
        return (char) ('0' + (sum % 11 % 10));
    }

    static boolean isValidOrganisation(String inn) {
        return inn != null && inn.length() == 10 && inn.matches("[0-9]{10}")
                && inn.charAt(9) == checkDigit10(inn);
    }

    static boolean isValidIndividual(String inn) {
        if (inn == null || !inn.matches("[0-9]{12}")) {
            return false;
        }
        String base = inn.substring(0, 10);
        String with11 = base + checkDigit(base, CHECK_11);
        return inn.substring(0, 11).equals(with11) && inn.charAt(11) == checkDigit(with11, CHECK_12);
    }
}