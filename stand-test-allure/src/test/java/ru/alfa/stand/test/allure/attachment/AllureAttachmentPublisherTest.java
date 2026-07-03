package ru.alfa.stand.test.allure.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade.RecordedAttachment;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.Attachment;

class AllureAttachmentPublisherTest {

    private final FakeAllureLifecycleFacade lifecycle = new FakeAllureLifecycleFacade();
    private final AllureAttachmentPublisher publisher = new AllureAttachmentPublisher(lifecycle, new SecretMasker());

    @Test
    @DisplayName("a non-secret core attachment is published unchanged with an extension derived from its media type")
    void publish_coreAttachment_derivesExtension() {
        publisher.publish(new Attachment("request", "application/json", "{\"a\":1}"));

        assertThat(lifecycle.attachments()).containsExactly(
                new RecordedAttachment("request", "application/json", "json", "{\"a\":1}"));
    }

    @Test
    @DisplayName("a sensitive JSON field in a core attachment body is masked at the sink")
    void publish_sensitiveJsonField_isMaskedAtSink() {
        publisher.publish(new Attachment("grpc-response", "application/json", "{\"token\":\"t-1\",\"status\":\"OK\"}"));

        assertThat(lifecycle.attachments()).containsExactly(
                new RecordedAttachment("grpc-response", "application/json", "json", "{\"token\":\"***\",\"status\":\"OK\"}"));
    }

    @Test
    @DisplayName("an embedded Bearer credential in a core attachment body is masked at the sink")
    void publish_embeddedBearer_isMasked() {
        publisher.publish(new Attachment("trace", "text/plain", "sent Bearer sk-abc123def456 upstream"));

        assertThat(lifecycle.attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.content()).contains("Bearer ***");
            assertThat(attachment.content()).doesNotContain("sk-abc123def456");
        });
    }

    @Test
    @DisplayName("a null attachment is ignored")
    void publish_nullAttachment_isIgnored() {
        publisher.publish(null);

        assertThat(lifecycle.attachments()).isEmpty();
    }

    @Test
    @DisplayName("text and json helpers publish with the right media type and extension")
    void publishText_andJson() {
        publisher.publishText("note", "hello");
        publisher.publishJson("body", "{}");

        assertThat(lifecycle.attachments()).containsExactly(
                new RecordedAttachment("note", "text/plain", "txt", "hello"),
                new RecordedAttachment("body", "application/json", "json", "{}"));
    }

    @Test
    @DisplayName("a null text content publishes nothing")
    void publishText_null_isIgnored() {
        publisher.publishText("note", null);

        assertThat(lifecycle.attachments()).isEmpty();
    }

    @Test
    @DisplayName("text and json helpers also mask secret content at the sink")
    void publishText_andJson_maskSecrets() {
        publisher.publishText("note", "auth was Basic dXNlcjpwYXNzd29yZA==");
        publisher.publishJson("body", "{\"apiKey\":\"k-123\"}");

        assertThat(lifecycle.attachments()).satisfiesExactly(
                note -> assertThat(note.content()).contains("Basic ***").doesNotContain("dXNlcjpwYXNzd29yZA=="),
                body -> assertThat(body.content()).contains("\"apiKey\":\"***\"").doesNotContain("k-123"));
    }

    @Test
    @DisplayName("diagnostics render as a masked key/value block named diagnostics")
    void publishDiagnostics_masksAndRenders() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("attempts", 3);
        diagnostics.put("token", "secret-value");
        diagnostics.put("lastValue", null);

        publisher.publishDiagnostics(diagnostics);

        assertThat(lifecycle.attachments()).hasSize(1);
        RecordedAttachment attachment = lifecycle.attachments().get(0);
        assertThat(attachment.name()).isEqualTo("diagnostics");
        assertThat(attachment.type()).isEqualTo("text/plain");
        assertThat(attachment.content())
                .contains("attempts=3")
                .contains("token=***")
                .contains("lastValue=null")
                .doesNotContain("secret-value");
    }

    @Test
    @DisplayName("empty or null diagnostics produce no attachment")
    void publishDiagnostics_emptyOrNull_isIgnored() {
        publisher.publishDiagnostics(Map.of());
        publisher.publishDiagnostics(null);

        assertThat(lifecycle.attachments()).isEmpty();
    }
}
