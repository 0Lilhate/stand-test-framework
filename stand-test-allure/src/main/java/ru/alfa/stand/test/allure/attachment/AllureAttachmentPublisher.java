package ru.alfa.stand.test.allure.attachment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.Attachment;

/**
 * Publishes generic, transport-agnostic attachments to Allure through the {@link AllureLifecycleFacade}.
 *
 * <p>It handles two sources: the already-formed core {@link Attachment}s carried on a step event
 * (published verbatim, with a file extension derived from their media type — these are expected to be
 * pre-redacted by the producing adapter), and ad-hoc key/value blocks (step diagnostics) rendered as a
 * {@link AttachmentType#KEY_VALUE} text block <em>with secret values masked</em> by the
 * {@link SecretMasker}. It contains no REST/Kafka/DB/gRPC-specific logic.
 */
public final class AllureAttachmentPublisher {

    private final AllureLifecycleFacade lifecycle;
    private final SecretMasker secretMasker;

    /**
     * Creates a publisher writing through the given lifecycle facade and masking with the given masker.
     *
     * @param lifecycle the lifecycle facade to write attachments through
     * @param secretMasker the masker applied to rendered key/value blocks
     */
    public AllureAttachmentPublisher(AllureLifecycleFacade lifecycle, SecretMasker secretMasker) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.secretMasker = Objects.requireNonNull(secretMasker, "secretMasker must not be null");
    }

    /**
     * Publishes a core attachment verbatim, deriving the file extension from its media type.
     *
     * @param attachment the attachment to publish (ignored when null)
     */
    public void publish(Attachment attachment) {
        if (attachment == null) {
            return;
        }
        String extension = AttachmentType.extensionForMediaType(attachment.mediaType());
        lifecycle.addAttachment(attachment.name(), attachment.mediaType(), extension, attachment.content());
    }

    /**
     * Publishes a plain-text attachment.
     *
     * @param name the attachment name
     * @param content the text content (ignored when null)
     */
    public void publishText(String name, String content) {
        publishTyped(name, AttachmentType.TEXT, content);
    }

    /**
     * Publishes a JSON attachment.
     *
     * @param name the attachment name
     * @param content the JSON content (ignored when null)
     */
    public void publishJson(String name, String content) {
        publishTyped(name, AttachmentType.JSON, content);
    }

    /**
     * Publishes a step's diagnostics map as a masked key/value attachment named {@code diagnostics}.
     * Empty or null diagnostics produce no attachment.
     *
     * @param diagnostics the diagnostics map (keys to opaque values)
     */
    public void publishDiagnostics(Map<String, Object> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }
        publishKeyValue("diagnostics", diagnostics);
    }

    /**
     * Publishes an arbitrary key/value map as a masked {@link AttachmentType#KEY_VALUE} text block.
     * Empty or null entries produce no attachment.
     *
     * @param name the attachment name
     * @param entries the entries to render (values stringified, sensitive ones masked)
     */
    public void publishKeyValue(String name, Map<String, ?> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        Map<String, String> stringified = new LinkedHashMap<>();
        entries.forEach((key, value) -> stringified.put(key, String.valueOf(value)));
        String rendered = render(secretMasker.mask(stringified));
        lifecycle.addAttachment(name, AttachmentType.KEY_VALUE.mediaType(), AttachmentType.KEY_VALUE.fileExtension(), rendered);
    }

    private void publishTyped(String name, AttachmentType type, String content) {
        if (content == null) {
            return;
        }
        lifecycle.addAttachment(name, type.mediaType(), type.fileExtension(), content);
    }

    private static String render(Map<String, String> entries) {
        StringBuilder builder = new StringBuilder();
        entries.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        return builder.toString();
    }
}
