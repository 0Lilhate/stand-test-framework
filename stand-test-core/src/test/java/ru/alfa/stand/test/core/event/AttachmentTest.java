package ru.alfa.stand.test.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentTest {

    @Test
    @DisplayName("the factory and the canonical constructor expose name, mediaType and content")
    void factory_exposesComponents() {
        Attachment attachment = Attachment.of("request", "application/json", "{\"a\":1}");

        assertThat(attachment.name()).isEqualTo("request");
        assertThat(attachment.mediaType()).isEqualTo("application/json");
        assertThat(attachment.content()).isEqualTo("{\"a\":1}");
        assertThat(attachment).isEqualTo(new Attachment("request", "application/json", "{\"a\":1}"));
    }

    @Test
    @DisplayName("empty content is allowed")
    void emptyContent_isAllowed() {
        assertThat(new Attachment("body", "text/plain", "").content()).isEmpty();
    }

    @Test
    @DisplayName("blank name, blank media type and null content are rejected")
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> new Attachment(" ", "text/plain", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attachment("body", " ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Attachment("body", "text/plain", null))
                .isInstanceOf(NullPointerException.class);
    }

    // ADR-UI-005 (variant A): the record grew a `Path file` component. The four tests below pin the
    // invariant and the backward compatibility the decision promised.

    @Test
    @DisplayName("all four content/file combinations are decided explicitly: exactly one body, never both, never neither")
    void bodyInvariant_acceptsExactlyOne() {
        assertThat(new Attachment("body", "text/plain", "x", null).isBinary()).isFalse();
        assertThat(new Attachment("shot", "image/png", null, Path.of("shot.png")).isBinary()).isTrue();

        assertThatThrownBy(() -> new Attachment("shot", "image/png", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both are null");
        assertThatThrownBy(() -> new Attachment("shot", "image/png", "x", Path.of("shot.png")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
    }

    @Test
    @DisplayName("the three-argument constructor and of(...) still build a textual attachment — the compatibility the ADR promised")
    void threeArgumentForm_staysTextual() {
        Attachment viaConstructor = new Attachment("request", "application/json", "{}");
        Attachment viaFactory = Attachment.of("request", "application/json", "{}");

        assertThat(viaConstructor).isEqualTo(viaFactory);
        assertThat(viaConstructor.file()).isNull();
        assertThat(viaConstructor.isBinary()).isFalse();
        assertThat(viaConstructor.content()).isEqualTo("{}");
    }

    @Test
    @DisplayName("ofFile builds a file-backed attachment and refuses a null path")
    void ofFile_buildsBinaryAttachment() {
        Attachment attachment = Attachment.ofFile("screenshot", "image/png", Path.of("build", "shot.png"));

        assertThat(attachment.isBinary()).isTrue();
        assertThat(attachment.content()).isNull();
        assertThat(attachment.file()).isEqualTo(Path.of("build", "shot.png"));
        assertThatThrownBy(() -> Attachment.ofFile("screenshot", "image/png", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("value semantics survive the new component — the reason the ADR chose Path over byte[]")
    void pathComponent_keepsValueSemantics() {
        Attachment one = Attachment.ofFile("screenshot", "image/png", Path.of("build", "shot.png"));
        Attachment other = Attachment.ofFile("screenshot", "image/png", Path.of("build", "shot.png"));

        // A byte[] component would compare by identity here and this assertion would fail — which is the
        // whole argument recorded in ADR-UI-005 against variant B.
        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }
}
