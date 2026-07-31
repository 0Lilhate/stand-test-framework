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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the two copies of the authoring bundle to each other. {@code docs/ai-agent/.claude} and
 * {@code docs/ai-agent/.opencode} ship the same assets for two tools, and README states they are the
 * same bundle — but nothing enforced it, which is exactly how the gRPC matcher set came to be current
 * in one copy and stale in the other for a whole release.
 *
 * <p>The intentional differences are enumerated below and checked rather than waved through: the
 * opencode-only files must be the ones listed, and a file that differs in content must differ ONLY by
 * the {@code .claude} ⇄ {@code .opencode} path spelling. Any other divergence is drift.
 */
class BundleParityTest {

    /** Files that legitimately exist only in the opencode bundle (its loader config and manual). */
    private static final Set<String> OPENCODE_ONLY = new TreeSet<>(Set.of("AGENTS.md", "opencode.json", "env.template"));

    /**
     * Files whose content legitimately differs, because they spell the bundle directory in their own
     * prose. Their difference must vanish once the path spelling is normalised.
     */
    private static final Set<String> PATH_ADAPTED = new TreeSet<>(Set.of(
            "rules/stand-test-guardrails.md",
            "rules/stand-test-pipeline.md",
            "commands/stand-test-bootstrap-kb.md",
            "skills/stand-test-kb-bootstrap/SKILL.md",
            "skills/stand-test-kb-lookup/SKILL.md",
            "skills/stand-test-java-dsl-authoring/example-provisioned-prelude.java"));

    /**
     * Never part of the shipped bundle: machine-local, gitignored, or runtime residue.
     *
     * <p>Excluded from the parity comparison — and, since {@link #noMachineLocalFileSitsInTheBundle},
     * forbidden from the directory outright. The distinction matters: exclusion made these files
     * invisible to every test, which is how one of them came to sit in the bundle holding a
     * developer's allow-list, absolute paths into an unrelated repository and a curl command with DEV
     * stand credentials — one {@code cp -R} away from every consumer that installed the kit.
     */
    private static final Set<String> NOT_SHIPPED = new TreeSet<>(Set.of("settings.local.json", "scheduled_tasks.lock"));

    /** Name shapes that carry machine-local state or secrets, wherever in the bundle they appear. */
    private static final List<String> FORBIDDEN_NAME_PREFIXES = List.of(".env", ".fetched-");

    /**
     * Assets that legitimately exist only in the Claude copy: the enforcement layer.
     *
     * <p>Subagents and {@code settings.json} are Claude Code mechanisms with no byte-identical opencode
     * twin — opencode declares agents in its own format and expresses permissions in
     * {@code opencode.json}. Listing them here rather than widening the walk keeps the asymmetry
     * deliberate: an asset that lands in {@code .claude/} without appearing on this prefix list still
     * fails the parity check, which is what makes "copy it across" the default answer.
     *
     * <p>{@code hooks/} used to be on this list and is not any more. The guard is plain Node and cares
     * nothing for the host: what is Claude-specific is the WIRING — the events in {@code settings.json}
     * — not the code. While the directory shipped once, the second bundle carried eighteen references
     * to {@code hooks/stand-guard.mjs} and no {@code hooks/} at all, so its kb-lookup skill instructed
     * the model to establish something "MECHANICALLY, never from memory" with a command that was not
     * there. The hooks now ship to both and are compared byte for byte; every path they used to spell
     * as {@code .claude} is derived from the installed directory instead, which is what lets them be
     * identical rather than path-adapted.
     *
     * <p>What the two copies must NOT diverge on is policy, and file-level parity cannot see that —
     * {@code opencode.json} is opencode-only. {@code ClaudeSettingsSafetyTest} owns that comparison.
     */
    private static final Set<String> CLAUDE_ONLY_PREFIXES = new TreeSet<>(Set.of("settings.json", "agents/"));

    private static boolean isClaudeOnly(String relativePath) {
        return CLAUDE_ONLY_PREFIXES.stream().anyMatch(relativePath::startsWith);
    }

