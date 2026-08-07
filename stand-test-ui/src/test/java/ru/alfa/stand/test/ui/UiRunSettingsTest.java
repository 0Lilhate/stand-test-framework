package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiRunSettingsTest {

    @Test
    @DisplayName("headless chromium is the default: CI is where these tests live")
    void defaultsAreHeadlessChromium() {
        UiRunSettings settings = UiRunSettings.fromProperties(property -> null);

        assertThat(settings.headless()).isTrue();
        assertThat(settings.browser()).isEqualTo("chromium");
        assertThat(settings.actionTimeout()).isEqualTo(Duration.ofMillis(UiStepParameters.DEFAULT_ACTION_TIMEOUT_MILLIS));
        assertThat(settings.navigationTimeout()).isEqualTo(Duration.ofMillis(UiStepParameters.DEFAULT_TIMEOUT_MILLIS));
    }

    @Test
    @DisplayName("a developer switches to a visible browser with one property, without touching a scenario")
    void headedModeIsOneProperty() {
        Map<String, String> properties = Map.of(
                UiRunSettings.HEADLESS_PROPERTY, "false",
                UiRunSettings.BROWSER_PROPERTY, "firefox",
                UiRunSettings.ACTION_TIMEOUT_PROPERTY, "2500",
                UiRunSettings.NAVIGATION_TIMEOUT_PROPERTY, "7000");

        UiRunSettings settings = UiRunSettings.fromProperties(properties::get);

        assertThat(settings.headless()).isFalse();
        assertThat(settings.browser()).isEqualTo("firefox");
        assertThat(settings.actionTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(settings.navigationTimeout()).isEqualTo(Duration.ofMillis(7000));
    }

    @Test
    @DisplayName("a nonsensical property is refused loudly rather than silently ignored")
    void malformedPropertiesAreRefused() {
        assertThatThrownBy(() -> UiRunSettings.fromProperties(Map.of(UiRunSettings.ACTION_TIMEOUT_PROPERTY, "soon")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("milliseconds");
        assertThatThrownBy(() -> UiRunSettings.fromProperties(Map.of(UiRunSettings.ACTION_TIMEOUT_PROPERTY, "0")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
        assertThatThrownBy(() -> new UiRunSettings(true, " ", Duration.ofSeconds(1), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UiRunSettings(true, "chromium", Duration.ZERO, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the system-property surface is what the module documents, and it is read from the real properties")
    void systemPropertiesAreRead() {
        assertThat(UiRunSettings.fromSystemProperties().browser()).isEqualTo(System.getProperty(UiRunSettings.BROWSER_PROPERTY, UiRunSettings.DEFAULT_BROWSER));
    }

    @Test
    @DisplayName("artefact retention defaults to a week, no longer than the CI report retention")
    void retentionDefaultsToAWeek() {
        UiRunSettings settings = UiRunSettings.fromProperties(property -> null);
        assertThat(settings.artifactRetention()).isEqualTo(UiRunSettings.DEFAULT_ARTIFACT_RETENTION);
    }

    @Test
    @DisplayName("retention is configurable by system property in whole days")
    void retentionIsOneProperty() {
        UiRunSettings settings = UiRunSettings.fromProperties(
                Map.of(UiRunSettings.ARTIFACTS_RETENTION_DAYS_PROPERTY, "14")::get);

        assertThat(settings.artifactRetention()).isEqualTo(Duration.ofDays(14));
    }

    @Test
    @DisplayName("a misconfigured retention is refused loudly rather than silently falling back")
    void malformedRetentionIsRefused() {
        assertThatThrownBy(() -> UiRunSettings.fromProperties(Map.of(UiRunSettings.ARTIFACTS_RETENTION_DAYS_PROPERTY, "soon")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("days");
        assertThatThrownBy(() -> UiRunSettings.fromProperties(Map.of(UiRunSettings.ARTIFACTS_RETENTION_DAYS_PROPERTY, "0")::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }
}
