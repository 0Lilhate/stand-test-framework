package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The training materials point at files that exist (task UITG-S030).
 *
 * <p>A training document is read by the person with the least means to tell a stale reference from a
 * current one: the newcomer follows the link, finds nothing, and has no way to know whether the file
 * moved, was renamed, or never existed. The walkthrough states as its own rule that every artifact it
 * names is present in the repository — this test is what makes that a fact rather than an intention.
 *
 * <p>It checks the links, not the prose. Whether the described API still behaves as described is a
 * different question, answered by the compile check the walkthrough itself publishes.
 */
class TrainingMaterialLinksTest {

    /** Documents whose links are the reader's map: every one of them must resolve. */
    private static final List<String> TRAINING_DOCUMENTS = List.of(
            "docs/ai-agent/example-ui-test-case-walkthrough.md",
            "docs/ai-agent/usage-guide.md");

    /** A markdown link: `[text](target)`. Anchors and external URLs are filtered out below. */
    private static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\(([^)\\s]+)\\)");

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Link> repositoryLinksOf(String document) {
        Path file = repositoryRoot().resolve(document);
        assertThat(file).as("the training material itself must exist").isRegularFile();

        List<Link> links = new ArrayList<>();
        Matcher matcher = LINK.matcher(read(file));
        while (matcher.find()) {
            String target = matcher.group(1);
            // Anchors, external addresses and mail links point outside the repository; a test that
            // resolved them would either need the network or would quietly pass on everything.
            if (target.startsWith("#") || target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                continue;
            }
            links.add(new Link(document, target.split("#", 2)[0]));
        }
        return links;
    }

    @Test
    @DisplayName("every repository link of the training materials resolves to a real file")
    void trainingLinksResolve() {
        List<String> broken = new ArrayList<>();

        for (String document : TRAINING_DOCUMENTS) {
            Path directory = repositoryRoot().resolve(document).getParent();
            for (Link link : repositoryLinksOf(document)) {
                Path target = directory.resolve(link.target()).normalize();
                if (!Files.exists(target)) {
                    broken.add(link.document() + " → " + link.target());
                }
            }
        }

        assertThat(broken)
                .as("a training document is read by the person least able to tell a stale link from a current one")
                .isEmpty();
    }

    @Test
    @DisplayName("the walkthrough names every stage of the UI branch and the source order")
    void walkthroughCoversTheWholeBranch() {
        String walkthrough = read(repositoryRoot().resolve("docs/ai-agent/example-ui-test-case-walkthrough.md"));

        assertThat(walkthrough)
                .as("the source order is the rule the branch exists to enforce; a walkthrough without it teaches the wrong habit")
                .contains("база знаний → живой DEV/IFT-стенд → вопрос человеку");
        for (String stage : List.of("intake", "полнота", "разведка", "дизайн", "Page Objects", "авторинг", "safety-ревью", "quality-ревью", "отчёт")) {
            assertThat(walkthrough).as("stage '%s' must be described", stage).contains(stage);
        }
    }

    @Test
    @DisplayName("no training material carries an address, a login or a password")
    void trainingMaterialsCarryNoSecretsAndNoAddresses() {
        for (String document : TRAINING_DOCUMENTS) {
            String text = read(repositoryRoot().resolve(document));
            for (String line : text.split("\\R")) {
                // `https://` appears in the kit's own prose about forbidden addresses, so the check is
                // for a STAND address: a host with a dot, not the scheme alone.
                assertThat(line)
                        .as("'%s': an example carrying a real stand address is the first thing a reader copies", line.strip())
                        .doesNotContainPattern("https?://[a-z0-9-]+\\.[a-z0-9.-]+");
            }
        }
    }

    /** One markdown link of one document: where it was written, and what it points at. */
    private record Link(String document, String target) {
    }
}