    private static Path bundleRoot(String bundle) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent", bundle));
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("bundle " + bundle + " not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static TreeMap<String, Path> shippedFiles(String bundle) {
        Path root = bundleRoot(bundle);
        TreeMap<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String relative = root.relativize(path).toString();
                String name = path.getFileName().toString();
                if (!NOT_SHIPPED.contains(name) && !name.startsWith(".fetched-") && !name.startsWith(".env")) {
                    files.put(relative, path);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + root, e);
        }
        return files;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * A command an asset tells the model to run must exist in the bundle that asset ships in.
     *
     * <p>This is the check whose absence let the opencode copy carry eighteen references to
     * {@code hooks/stand-guard.mjs} while shipping no {@code hooks/} directory. The kb-lookup skill was
     * the sharp end: it instructs the model to establish attestation "MECHANICALLY, never from memory"
     * by running {@code kb-status} — a command that, on that host, was not there. An instruction to run
     * a missing command does not fail loudly; it degrades into the recollection the instruction exists
     * to forbid.
     *
     * <p>Both spellings are read. {@code <bundle>/hooks/…} is the placeholder the shared assets use so
     * they can stay byte-identical between copies, and the literal directory name is what the
     * path-adapted ones use.
     */
    @Test
    @DisplayName("every command an asset names resolves to a file in the same bundle")
    void referencedCommands_exist() {
        Pattern reference = Pattern.compile("(?:<bundle>|\\.claude|\\.opencode)/(hooks/[A-Za-z0-9_.-]+\\.mjs)");
        List<String> dangling = new ArrayList<>();
        List<String> seen = new ArrayList<>();

        for (String bundle : List.of(".claude", ".opencode")) {
            Path root = bundleRoot(bundle);
            for (var entry : shippedFiles(bundle).entrySet()) {
                if (!entry.getKey().endsWith(".md")) {
                    continue;
                }
                Matcher matcher = reference.matcher(read(entry.getValue()));
                while (matcher.find()) {
                    seen.add(bundle + "/" + entry.getKey());
                    if (!Files.exists(root.resolve(matcher.group(1)))) {
                        dangling.add(bundle + "/" + entry.getKey() + " → " + matcher.group(1));
                    }
                }
            }
        }

        assertThat(seen)
                .as("the pattern found no command reference at all — an empty sweep passes this test by checking "
                        + "nothing, which is the failure it was written to catch one level up")
                .isNotEmpty();
        assertThat(dangling)
                .as("an asset that tells the model to run a command the bundle does not carry is worse than one that "
                        + "says nothing: the instruction reads as mechanical and resolves to memory")
                .isEmpty();
    }

    @Test
    @DisplayName("no machine-local file sits in the bundle at all — being excluded from parity is not the same as being absent")
    void noMachineLocalFileSitsInTheBundle() {
        List<String> found = new ArrayList<>();
        for (String bundle : List.of(".claude", ".opencode")) {
            Path root = bundleRoot(bundle);
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    String name = path.getFileName().toString();
                    boolean forbidden = NOT_SHIPPED.contains(name)
                            || FORBIDDEN_NAME_PREFIXES.stream().anyMatch(name::startsWith);
                    if (forbidden) {
                        found.add(bundle + "/" + root.relativize(path).toString().replace('\\', '/'));
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + root, e);
            }
        }

