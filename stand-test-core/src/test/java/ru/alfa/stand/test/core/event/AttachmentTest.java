package ru.alfa.stand.test.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
