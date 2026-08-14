package ru.alfa.stand.test.config;

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
 * The deprecation channel as the file surface actually produces it (ADR-UI-004, {@code OQ-13}).
 *
 * <p>The unit test beside {@code EnvironmentConfigFormat} pins the message; this one runs a real
 * document through the real loader, which is a different claim. The repository has already paid once for
 * the difference: every link of the attachment channel was covered on its own while the composition had
 * never executed, and the binary channel turned out to be dead end to end.
 *
 * <p>What only this level can show is the security requirement of the card. The registry is full of
 * aliases and secret references, the loader has all of them in hand when it warns, and the warning must
 * carry none of them.
 */
class EnvironmentConfigVersionWarningTest {

    /** A document with plenty for a careless message to leak: aliases, a topic name, references. */
    private static final String REGISTRY = """
            environments:
              ift:
                services:
                  client-service:
                    base-url-ref: CLIENT_SERVICE_SECRET_URL_REF
                datasources:
                  client-db:
                    url-ref: CLIENT_DB_URL
                    user-ref: CLIENT_DB_USER
                    password-ref: CLIENT_DB_PASSWORD
            """;

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
    @DisplayName("loading a pre-versioning file warns once, and the warning names nothing out of the registry")
    void loadingAnOlderDocument_warnsWithoutLeakingRegistryContent() {
        EnvironmentConfig.toRegistry(SafeYaml.load(REGISTRY));

        assertThat(warnings()).as("one document loaded, one warning — not one per alias").hasSize(1);
        String message = warnings().get(0).getFormattedMessage();
        assertThat(message).contains("<document>").contains(String.valueOf(EnvironmentConfigFormat.SUPPORTED_VERSION));
        assertThat(message)
                .as("the loader holds every alias and secret reference of the file when it warns; the line must carry none of them")
                .doesNotContain("client-service")
                .doesNotContain("client-db")
                .doesNotContain("CLIENT_SERVICE_SECRET_URL_REF")
                .doesNotContain("CLIENT_DB_PASSWORD")
                .doesNotContain("ift");
    }

    @Test
    @DisplayName("a file already declaring the supported version loads silently")
    void loadingACurrentDocument_saysNothing() {
        EnvironmentConfig.toRegistry(SafeYaml.load("version: " + EnvironmentConfigFormat.SUPPORTED_VERSION + "\n" + REGISTRY));

        assertThat(appender.list).isEmpty();
    }
}