        assertThat(found)
                .as("these are gitignored, so they never reach a remote — but the install instruction copies the directory, "
                        + "and a name without a leading dot travels with it. Machine-local config belongs outside the bundle")
                .isEmpty();
    }

    @Test
    @DisplayName("both bundles ship the same file set, apart from the opencode-only loader files")
    void fileSets_match() {
        Set<String> claude = new TreeSet<>(shippedFiles(".claude").keySet());
        Set<String> opencode = new TreeSet<>(shippedFiles(".opencode").keySet());

        Set<String> onlyInOpencode = new TreeSet<>(opencode);
        onlyInOpencode.removeAll(claude);
        Set<String> onlyInClaude = new TreeSet<>(claude);
        onlyInClaude.removeAll(opencode);

        onlyInClaude.removeIf(BundleParityTest::isClaudeOnly);

        assertThat(onlyInOpencode).as("files present only in .opencode must be exactly the loader files").isEqualTo(OPENCODE_ONLY);
        assertThat(onlyInClaude).as("the .claude bundle must not carry assets .opencode lacks — copy them across, or add the path to CLAUDE_ONLY_PREFIXES if it is a Claude Code mechanism opencode expresses differently").isEmpty();
    }

    @Test
    @DisplayName("shared files are byte-identical, except the few that only differ by the .claude/.opencode path spelling")
    void sharedFiles_areIdenticalOrOnlyPathAdapted() {
        TreeMap<String, Path> claude = shippedFiles(".claude");
        TreeMap<String, Path> opencode = shippedFiles(".opencode");

        List<String> drifted = new ArrayList<>();
        List<String> unexpectedlyIdentical = new ArrayList<>();

        for (var entry : claude.entrySet()) {
            Path other = opencode.get(entry.getKey());
            if (other == null) {
                continue;
            }
            String left = read(entry.getValue());
            String right = read(other);
            boolean identical = left.equals(right);
            if (PATH_ADAPTED.contains(entry.getKey())) {
                if (identical) {
                    unexpectedlyIdentical.add(entry.getKey());
                } else if (!left.equals(right.replace(".opencode", ".claude"))) {
                    drifted.add(entry.getKey() + " (differs by more than the path spelling)");
                }
            } else if (!identical) {
                drifted.add(entry.getKey());
            }
        }

        assertThat(drifted).as("the two bundle copies have drifted — fix both, or add the file to PATH_ADAPTED if the difference is only the directory name").isEmpty();
        assertThat(unexpectedlyIdentical).as("these are listed as path-adapted but are now identical: drop them from PATH_ADAPTED so a real difference cannot hide behind the exemption").isEmpty();
    }

    /**
     * Narrow, and deliberately so. Today the check above already implies this one: its normalisation
     * is a blunt {@code .opencode} → {@code .claude} substitution, so a version bumped in one copy
     * survives it and is reported as drift. What this test adds is independence from that
     * implication — the waiver exists to let PROSE spell its own bundle directory, and if it is ever
     * widened (a regex, a per-file exemption), the frontmatter must not ride along.
     *
     * <p>Consequence worth knowing before TASK-014 adds {@code outputSchema}: a frontmatter value that
     * legitimately differs between the copies would fail here. That is the intended answer — a
     * per-copy path in metadata is a fact the registry should not have to path-adapt — but it is a
     * constraint on how {@code outputSchema} may be spelled, not an accident.
     */
    @Test
    @DisplayName("the frontmatter of a path-adapted file is identical in both copies — the exemption covers prose, not metadata")
    void pathAdaptedFiles_haveIdenticalFrontmatter() {
        TreeMap<String, Path> claude = shippedFiles(".claude");
        TreeMap<String, Path> opencode = shippedFiles(".opencode");

        List<String> drifted = new ArrayList<>();
        for (String relative : PATH_ADAPTED) {
            Path here = claude.get(relative);
            Path there = opencode.get(relative);
            if (here == null || there == null) {
                drifted.add(relative + " is missing from one of the copies");
                continue;
            }
            String left = frontmatterBlock(read(here));
            String right = frontmatterBlock(read(there));
            if (!left.equals(right)) {
                drifted.add(relative + ": .claude has [" + left + "], .opencode has [" + right + "]");
            }
        }

        assertThat(drifted).as("byte-equality is waived for these files so their prose may spell its own bundle directory; that waiver must not extend to the frontmatter, which contains no paths and is where a version bumped in one copy only would hide").isEmpty();
    }

    /** The leading frontmatter block, delimiters excluded, or an empty string when the file has none. */
    private static String frontmatterBlock(String document) {
        List<String> lines = document.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.get(0).strip())) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        for (int i = 1; i < lines.size(); i++) {
            if ("---".equals(lines.get(i).strip())) {
                return block.toString();
            }
            block.append(lines.get(i).strip()).append('\n');
        }
        return "";
    }
}
