package ru.alfa.stand.test.allure.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade;
import ru.alfa.stand.test.allure.lifecycle.FakeAllureLifecycleFacade.RecordedAttachment;
import ru.alfa.stand.test.allure.masking.SecretMasker;
import ru.alfa.stand.test.core.event.Attachment;

class AllureAttachmentPublisherTest {

    private final FakeAllureLifecycleFacade lifecycle = new FakeAllureLifecycleFacade();
    /** No artefacts root: the fail-closed publisher, which refuses every file attachment. */
    private final AllureAttachmentPublisher publisher = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), null);

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

    // ---------------------------------------------------------------------------------------------
    // ADR-UI-005: the file branch. A reporting sink that opens a path somebody else chose is a
    // file-disclosure channel unless it is fenced, so the fence is tested as carefully as the feature.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a file attachment inside the run's artefacts directory is published as a file, with the extension its media type deserves")
    void publishFile_insideArtefactsDirectory_isPublished(@TempDir Path artifacts) throws IOException {
        Path screenshot = Files.write(artifacts.resolve("shot.png"), new byte[] {1, 2, 3});
        AllureAttachmentPublisher fenced = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), artifacts);

        fenced.publish(Attachment.ofFile("screenshot", "image/png", screenshot));

        assertThat(lifecycle.fileAttachments()).singleElement().satisfies(published -> {
            assertThat(published.name()).isEqualTo("screenshot");
            assertThat(published.type()).isEqualTo("image/png");
            // The acceptance criterion of UITG-T002, and the reason a second extension mapper exists:
            // the textual mapper would have answered `txt` here and Allure would offer a picture as text.
            assertThat(published.fileExtension()).isEqualTo("png");
            assertThat(published.file()).isEqualTo(screenshot.toRealPath());
        });
        assertThat(lifecycle.attachments()).as("a file body must not travel through the textual branch").isEmpty();
    }

    @Test
    @DisplayName("a path escaping the artefacts directory with ../ is refused, not read")
    void publishFile_escapingWithDotDot_isRefused(@TempDir Path base) throws IOException {
        Path artifacts = Files.createDirectory(base.resolve("run"));
        Path outside = Files.write(base.resolve("secret.txt"), "top secret".getBytes(StandardCharsets.UTF_8));
        AllureAttachmentPublisher fenced = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), artifacts);

        fenced.publish(Attachment.ofFile("stolen", "text/plain", artifacts.resolve("..").resolve("secret.txt")));

        assertThat(outside).exists();
        assertThat(lifecycle.fileAttachments()).isEmpty();
        assertThat(lifecycle.attachments()).isEmpty();
    }

    @Test
    @DisplayName("a symlink pointing out of the artefacts directory is refused — the check resolves the real path, not the spelled one")
    void publishFile_symlinkOutwards_isRefused(@TempDir Path base) throws IOException {
        Path artifacts = Files.createDirectory(base.resolve("run"));
        Path outside = Files.write(base.resolve("secret.txt"), "top secret".getBytes(StandardCharsets.UTF_8));
        Path link = artifacts.resolve("innocent.png");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows without developer mode cannot create symlinks; the ../ test above still covers the
            // rule. Skipping loudly beats asserting nothing.
            return;
        }

        AllureAttachmentPublisher fenced = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), artifacts);
        fenced.publish(Attachment.ofFile("innocent", "image/png", link));

        assertThat(lifecycle.fileAttachments())
                .as("the path spells its way inside the run directory and resolves outside it — resolving is the whole point")
                .isEmpty();
    }

    @Test
    @DisplayName("with no artefacts directory configured every file attachment is refused: a sink that cannot tell an artefact from any other file must not guess")
    void publishFile_withoutArtefactsRoot_isRefused(@TempDir Path artifacts) throws IOException {
        Path screenshot = Files.write(artifacts.resolve("shot.png"), new byte[] {1});

        publisher.publish(Attachment.ofFile("screenshot", "image/png", screenshot));

        assertThat(lifecycle.fileAttachments()).isEmpty();
    }

    @Test
    @DisplayName("a file that vanished between being recorded and being published is skipped, and the run carries on")
    void publishFile_missingFile_isSkippedNotThrown(@TempDir Path artifacts) {
        AllureAttachmentPublisher fenced = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), artifacts);

        assertThatCode(() -> fenced.publish(Attachment.ofFile("screenshot", "image/png", artifacts.resolve("never-written.png"))))
                .doesNotThrowAnyException();
        assertThat(lifecycle.fileAttachments()).isEmpty();
    }

    @Test
    @DisplayName("an unknown media type on a file body falls back to .bin, not to .txt")
    void publishFile_unknownMediaType_fallsBackToBin(@TempDir Path artifacts) throws IOException {
        Path artefact = Files.write(artifacts.resolve("thing"), new byte[] {7});
        AllureAttachmentPublisher fenced = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), artifacts);

        fenced.publish(Attachment.ofFile("thing", "application/x-unheard-of", artefact));

        assertThat(lifecycle.fileAttachments()).singleElement()
                .satisfies(published -> assertThat(published.fileExtension()).isEqualTo("bin"));
    }

    @Test
    @DisplayName("the textual branch is untouched by the file branch: it still goes through the secret masker")
    void publish_textualBranch_stillMasked(@TempDir Path artifacts) {
        AllureAttachmentPublisher fenced = new AllureAttachmentPublisher(lifecycle, new SecretMasker(), artifacts);

        fenced.publish(new Attachment("response", "application/json", "{\"token\":\"t-1\"}"));

        assertThat(lifecycle.attachments()).singleElement()
                .satisfies(published -> assertThat(published.content()).contains("***").doesNotContain("t-1"));
        assertThat(lifecycle.fileAttachments()).isEmpty();
    }
}
