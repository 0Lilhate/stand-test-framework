package ru.alfa.stand.test.allure.attachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.allure.lifecycle.AllureLifecycleFacade;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.Attachment;

/**
 * Publishes generic, transport-agnostic attachments to Allure through the {@link AllureLifecycleFacade}.
 *
 * <p>It handles two sources: the already-formed core {@link Attachment}s carried on a step event
 * (published with a file extension derived from their media type), and ad-hoc key/value blocks (step
 * diagnostics) rendered as a {@link AttachmentType#KEY_VALUE} text block. No content leaves this
 * publisher unmasked: producers are still expected to pre-redact attachment bodies, but every body is
 * additionally passed through {@link SecretMasker#maskText(String)} as a sink-side second echelon, and
 * key/value blocks are masked entry-by-entry. It contains no REST/Kafka/DB/gRPC-specific logic.
 */
public final class AllureAttachmentPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(AllureAttachmentPublisher.class);

    private final AllureLifecycleFacade lifecycle;
    private final SecretMasker secretMasker;

    /**
     * The only directory a file attachment may be read from, or null when file attachments are refused
     * outright. See {@link #publish(Attachment)} for why this is fail-closed.
     */
    private final Path artifactsRoot;

    /**
     * Creates a publisher writing through the given lifecycle facade and masking with the given masker.
     *
     * @param lifecycle the lifecycle facade to write attachments through
     * @param secretMasker the masker applied to rendered key/value blocks
     */
    public AllureAttachmentPublisher(AllureLifecycleFacade lifecycle, SecretMasker secretMasker) {
        this(lifecycle, secretMasker, null);
    }

    /**
     * Creates a publisher that may additionally publish FILE-backed attachments, but only those living
     * inside {@code artifactsRoot}.
     *
     * @param lifecycle the lifecycle facade to write attachments through
     * @param secretMasker the masker applied to textual bodies and rendered key/value blocks
     * @param artifactsRoot the run's artefacts directory; null refuses every file attachment
     */
    public AllureAttachmentPublisher(AllureLifecycleFacade lifecycle, SecretMasker secretMasker, Path artifactsRoot) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.secretMasker = Objects.requireNonNull(secretMasker, "secretMasker must not be null");
        this.artifactsRoot = artifactsRoot;
    }

    /**
     * Publishes a core attachment with its body passed through the sink-side secret masker, deriving
     * the file extension from its media type.
     *
     * @param attachment the attachment to publish (ignored when null)
     */
    public void publish(Attachment attachment) {
        if (attachment == null) {
            return;
        }
        if (attachment.isBinary()) {
            publishFile(attachment);
            return;
        }
        String extension = AttachmentType.extensionForMediaType(attachment.mediaType());
        lifecycle.addAttachment(attachment.name(), attachment.mediaType(), extension, secretMasker.maskText(attachment.content()));
    }

    /**
     * Publishes a file-backed attachment, refusing anything the run did not produce.
     *
     * <p>The channel became file-backed with ADR-UI-005, and that turned a reporting sink into something
     * that opens a path somebody else chose. Left unguarded it is a file-disclosure channel: a path
     * containing {@code ../}, or a symlink pointing out of the run directory, would put an arbitrary file
     * into a report that is then attached to a ticket. So the path is resolved to its real location —
     * which is what follows symlinks — and must lie inside the run's artefacts directory.
     *
     * <p>Fail-closed twice over. With no artefacts root configured, EVERY file attachment is refused
     * rather than published: a sink that does not know which directory belongs to the run cannot tell an
     * artefact from {@code /etc/passwd}. And every refusal is a WARN plus a skipped attachment, never an
     * exception — reporting is a side-channel and must not change a test outcome (plan §17). A missing
     * file is the same: the run keeps going without the picture.
     */
    private void publishFile(Attachment attachment) {
        Path file = attachment.file();
        if (artifactsRoot == null) {
            LOG.warn("Skipping file attachment '{}': this publisher has no artefacts directory configured, so no path can be proven to belong to the run", attachment.name());
            return;
        }
        Path resolvedFile;
        Path resolvedRoot;
        try {
            resolvedRoot = artifactsRoot.toRealPath();
            resolvedFile = file.toRealPath();
        } catch (IOException e) {
            LOG.warn("Skipping file attachment '{}': its path could not be resolved ({})", attachment.name(), e.toString());
            return;
        }
        if (!resolvedFile.startsWith(resolvedRoot)) {
            LOG.warn("Refusing file attachment '{}': it resolves outside the run's artefacts directory", attachment.name());
            return;
        }
        if (!Files.isRegularFile(resolvedFile)) {
            LOG.warn("Skipping file attachment '{}': no regular file at the resolved path", attachment.name());
            return;
        }
        String extension = AttachmentType.extensionForBinaryMediaType(attachment.mediaType());
        if (AttachmentType.BINARY.fileExtension().equals(extension)) {
            LOG.warn("File attachment '{}' has media type '{}', which maps to no known extension — publishing it as .bin", attachment.name(), attachment.mediaType());
        }
        lifecycle.addAttachment(attachment.name(), attachment.mediaType(), extension, resolvedFile);
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
        lifecycle.addAttachment(name, type.mediaType(), type.fileExtension(), secretMasker.maskText(content));
    }

    private static String render(Map<String, String> entries) {
        StringBuilder builder = new StringBuilder();
        entries.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        return builder.toString();
    }
}
