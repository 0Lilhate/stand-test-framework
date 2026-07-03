package ru.alfa.stand.test.core.event;

import java.util.Objects;

/**
 * Immutable, transport-agnostic reporting attachment (plan §8.9).
 *
 * <p>A generic carrier of a single named piece of evidence — a request/response body, a final SQL
 * statement, a rendered key/value diagnostics block — that an adapter records on a step so a reporting
 * consumer (the Allure adapter, a later iteration) can render it without knowing the producing
 * transport. Core depends on no reporting library: this is a plain value type and the only attachment
 * contract in the SDK. There are deliberately no REST/Kafka/DB/gRPC-specific attachment subtypes — the
 * {@code mediaType} (for example {@code application/json}, {@code application/xml}, {@code text/plain})
 * is what a renderer keys off.
 *
 * <p><strong>Security.</strong> An {@code Attachment} must already be redacted by the producing adapter:
 * resolved secrets, credentials and secret-bind values must never reach this type. The contract is
 * "carry pre-sanitised evidence"; it performs no masking itself. The Allure sink additionally applies a
 * best-effort secret mask to attachment bodies before publishing (defence-in-depth), but that second
 * echelon never relaxes this contract.
 *
 * @param name a short, non-blank human-readable name (for example {@code request} or {@code diagnostics})
 * @param mediaType the non-blank media type describing the content (for example {@code application/json})
 * @param content the textual content (never null; may be empty)
 */
public record Attachment(String name, String mediaType, String content) {

    public Attachment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("attachment name must not be blank");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("attachment mediaType must not be blank");
        }
        Objects.requireNonNull(content, "attachment content must not be null");
    }

    /**
     * Creates an attachment from the given name, media type and content.
     *
     * @param name a short, non-blank human-readable name
     * @param mediaType the non-blank media type describing the content
     * @param content the textual content (never null; may be empty)
     * @return a new attachment
     */
    public static Attachment of(String name, String mediaType, String content) {
        return new Attachment(name, mediaType, content);
    }
}
