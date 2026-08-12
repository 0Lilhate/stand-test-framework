package ru.alfa.stand.test.allure.attachment;

import java.util.Locale;

/**
 * The generic, transport-agnostic attachment kinds the adapter publishes to Allure.
 *
 * <p>Deliberately generic (plan §8.9): there are no REST/Kafka/DB-specific attachment kinds. Each value
 * carries the media type Allure stores and the file extension used for the rendered source file. The
 * producing adapter chooses a media type when it builds a core {@code Attachment}; this enum is how the
 * reporting side maps that media type back to a file extension.
 */
public enum AttachmentType {

    /** Plain UTF-8 text. */
    TEXT("text/plain", "txt"),

    /** JSON document. */
    JSON("application/json", "json"),

    /** XML document. */
    XML("application/xml", "xml"),

    /** SQL statement text. */
    SQL("application/sql", "sql"),

    /** Arbitrary bytes, and the fallback for a file body whose media type is unknown. */
    BINARY("application/octet-stream", "bin"),

    /** A rendered key/value diagnostics block. */
    KEY_VALUE("text/plain", "txt");

    private final String mediaType;
    private final String fileExtension;

    AttachmentType(String mediaType, String fileExtension) {
        this.mediaType = mediaType;
        this.fileExtension = fileExtension;
    }

    /**
     * Returns the media type Allure stores for this kind.
     *
     * @return the media type
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * Returns the file extension (no leading dot) for the rendered attachment source.
     *
     * @return the file extension
     */
    public String fileExtension() {
        return fileExtension;
    }

    /**
     * Derives a sensible file extension for an arbitrary media type carried on a core attachment,
     * falling back to {@code txt} for an unknown type.
     *
     * @param mediaType the media type (may be null)
     * @return the file extension without a leading dot
     */
    public static String extensionForMediaType(String mediaType) {
        if (mediaType == null) {
            return TEXT.fileExtension;
        }
        String lower = mediaType.toLowerCase(Locale.ROOT);
        if (lower.contains("json")) {
            return JSON.fileExtension;
        }
        if (lower.contains("xml")) {
            return XML.fileExtension;
        }
        if (lower.contains("sql")) {
            return SQL.fileExtension;
        }
        if (lower.contains("octet-stream")) {
            return BINARY.fileExtension;
        }
        return TEXT.fileExtension;
    }

    /**
     * Derives the file extension for a FILE-backed attachment (ADR-UI-005).
     *
     * <p>Separate from {@link #extensionForMediaType(String)} because the two fall back in opposite
     * directions: an unknown media type on a text body is most usefully {@code txt}, while on a binary
     * body it must be {@code bin} — naming a screenshot {@code .txt} makes the report offer it as text.
     * The known types are the ones wave 1 produces (UITG-S013…S016): a PNG screenshot, a WebM video, a
     * trace ZIP, plus the text-shaped artefacts (console log, network log) that travel as files when they
     * are large.
     *
     * @param mediaType the media type (may be null)
     * @return the file extension without a leading dot; {@code bin} when the type is unknown
     */
    public static String extensionForBinaryMediaType(String mediaType) {
        if (mediaType == null) {
            return BINARY.fileExtension;
        }
        String lower = mediaType.toLowerCase(Locale.ROOT);
        if (lower.contains("png")) {
            return "png";
        }
        if (lower.contains("jpeg") || lower.contains("jpg")) {
            return "jpg";
        }
        if (lower.contains("webm")) {
            return "webm";
        }
        if (lower.contains("zip")) {
            return "zip";
        }
        if (lower.contains("json")) {
            return JSON.fileExtension;
        }
        if (lower.contains("xml")) {
            return XML.fileExtension;
        }
        if (lower.startsWith("text/")) {
            return TEXT.fileExtension;
        }
        return BINARY.fileExtension;
    }
}
