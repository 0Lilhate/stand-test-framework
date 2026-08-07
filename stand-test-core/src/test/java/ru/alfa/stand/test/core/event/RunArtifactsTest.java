package ru.alfa.stand.test.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the single definition of the run's artefacts directory (UITG-F003)")
class RunArtifactsTest {

    @Test
    @DisplayName("an unset property gives the default directory, so a producer and a sink agree without configuration")
    void unsetProperty_givesTheDefault() {
        assertThat(RunArtifacts.directory(property -> null)).isEqualTo(Paths.get(RunArtifacts.DEFAULT_DIRECTORY));
    }

    @Test
    @DisplayName("a blank value is not a directory: it falls back to the default rather than resolving to the working directory")
    void blankValue_fallsBackToTheDefault() {
        assertThat(RunArtifacts.directory(property -> "   ")).isEqualTo(Paths.get(RunArtifacts.DEFAULT_DIRECTORY));
        assertThat(RunArtifacts.directory(property -> "")).isEqualTo(Paths.get(RunArtifacts.DEFAULT_DIRECTORY));
    }

    @Test
    @DisplayName("a configured value wins and is trimmed — a trailing space in a CI variable must not make a second directory")
    void configuredValue_isTrimmed() {
        assertThat(RunArtifacts.directory(Map.of(RunArtifacts.DIRECTORY_PROPERTY, "  /var/run/artefacts  ")::get))
                .isEqualTo(Paths.get("/var/run/artefacts"));
    }

    @Test
    @DisplayName("only this property is consulted: the resolver reads one name, so the two sides cannot be configured apart")
    void onlyTheOneProperty_isRead() {
        assertThat(RunArtifacts.directory(Map.of("stand.test.artifacts.dir", "/elsewhere")::get))
                .isEqualTo(Paths.get(RunArtifacts.DEFAULT_DIRECTORY));
    }

    @Test
    @DisplayName("a null source is refused rather than silently treated as 'nothing configured'")
    void nullSource_isRefused() {
        assertThatThrownBy(() -> RunArtifacts.directory(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
    }
}
