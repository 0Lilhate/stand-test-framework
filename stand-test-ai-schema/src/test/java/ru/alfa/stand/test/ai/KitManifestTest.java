package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manifest that travels with the kit, pinned to the tree it describes.
 *
 * <p>Every mechanism holding this bundle together — the schema tests, the parity tests, the inventory
 * snapshot, this file — lives in the SDK repository and stops existing the moment the kit is copied
 * into a consumer. There the kit is a directory of markdown that nothing checks, and a guardrail
 * softened by hand looks exactly like a guardrail.
 *
 * <p>{@code MANIFEST.json} is the part that travels: every shipped path and the hash of its content.
 * {@code install.mjs} copies strictly what it lists — the alternative is a directory copy, which is
 * how a file holding a developer's allow-list and a curl command with DEV credentials once sat in the
 * bundle — and {@code stand-guard.mjs doctor} compares an installation against it.
 *
 * <p>Which makes a stale manifest worse than none: it would answer "unmodified" about content it has
 * never seen, and an install would silently ship an incomplete kit. That already happened once during
 * this change — {@code doctor.mjs} was added, the manifest was not regenerated, and the installed kit
 * crashed on its own doctor. So the check here is regeneration, not inspection: the committed file
 * must equal what the tree produces, and the failure message says which command fixes it.
 */
class KitManifestTest {

    private static final String BUNDLE_ROOT = "docs/ai-agent";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Machine-local residue: gitignored, so it never reaches a remote — and a directory copy takes it anyway. */
    private static final Set<String> NOT_SHIPPED = Set.of("settings.local.json", "scheduled_tasks.lock", ".DS_Store");

    private static final List<String> NOT_SHIPPED_PREFIXES = List.of(".env", ".fetched-");

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

    private static Path bundleRoot() {
        return repositoryRoot().resolve(BUNDLE_ROOT);
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(file));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("could not hash " + file, e);
        }
    }

    /** What the tree says it ships, computed the same way {@code install.mjs} computes it. */
    private static TreeMap<String, String> expectedFiles() {
        Path root = bundleRoot();
        TreeMap<String, String> files = new TreeMap<>();
        for (String bundle : List.of(".claude", ".opencode")) {
            try (Stream<Path> walk = Files.walk(root.resolve(bundle))) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> !path.toString().replace('\\', '/').contains("/.stand-test/"))
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return !NOT_SHIPPED.contains(name) && NOT_SHIPPED_PREFIXES.stream().noneMatch(name::startsWith);
                        })
                        .forEach(path -> files.put(root.relativize(path).toString().replace('\\', '/'), sha256(path)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + root.resolve(bundle), e);
            }
        }
        return files;
    }

    private static JsonNode manifest() {
        Path file = bundleRoot().resolve("MANIFEST.json");
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file + " — regenerate it: node docs/ai-agent/install.mjs --manifest", e);
        }
    }

    @Test
    @DisplayName("the committed manifest is what this tree produces — path for path, hash for hash")
    void manifest_describesTheTree() {
        TreeMap<String, String> expected = expectedFiles();
        JsonNode files = manifest().path("files");

        TreeSet<String> listed = new TreeSet<>();
        files.fieldNames().forEachRemaining(listed::add);
        TreeSet<String> shouldBeListed = new TreeSet<>(expected.keySet());

        TreeSet<String> missing = new TreeSet<>(shouldBeListed);
        missing.removeAll(listed);
        TreeSet<String> extra = new TreeSet<>(listed);
        extra.removeAll(shouldBeListed);

        assertThat(missing)
                .as("shipped but not in the manifest — install copies strictly what the manifest lists, so these would silently never arrive. Regenerate: node docs/ai-agent/install.mjs --manifest")
                .isEmpty();
        assertThat(extra)
                .as("in the manifest but not in the tree — a consumer's doctor would report them as lost. Regenerate: node docs/ai-agent/install.mjs --manifest")
                .isEmpty();

        List<String> drifted = new ArrayList<>();
        expected.forEach((path, hash) -> {
            if (!hash.equals(files.path(path).asText())) {
                drifted.add(path);
            }
        });
        assertThat(drifted)
                .as("content changed since the manifest was written; a stale hash answers 'unmodified' about content it has never seen. Regenerate: node docs/ai-agent/install.mjs --manifest")
                .isEmpty();
    }

    @Test
    @DisplayName("the manifest carries a hand-set kit version and no machine-local residue")
    void manifest_isShaped() {
        JsonNode manifest = manifest();

        assertThat(manifest.path("kit").asText()).isEqualTo("stand-test-ai-agent-kit");
        assertThat(manifest.path("version").asInt())
                .as("the version says what SET of prompts this is; it is bumped deliberately, not by a typo fix")
                .isPositive();

        List<String> residue = new ArrayList<>();
        manifest.path("files").fieldNames().forEachRemaining(path -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (NOT_SHIPPED.contains(name) || NOT_SHIPPED_PREFIXES.stream().anyMatch(name::startsWith) || path.contains("/.stand-test/")) {
                residue.add(path);
            }
        });
        assertThat(residue)
                .as("a manifest listing machine-local files would make the installer copy them — the exact failure the manifest exists to end")
                .isEmpty();
    }

    @Test
    @DisplayName("everything the inventory snapshot pins is in the manifest too")
    void manifest_coversTheInventory() {
        Path snapshot = repositoryRoot().resolve("stand-test-ai-schema/src/test/resources/prompt-bundle-inventory.txt");
        JsonNode files = manifest().path("files");
        List<String> uncovered = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(snapshot, StandardCharsets.UTF_8)) {
                String path = line.strip();
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                if (!files.has(path)) {
                    uncovered.add(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + snapshot, e);
        }

        assertThat(uncovered)
                .as("the two lists describe the same bundle from different angles; an asset pinned by one and absent from the other travels only by accident")
                .isEmpty();
    }
}
