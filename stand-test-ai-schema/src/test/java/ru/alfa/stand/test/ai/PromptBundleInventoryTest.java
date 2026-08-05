package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the COMPOSITION of the authoring bundle to a committed snapshot. {@link BundleParityTest}
 * compares the two copies to each other, which is blind to the one failure mode that matters most
 * before a mass frontmatter migration: an asset deleted or renamed in BOTH copies at once stays
 * perfectly "in parity" and vanishes unnoticed.
 *
 * <p>The snapshot {@code prompt-bundle-inventory.txt} therefore lists every shipped asset path of
 * both copies, and this test fails naming the path whenever the bundle gains, loses or renames one.
 * Updating the snapshot is a one-line, deliberate act — which is the point: the migration that edits
 * 32 prompts must be provably additive.
 *
 * <p>Covered are the seven asset directories of each copy ({@code commands}, {@code rules},
 * {@code skills}, {@code workflows}, {@code hooks}, {@code agents}, {@code plugin} — {@code agents}
 * exists only in the Claude Code copy, {@code plugin} only in the opencode one) plus the bundle-root
 * assets and the opencode-only loader manual. That
 * is exactly the file set {@code stand-test-ai-schema/build.gradle.kts} declares as a test input, so
 * a change to any pinned file re-runs this test instead of returning a stale {@code FROM-CACHE}
 * result. The machine-local residue that also lives in those bundle directories ({@code .env*},
 * {@code settings.local.json}, {@code scheduled_tasks.lock}) sits at the bundle root, outside the
 * asset directories, and is thus excluded without needing a name filter.
 */
class PromptBundleInventoryTest {

    /** The two shipped copies of the same bundle, spelled as they appear under {@code docs/ai-agent}. */
    private static final List<String> BUNDLES = List.of(".claude", ".opencode");

    /** Asset directories that carry the prompts themselves plus their templates, examples and checklists. */
    private static final List<String> ASSET_DIRECTORIES = List.of("commands", "rules", "skills", "workflows", "hooks", "agents", "plugin");

    /**
     * Single files that are assets in their own right, in whichever copy carries them.
     *
     * <p>{@code settings.json} is the Claude half of the permission policy and {@code .gitignore}
     * travels with the bundle so a consumer inherits its protection. Both belong to the composition
     * for the same reason the prompts do: an installer copies what this snapshot lists, so an asset
     * missing from it is an asset that silently never arrives.
     */
    private static final List<String> ASSET_FILES = List.of("settings.json", ".gitignore");

    /**
     * Files that legitimately exist only in the opencode copy.
     *
     * <p>{@code plugin/stand-guard.js} is the opencode half of the ENFORCEMENT layer: the same guard,
     * bound to this host's tool events because opencode has no {@code settings.json} to bind it with.
     * It is listed here rather than copied across — Claude Code binds the guard through
     * {@code settings.json} and has no use for a plugin file.
     *
     * <p>The rest are bundle-root loader files. Its loader config
     * ({@code opencode.json}) and credential template ({@code env.template}) are deliberately absent:
     * {@code opencode.json} gets its own safety test, and neither is a prompt.
     */
    private static final Set<String> OPENCODE_ONLY = new TreeSet<>(Set.of("AGENTS.md", "plugin/stand-guard.js"));

    /**
     * Paths that legitimately exist only in the Claude copy: the enforcement layer.
     *
     * <p>Hooks, subagents and {@code settings.json} are Claude Code mechanisms. opencode reaches the
     * same checker through a plugin and states its permissions in {@code opencode.json}, so there is
     * nothing for it to carry under these names. Listing the prefixes keeps the asymmetry deliberate:
     * any OTHER asset appearing in one copy alone still fails, which is what makes "copy it across"
     * the default answer.
     */
    private static final List<String> CLAUDE_ONLY_PREFIXES = List.of("settings.json", "hooks/", "agents/");

    private static final String SNAPSHOT = "prompt-bundle-inventory.txt";

    /**
     * A wrongly resolved bundle root would walk nothing and leave every set empty — and an empty set
     * equals an empty set. The floor makes that failure loud instead of green.
     */
    private static final int MINIMUM_ENTRIES = 30;

