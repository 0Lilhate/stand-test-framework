package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.exception.StandTestException;

class EnvironmentConfigFormatTest {

    @Test
    @DisplayName("a document declaring no version is read as version 1 — every file written before versioning existed stays valid")
    void absentVersion_isInitial() {
        assertThat(EnvironmentConfigFormat.requireSupported(null, "<document>"))
                .isEqualTo(EnvironmentConfigFormat.INITIAL_VERSION);
    }

    @Test
    @DisplayName("a version at or below the supported one is read")
    void supportedVersions_areRead() {
        assertThat(EnvironmentConfigFormat.requireSupported(1, "<document>")).isEqualTo(1);
        assertThat(EnvironmentConfigFormat.requireSupported(EnvironmentConfigFormat.SUPPORTED_VERSION, "<document>"))
                .isEqualTo(EnvironmentConfigFormat.SUPPORTED_VERSION);
        assertThat(EnvironmentConfigFormat.requireSupported(2L, "<document>")).isEqualTo(2);
    }

    @Test
    @DisplayName("a newer version is refused with a message naming both versions and the action — not with 'unknown key'")
    void newerVersion_failsWithVersionMessage() {
        int newer = EnvironmentConfigFormat.SUPPORTED_VERSION + 1;

        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSupported(newer, "<document>"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("format version " + newer)
                .hasMessageContaining("up to " + EnvironmentConfigFormat.SUPPORTED_VERSION)
                .hasMessageContaining("upgrade the stand-test-* dependencies")
                .hasMessageNotContaining("Unknown field");
    }

    @Test
    @DisplayName("a non-integer or non-positive version is a configuration error — fail-closed is preserved")
    void malformedVersion_isRejected() {
        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSupported("2", "<document>"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number")
                .hasMessageContaining("String");
        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSupported(2.0d, "<document>"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("whole number");
        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSupported(0, "<document>"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSupported(-1, "<document>"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("the version message says 'format version', not 'SDK version' — the two must not be confused")
    void versionMessage_namesTheFormat() {
        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSupported("2", "<document>"))
                .hasMessageContaining("FORMAT version (not the SDK version)");
    }

    @Test
    @DisplayName("a section introduced after version 1 requires the document to declare at least that version")
    void laterSection_requiresItsVersion() {
        assertThatCode(() -> EnvironmentConfigFormat.requireSectionSupported(
                2, "ui-applications", EnvironmentConfigFormat.UI_APPLICATIONS_SINCE_VERSION, "environments.ift.ui-applications"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> EnvironmentConfigFormat.requireSectionSupported(
                1, "ui-applications", EnvironmentConfigFormat.UI_APPLICATIONS_SINCE_VERSION, "environments.ift.ui-applications"))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining("ui-applications")
                .hasMessageContaining("requires environment registry format version 2")
                .hasMessageContaining("declares version 1")
                .hasMessageContaining("stand.test.version: 2");
    }

    @Test
    @DisplayName("the UI section is exactly what version 2 introduced, and version 2 is what this SDK supports")
    void uiApplicationsSection_arrivedInTheSupportedVersion() {
        assertThat(EnvironmentConfigFormat.UI_APPLICATIONS_SINCE_VERSION).isEqualTo(2);
        assertThat(EnvironmentConfigFormat.SUPPORTED_VERSION)
                .as("a section this SDK parses cannot require a version this SDK refuses to read")
                .isGreaterThanOrEqualTo(EnvironmentConfigFormat.UI_APPLICATIONS_SINCE_VERSION);
    }
}
