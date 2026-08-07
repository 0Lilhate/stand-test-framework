package ru.alfa.stand.test.core.event;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The single definition of the directory a run writes its file-backed artefacts to.
 *
 * <p>A file {@link Attachment} is meaningless on its own: a path is only an artefact of <em>this</em> run
 * if it lies inside the run's artefacts directory, and a reporting sink that cannot name that directory
 * has to refuse every file it is handed (see the fail-closed rule on the Allure attachment publisher).
 * So the directory has two readers who never see each other — the <em>producer</em> in an adapter, which
 * writes the screenshot, and the <em>sink</em> in a reporting module, which proves the path belongs to the
 * run before opening it. Neither may depend on the other: adapters do not depend on reporting modules and
 * reporting modules do not depend on adapters.
 *
 * <p>Two readers of one value with no edge between them is exactly the shape that drifts, and it did:
 * the producer wrote its screenshots into {@code build/stand-test-ui} while the SPI-constructed sink was
 * built with no directory at all, so every picture was silently refused. This class is the anti-drift
 * device — the same one {@code EnvironmentConfigFormat} is for the registry version: one constant, read
 * by both sides, so a change cannot land on one side only.
 *
 * <p><strong>On the spelling of the property.</strong> {@code stand.test.ui.artifacts.dir} carries
 * {@code ui} because the UI adapter was the first producer of file attachments and the knob is already
 * documented and shipped under that name. Renaming it would break consumers' configurations to buy
 * nothing but tidiness, so the name stays and this javadoc explains it. Naming a property string is not a
 * dependency: core still knows nothing about any adapter, and this class holds constants plus a pure
 * function — it reads no system property and touches no file itself.
 */
public final class RunArtifacts {

    /** System property naming the directory a run writes its file-backed artefacts to. */
    public static final String DIRECTORY_PROPERTY = "stand.test.ui.artifacts.dir";

    /** The directory used when the property is not set: relative to the working directory of the run. */
    public static final String DEFAULT_DIRECTORY = "build/stand-test-ui";

    private RunArtifacts() {
    }

    /**
     * Resolves the run's artefacts directory from a property source.
     *
     * <p>The source is passed in rather than read from {@link System#getProperties()} here so the
     * resolution stays a pure function: callers hand it {@code System::getProperty} in production and a
     * map lookup in tests.
     *
     * @param source the property source, typically {@code System::getProperty}
     * @return the configured directory, or {@link #DEFAULT_DIRECTORY} when the property is unset or blank
     */
    public static Path directory(UnaryOperator<String> source) {
        Objects.requireNonNull(source, "source must not be null");
        String configured = source.apply(DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Paths.get(DEFAULT_DIRECTORY);
        }
        return Paths.get(configured.trim());
    }
}