    private static Path aiAgentRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent"));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("docs/ai-agent not found upwards from " + Paths.get("").toAbsolutePath());
    }

    /** Every shipped asset path of both copies, relative to {@code docs/ai-agent}, sorted. */
    private static TreeSet<String> actualInventory() {
        Path root = aiAgentRoot();
        TreeSet<String> paths = new TreeSet<>();
        for (String bundle : BUNDLES) {
            for (String directory : ASSET_DIRECTORIES) {
                Path assets = root.resolve(bundle).resolve(directory);
                if (!Files.isDirectory(assets)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(assets)) {
                    walk.filter(Files::isRegularFile).forEach(file -> paths.add(relative(root, file)));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to walk " + assets, e);
                }
            }
        }
        for (String bundle : BUNDLES) {
            for (String assetFile : ASSET_FILES) {
                Path file = root.resolve(bundle).resolve(assetFile);
                if (Files.isRegularFile(file)) {
                    paths.add(relative(root, file));
                }
            }
        }
        for (String loaderFile : OPENCODE_ONLY) {
            Path file = root.resolve(".opencode").resolve(loaderFile);
            if (Files.isRegularFile(file)) {
                paths.add(relative(root, file));
            }
        }
        return paths;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** The committed snapshot, comment and blank lines dropped, in file order. */
    private static List<String> snapshotLines() {
        try (InputStream in = PromptBundleInventoryTest.class.getResourceAsStream("/" + SNAPSHOT)) {
            if (in == null) {
                throw new IllegalStateException("test resource " + SNAPSHOT + " is missing");
            }
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1)) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + SNAPSHOT, e);
        }
    }

    private static TreeSet<String> stripPrefix(List<String> lines, String bundle) {
        TreeSet<String> stripped = new TreeSet<>();
        String prefix = bundle + "/";
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                stripped.add(line.substring(prefix.length()));
            }
        }
        return stripped;
    }

    @Test
    @DisplayName("the bundle ships exactly the prompts the committed snapshot lists")
    void inventory_matchesSnapshot() {
        TreeSet<String> actual = actualInventory();
        TreeSet<String> snapshot = new TreeSet<>(snapshotLines());

        Set<String> removed = new TreeSet<>(snapshot);
        removed.removeAll(actual);
        Set<String> added = new TreeSet<>(actual);
        added.removeAll(snapshot);

        // Soft, so a RENAME reports the path that vanished and the path that appeared in one run
        // instead of only the first half of the story.
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(removed).as("these prompts are in the snapshot but no longer in the bundle — restore them, or drop them from src/test/resources/" + SNAPSHOT + " if the removal is intended").isEmpty();
            softly.assertThat(added).as("these prompts are in the bundle but not in the snapshot — add them to src/test/resources/" + SNAPSHOT + ", and copy them into BOTH bundle copies").isEmpty();
        });
    }

    @Test
    @DisplayName("the snapshot describes both copies identically, apart from the opencode-only loader manual")
    void snapshot_coversBothCopiesAlike() {
        List<String> lines = snapshotLines();
        TreeSet<String> claude = stripPrefix(lines, ".claude");
        TreeSet<String> opencode = stripPrefix(lines, ".opencode");

        Set<String> onlyInOpencode = new TreeSet<>(opencode);
        onlyInOpencode.removeAll(claude);
        Set<String> onlyInClaude = new TreeSet<>(claude);
        onlyInClaude.removeAll(opencode);
        onlyInClaude.removeIf(path -> CLAUDE_ONLY_PREFIXES.stream().anyMatch(path::startsWith));

        assertThat(onlyInOpencode).as("the snapshot may list an opencode-only asset only if it is a loader file").isEqualTo(OPENCODE_ONLY);
        assertThat(onlyInClaude).as("the snapshot lists these for .claude only — a half-updated snapshot hides an asset missing from the .opencode copy").isEmpty();
    }

    @Test
    @DisplayName("the snapshot is sorted, non-trivial, and speaks only of the two bundle copies")
    void snapshot_isWellFormed() {
        List<String> lines = snapshotLines();
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(String::compareTo);

        assertThat(lines).as("keep " + SNAPSHOT + " lexicographically sorted so its diffs stay reviewable").isEqualTo(sorted);
        assertThat(lines).as("no line may repeat").doesNotHaveDuplicates();
        assertThat(lines).as("a snapshot this small means the bundle root resolved wrongly and the test is green by accident").hasSizeGreaterThanOrEqualTo(MINIMUM_ENTRIES);
        assertThat(lines).as("every entry must name one of the two bundle copies").allMatch(line -> line.startsWith(".claude/") || line.startsWith(".opencode/"));
    }
}
