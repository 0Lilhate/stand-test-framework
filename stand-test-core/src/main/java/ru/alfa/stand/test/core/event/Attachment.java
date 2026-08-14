package ru.alfa.stand.test.core.event;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable, transport-agnostic reporting attachment (plan §8.9).
 *
 * <p>A generic carrier of a single named piece of evidence — a request/response body, a final SQL
 * statement, a rendered key/value diagnostics block, a screenshot — that an adapter records on a step so
 * a reporting consumer (the Allure adapter) can render it without knowing the producing transport. Core
 * depends on no reporting library: this is a plain value type and the only attachment contract in the
 * SDK. There are deliberately no REST/Kafka/DB/gRPC/UI-specific attachment subtypes — the
 * {@code mediaType} (for example {@code application/json}, {@code text/plain}, {@code image/png}) is what
 * a renderer keys off.
 *
 * <h2>Two bodies, exactly one present</h2>
 *
 * <p>An attachment carries EITHER textual {@code content} OR a {@code file} path — never both, never
 * neither (ADR-UI-005, variant A). The file form exists because the channel used to be textual end to
 * end: the Allure sink did {@code content.getBytes(UTF_8)}, so a PNG, a WebM or a trace ZIP could not
 * pass through it. Base64 in the text field does not solve it either — the file extension is derived
 * from the media type, so the report would offer a {@code .bin} wall of characters instead of a picture.
 *
 * <p>Why a {@link Path} rather than a {@code byte[]}: a record compares its components structurally,
 * and an array compares by identity — a {@code byte[]} component would quietly break the value
 * semantics this repository requires of immutable value types. A path is also cheap to carry and keeps
 * core free of IO: <strong>nothing here opens the file</strong>. The sink does, which is what keeps the
 * "core is JDK-only, no IO" invariant true — {@code java.nio.file.Path} is the JDK, and holding one
 * performs no IO.
 *
 * <p><strong>Security.</strong> An {@code Attachment} must already be redacted by the producing adapter:
 * resolved secrets, credentials and secret-bind values must never reach this type. The contract is
 * "carry pre-sanitised evidence"; it performs no masking itself. The Allure sink additionally applies a
 * best-effort secret mask to TEXTUAL bodies before publishing (defence-in-depth), but that second
 * echelon never relaxes this contract — and it cannot help the file form at all: a screenshot's bytes
 * cannot be masked after the fact. That is exactly why sensitive regions must be masked in the DOM
 * <em>before</em> the artefact is taken (SEC-05, UITG-S017), and why the sink validates a file path
 * against the run's artefacts directory before opening it — a reporting channel that reads an arbitrary
 * path is a file-disclosure channel.
 *
 * @param name a short, non-blank human-readable name (for example {@code request} or {@code screenshot});
 *     null and blank are refused alike, as {@link IllegalArgumentException} rather than
 *     {@link NullPointerException} — absent and empty are one contract here, not two
 * @param mediaType the non-blank media type describing the body (for example {@code application/json});
 *     null and blank are refused alike, as {@link IllegalArgumentException}
 * @param content the textual content, or null when this attachment carries a file
 * @param file the path to the binary body, or null when this attachment carries text
 */
public record Attachment(String name, String mediaType, String content, Path file) {

    public Attachment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("attachment name must not be blank");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("attachment mediaType must not be blank");
        }
        if (content == null && file == null) {
            throw new IllegalArgumentException("attachment '" + name + "' must carry either content or file, but both are null");
        }
        if (content != null && file != null) {
            throw new IllegalArgumentException("attachment '" + name + "' must carry either content or file, not both");
        }
    }

    /**
     * Creates a textual attachment. This is the pre-existing three-argument shape, kept so that every
     * call site written before the file form compiles unchanged.
     *
     * @param name a short, non-blank human-readable name
     * @param mediaType the non-blank media type describing the content
     * @param content the textual content (never null; may be empty)
     */
    public Attachment(String name, String mediaType, String content) {
        this(name, mediaType, Objects.requireNonNull(content, "attachment content must not be null"), null);
    }

    /**
     * Creates a textual attachment from the given name, media type and content.
     *
     * @param name a short, non-blank human-readable name
     * @param mediaType the non-blank media type describing the content
     * @param content the textual content (never null; may be empty)
     * @return a new textual attachment
     */
    public static Attachment of(String name, String mediaType, String content) {
        return new Attachment(name, mediaType, content);
    }

    /**
     * Creates a file-backed attachment. The file is neither read nor validated here — core performs no
     * IO; the reporting sink reads it, after checking that it lies inside the run's artefacts directory.
     *
     * @param name a short, non-blank human-readable name
     * @param mediaType the non-blank media type describing the body (for example {@code image/png})
     * @param file the path to the body (never null)
     * @return a new file-backed attachment
     */
    public static Attachment ofFile(String name, String mediaType, Path file) {
        return new Attachment(name, mediaType, null, Objects.requireNonNull(file, "attachment file must not be null"));
    }

    /**
     * Tells whether this attachment carries a file rather than text.
     *
     * @return true when the body is a file, false when it is textual content
     */
    public boolean isBinary() {
        return file != null;
    }
}
