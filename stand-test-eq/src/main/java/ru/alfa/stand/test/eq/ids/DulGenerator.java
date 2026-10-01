package ru.alfa.stand.test.eq.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates run-unique document (DUL) numbers (BR-41, Г-6).
 *
 * <p>The reference library derived the number from {@code ddMMyyHHmmssSSS}, which is unique only to the
 * millisecond and repeats within one run. Here the number is a run salt plus a monotonic counter, so two
 * documents produced in the same millisecond still differ.
 */
public final class DulGenerator {

    private final String salt;
    private final AtomicInteger counter = new AtomicInteger();

    public DulGenerator(String testRunId) {
        if (testRunId == null || testRunId.isBlank()) {
            throw new IllegalArgumentException("testRunId must not be blank");
        }
        this.salt = Long.toUnsignedString(hash(testRunId), 36);
    }

    /** Returns the next run-unique document number, digits only. */
    public String next() {
        long sequence = Integer.toUnsignedLong(counter.getAndIncrement());
        return "99" + Long.toUnsignedString(hash(salt + ':' + sequence), 10).substring(0, 8);
    }

    private static long hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                result = (result << 8) | (bytes[index] & 0xFFL);
            }
            return result;
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}