package ru.alfa.stand.test.core.environment;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The deprecation channel of the registry format (ADR-UI-004, {@code OQ-13}).
 *
 * <p>The policy the ADR settled is <em>no compatibility window</em>: every version from
 * {@link EnvironmentConfigFormat#INITIAL_VERSION} is read indefinitely. The channel exists so a consumer
 * learns their file is behind before that means anything — not to threaten them with a refusal.
 *
 * <p>That pairing is what most of this class is about. A warning is free to be ignored, so the risk here
 * is not a missing line but a <strong>lying</strong> one: a message saying the file will stop being read
 * would be false under the accepted policy, and a warning that threatens a refusal which never comes
 * devalues itself and every real warning beside it. So the wording is pinned as hard as the behaviour —
 * the message must state that the file stays readable, and must not carry the vocabulary of removal.
 */
class EnvironmentConfigFormatLoggingTest {

    /** Phrases that would promise a refusal the accepted policy does not provide for. */
    private static final List<String> PROMISES_OF_REFUSAL =
            List.of("will stop", "no longer", "will fail", "deprecated", "unsupported", "must upgrade", "will be removed");

    private Logger formatLogger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheFormatLogger() {
        formatLogger = (Logger) LoggerFactory.getLogger(EnvironmentConfigFormat.class);
        appender = new ListAppender<>();
        appender.start();
        formatLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheFormatLogger() {
        formatLogger.detachAppender(appender);
        appender.stop();
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("a document behind the supported format warns exactly once, naming both versions")
    void olderVersion_warnsOnceNamingBothVersions() {
        EnvironmentConfigFormat.requireSupported(2, "<document>");

        assertThat(warnings()).as("one document parsed, so one warning — the channel belongs to the parse, not to every alias resolved").hasSize(1);
        assertThat(warnings().get(0).getFormattedMessage())
                .as("the reader must be able to tell what they have from what this SDK reads")
                .contains("2")
                .contains(String.valueOf(EnvironmentConfigFormat.SUPPORTED_VERSION))
                .contains("<document>");
    }

    @Test
    @DisplayName("a document declaring no version warns the same way — it is version 1, which is behind, not wrong")
    void absentVersion_warnsTheSameWayAndNoLouder() {
        int effective = EnvironmentConfigFormat.requireSupported(null, "<document>");

        assertThat(effective).isEqualTo(EnvironmentConfigFormat.INITIAL_VERSION);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0).getLevel())
                .as("absence of the key is a legitimate version 1, so it is reported no louder than any other lagging version")
                .isEqualTo(Level.WARN);
        assertThat(warnings().get(0).getFormattedMessage()).contains(String.valueOf(EnvironmentConfigFormat.INITIAL_VERSION));
    }

    @Test
    @DisplayName("a document declaring the supported version says nothing at all")
    void supportedVersion_doesNotWarn() {
        EnvironmentConfigFormat.requireSupported(EnvironmentConfigFormat.SUPPORTED_VERSION, "<document>");

        assertThat(appender.list).as("warning about a file that is exactly current is pure noise").isEmpty();
    }

    @Test
    @DisplayName("the warning never promises the file will stop being read — under this policy that would be a lie")
    void theWarning_neverPromisesARefusal() {
        EnvironmentConfigFormat.requireSupported(1, "<document>");
        String message = warnings().get(0).getFormattedMessage().toLowerCase(java.util.Locale.ROOT);

        assertThat(PROMISES_OF_REFUSAL)
                .as("ADR-UI-004 accepted 'no window': a warning threatening a refusal that never comes devalues itself and every real warning beside it")
                .allSatisfy(forbidden -> assertThat(message).doesNotContain(forbidden));
        assertThat(message)
                .as("the message must say the opposite out loud, or the reader supplies the threat themselves")
                .contains("readable");
    }

    @Test
    @DisplayName("the warning carries no number beyond the versions it is about")
    void theWarning_carriesNoNumberItWasNotGiven() {
        EnvironmentConfigFormat.requireSupported(2, "<document>");

        Set<String> numbers = new TreeSet<>();
        Matcher digits = Pattern.compile("\\d+").matcher(warnings().get(0).getFormattedMessage());
        while (digits.find()) {
            numbers.add(digits.group());
        }

        assertThat(numbers)
                .as("a stray number in a warning is a leak waiting to happen — a count, a port, an id. Only the version being read, the oldest readable one and the newest supported may appear")
                .containsExactlyInAnyOrder("2", String.valueOf(EnvironmentConfigFormat.INITIAL_VERSION),
                        String.valueOf(EnvironmentConfigFormat.SUPPORTED_VERSION));
    }

    @Test
    @DisplayName("the message differs between the two surfaces only by the location it was handed")
    void theWarning_variesOnlyByLocation() {
        EnvironmentConfigFormat.requireSupported(1, "<document>");
        String fileSurface = warnings().get(0).getFormattedMessage();
        appender.list.clear();
        EnvironmentConfigFormat.requireSupported(1, "stand.test");
        String springSurface = warnings().get(0).getFormattedMessage();

        assertThat(springSurface)
                .as("the two surfaces must say the same thing, or a consumer comparing notes would think they behave differently")
                .isEqualTo(fileSurface.replace("<document>", "stand.test"));
    }
}
