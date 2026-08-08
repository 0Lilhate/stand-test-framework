package ru.alfa.stand.test.starter;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.alfa.stand.test.core.environment.EnvironmentConfigFormat;

/**
 * The deprecation channel on the Spring surface (ADR-UI-004, {@code OQ-13}).
 *
 * <p>The card asks for the two surfaces to behave identically, and the reason that is a test rather than
 * an observation is the standing risk of this pair: the file loader and
 * {@link EnvironmentRegistryFactory} are two hand-maintained mappers of one schema, and their drift is a
 * recorded project risk. The version constant is shared precisely so they cannot disagree — this pins
 * that the warning derived from it reaches the Spring path too, and says the same thing there.
 */
class StarterVersionWarningTest {

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

    private static StandTestProperties propertiesDeclaring(Integer version) {
        StandTestProperties properties = new StandTestProperties();
        properties.setVersion(version);
        StandTestProperties.Environment ift = new StandTestProperties.Environment();
        StandTestProperties.Service service = new StandTestProperties.Service();
        service.setBaseUrlRef("CLIENT_SERVICE_URL");
        ift.getServices().put("client-service", service);
        properties.getEnvironments().put("ift", ift);
        return properties;
    }

    @Test
    @DisplayName("Spring properties declaring no version warn once, naming the Spring location rather than a file")
    void starterSurface_warnsWhenBehind() {
        EnvironmentRegistryFactory.build(propertiesDeclaring(null));

        assertThat(warnings()).hasSize(1);
        String message = warnings().get(0).getFormattedMessage();
        assertThat(message)
                .as("a Spring consumer has no stand-test-environments.yml to look at, so the location must be the property prefix")
                .contains("stand.test")
                .contains(String.valueOf(EnvironmentConfigFormat.SUPPORTED_VERSION));
        assertThat(message).doesNotContain("client-service").doesNotContain("CLIENT_SERVICE_URL");
    }

    @Test
    @DisplayName("Spring properties at the supported version are silent, exactly as the file surface is")
    void starterSurface_isSilentWhenCurrent() {
        EnvironmentRegistryFactory.build(propertiesDeclaring(EnvironmentConfigFormat.SUPPORTED_VERSION));

        assertThat(appender.list).isEmpty();
    }
}
