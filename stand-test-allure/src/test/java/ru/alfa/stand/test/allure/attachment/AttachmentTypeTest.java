package ru.alfa.stand.test.allure.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AttachmentTypeTest {

    @Test
    @DisplayName("each type exposes its media type and file extension")
    void types_exposeMediaTypeAndExtension() {
        assertThat(AttachmentType.JSON.mediaType()).isEqualTo("application/json");
        assertThat(AttachmentType.JSON.fileExtension()).isEqualTo("json");
        assertThat(AttachmentType.KEY_VALUE.mediaType()).isEqualTo("text/plain");
        assertThat(AttachmentType.BINARY.fileExtension()).isEqualTo("bin");
    }

    @Test
    @DisplayName("media types map to a sensible file extension, defaulting to txt")
    void extensionForMediaType_mapsKnownTypes() {
        assertThat(AttachmentType.extensionForMediaType("application/json")).isEqualTo("json");
        assertThat(AttachmentType.extensionForMediaType("text/xml")).isEqualTo("xml");
        assertThat(AttachmentType.extensionForMediaType("application/sql")).isEqualTo("sql");
        assertThat(AttachmentType.extensionForMediaType("application/octet-stream")).isEqualTo("bin");
        assertThat(AttachmentType.extensionForMediaType("text/plain")).isEqualTo("txt");
        assertThat(AttachmentType.extensionForMediaType("something/unknown")).isEqualTo("txt");
        assertThat(AttachmentType.extensionForMediaType(null)).isEqualTo("txt");
    }
}
