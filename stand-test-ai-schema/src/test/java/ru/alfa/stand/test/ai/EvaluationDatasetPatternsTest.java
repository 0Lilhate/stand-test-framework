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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The forbidden-content patterns of the evaluation corpus, checked for the one defect that makes them
 * silently useless.
 *
 * <p>{@code forbiddenArtifactPatterns} is how injection resistance is measured: a regex that must
 * never appear in anything the agent produced. Three of them shipped double-escaped —
 * {@code Thread\\.sleep} where a plain YAML scalar processes no escapes, so the value reached the
 * regex engine as "Thread, a literal backslash, any character, sleep". It cannot match
 * {@code Thread.sleep}, and the case measuring whether a guardrail violation reaches an artifact
 * passed by never being able to fail.
 *
 * <p>That is the worst shape a check can take: present, green, and blind. The batch runner
 * ({@code stand-batch.mjs}) reads these patterns, so it inherited the blindness — which is how they
 * were noticed at all.
 *
 * <p>The invariant below is general rather than a list of the three: a pattern must match the literal
 * text it forbids. Strip its escapes and the pattern has to find the result. A correctly written
 * {@code Thread\.sleep} matches {@code Thread.sleep}; the broken one demands a backslash that no
 * generated artifact will ever contain.
 */
class EvaluationDatasetPatternsTest {

    private static final String DATASET = "docs/agent-evaluation/dataset/cases";

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

    /** Every `forbiddenArtifactPatterns` item of every case, as `case-id → pattern`. */
    private static List<String[]> patterns() {
        List<String[]> found = new ArrayList<>();
        Path root = repositoryRoot().resolve(DATASET);
        try (Stream<Path> walk = Files.walk(root, 2)) {
            walk.filter(path -> path.getFileName().toString().equals("case.yml")).sorted().forEach(path -> {
                String caseId = path.getParent().getFileName().toString();
                boolean inside = false;
                int indent = 0;
                for (String line : read(path).split("\n")) {
                    if (line.strip().equals("forbiddenArtifactPatterns:")) {
                        inside = true;
                        indent = line.length() - line.stripLeading().length();
                        continue;
                    }
                    if (!inside) {
                        continue;
                    }
                    String trimmed = line.stripLeading();
                    int own = line.length() - trimmed.length();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    if (own < indent || !trimmed.startsWith("- ")) {
                        inside = false;
                        continue;
                    }
                    found.add(new String[] {caseId, trimmed.substring(2).strip()});
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
        return found;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    @Test
    @DisplayName("the corpus declares forbidden patterns at all — an empty sweep would pass this file vacuously")
    void patterns_exist() {
        assertThat(patterns())
                .as("if nothing is found here the walk is wrong, and every assertion below becomes a check of the empty set")
                .hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("every forbidden pattern compiles")
    void patterns_compile() {
        List<String> broken = new ArrayList<>();
        for (String[] entry : patterns()) {
            try {
                Pattern.compile(entry[1]);
            } catch (PatternSyntaxException e) {
                broken.add(entry[0] + ": " + entry[1] + " — " + e.getDescription());
            }
        }

        assertThat(broken).as("a pattern that does not compile is a check that throws instead of checking").isEmpty();
    }

    @Test
    @DisplayName("every forbidden pattern matches the literal text it forbids")
    void patterns_matchWhatTheyForbid() {
        List<String> blind = new ArrayList<>();
        for (String[] entry : patterns()) {
            // The pattern with its escapes removed IS the text it exists to forbid: `Thread\.sleep`
            // forbids `Thread.sleep`. If the pattern cannot find that, it can find nothing.
            String literal = entry[1].replace("\\", "");
            if (!Pattern.compile(entry[1]).matcher(literal).find()) {
                blind.add(entry[0] + ": '" + entry[1] + "' не находит '" + literal + "'");
            }
        }

        assertThat(blind)
                .as("a plain YAML scalar processes no escapes, so `Thread\\\\.sleep` reaches the engine demanding a literal backslash — present, green, and unable to fail")
                .isEmpty();
    }

    @Test
    @DisplayName("no forbidden pattern carries a doubled backslash — in a plain scalar there is no reason to")
    void patterns_areNotDoubleEscaped() {
        List<String> doubled = new ArrayList<>();
        for (String[] entry : patterns()) {
            if (entry[1].contains("\\\\")) {
                doubled.add(entry[0] + ": " + entry[1]);
            }
        }

        assertThat(doubled)
                .as("stated separately from the invariant above because this is the exact typo that happened, and naming it is what makes the next one obvious")
                .isEmpty();
    }
}
