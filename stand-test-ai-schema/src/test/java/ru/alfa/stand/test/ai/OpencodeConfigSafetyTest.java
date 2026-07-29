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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the one bundle file that no other test looks at. {@code opencode.json} is shipped — the
 * README tells consumers to copy {@code .opencode/} into their repository — yet it is listed in
 * {@link BundleParityTest}'s {@code OPENCODE_ONLY}, so parity never compares it, and it holds no
 * prompts, so the inventory snapshot never lists it.
 *
 * <p>That blind spot mattered: the file used to declare three {@code postgres_*} MCP servers running
 * the database toolbox with {@code --env-file /Users/alfa/…} absolute paths. Copying the bundle
 * therefore handed the consumer's agent SQL access to databases it had never heard of, in direct
 * contradiction of the bundle's own guardrail "No production environments in any test registry".
 * ADR-0010 answers this by absence: a capability that must not be used is not wired up at all.
 *
 * <p>Four independent sieves, because the obvious one (a name containing "prod") is the weakest —
 * {@code postgres_designer} pointed at a real database and would have slipped straight through it.
 */
class OpencodeConfigSafetyTest {

    private static final String CONFIG = "docs/ai-agent/.opencode/opencode.json";

    /**
     * Paths tied to one developer's machine. The leading separator is optional on purpose: the
     * jetbrains server spelled its first command element {@code Users/alfa/Applications/…} without
     * one, and a pattern anchored on {@code /Users/} would have missed exactly that line.
     */
    private static final List<Pattern> MACHINE_SPECIFIC_PATH = List.of(
            Pattern.compile("(?i)(^|/)Users/[^/\"]+/"),
            Pattern.compile("(?i)(^|/)home/[a-z][^/\"]*/"),
            Pattern.compile("(?i)^[a-z]:\\\\"),
            Pattern.compile("(?i)/Applications/"));

    /** Substrings that mark a server as pointed at production. Blunt by design, and not the only sieve. */
    private static final List<String> PRODUCTION_MARKER = List.of("prod");

    /**
     * Command fragments that mean "this server can run SQL against a real database". The shipped
     * bundle must wire up no such server: the agent authors tests that talk to stands through the
     * SDK, and a direct SQL channel is precisely the capability ADR-0010 removes rather than guards.
     */
    private static final List<String> DATABASE_ACCESS_MARKER = List.of("database-toolbox", "--prebuilt");

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

    private static JsonNode config() {
        Path file = repositoryRoot().resolve(CONFIG);
        try {
            return new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read or parse " + file, e);
        }
    }

    /** Every string leaf of the document, keyed by a readable path, so a finding names where it lives. */
    private static List<Map.Entry<String, String>> stringLeaves(JsonNode node, String path) {
        List<Map.Entry<String, String>> leaves = new ArrayList<>();
        if (node.isValueNode()) {
            if (node.isTextual()) {
                leaves.add(Map.entry(path, node.asText()));
            }
            return leaves;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                leaves.addAll(stringLeaves(node.get(i), path + "[" + i + "]"));
            }
            return leaves;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            leaves.addAll(stringLeaves(field.getValue(), path.isEmpty() ? field.getKey() : path + "." + field.getKey()));
        }
        return leaves;
    }

    @Test
    @DisplayName("the shipped opencode config is valid JSON and still declares the sections the bundle relies on")
    void config_isStructurallySound() {
        JsonNode config = config();

        assertThat(config.isObject()).as("opencode.json must be a JSON object").isTrue();
        assertThat(config.has("instructions")).as("the config must still point opencode at the bundle's rules — losing this silently unloads the guardrails").isTrue();
        assertThat(config.has("permission")).as("the permission block is part of the shipped safety posture").isTrue();
        assertThat(config.has("mcp")).as("the mcp section must exist, even if it only wires machine-agnostic servers — its absence would make the other checks vacuous").isTrue();
    }

    @Test
    @DisplayName("no value in the shipped config points at one developer's machine")
    void config_carriesNoMachineSpecificPaths() {
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, String> leaf : stringLeaves(config(), "")) {
            for (Pattern pattern : MACHINE_SPECIFIC_PATH) {
                if (pattern.matcher(leaf.getValue()).find()) {
                    findings.add(leaf.getKey() + " → " + leaf.getValue());
                    break;
                }
            }
        }

        assertThat(findings).as("these values are absolute paths on the author's machine; the bundle is copied into other repositories, where they resolve to nothing or — worse — to something else").isEmpty();
    }

    @Test
    @DisplayName("no MCP server is named after a production source")
    void mcpServers_areNotNamedAfterProduction() {
        List<String> findings = new ArrayList<>();
        config().path("mcp").fieldNames().forEachRemaining(name -> {
            for (String marker : PRODUCTION_MARKER) {
                if (name.toLowerCase(java.util.Locale.ROOT).contains(marker)) {
                    findings.add(name);
                }
            }
        });

        assertThat(findings).as("the bundle's own guardrail forbids production environments in any test registry; a shipped MCP server named after one contradicts it before the agent writes a line").isEmpty();
    }

    @Test
    @DisplayName("no MCP server hands the agent direct SQL access to a database")
    void mcpServers_grantNoRawDatabaseAccess() {
        JsonNode mcp = config().path("mcp");
        List<String> findings = new ArrayList<>();
        mcp.fieldNames().forEachRemaining(name -> {
            String command = mcp.path(name).path("command").toString();
            for (String marker : DATABASE_ACCESS_MARKER) {
                if (command.contains(marker)) {
                    findings.add(name + " (matched '" + marker + "')");
                    break;
                }
            }
        });

        assertThat(findings).as("a name check alone is too weak — postgres_designer pointed at a real database without the word 'prod' in it. The shipped bundle wires up no SQL channel at all (ADR-0010); a developer who needs one adds it in a local override outside this repository").isEmpty();
    }

    @Test
    @DisplayName("no apiKey value is shipped filled in")
    void config_shipsNoApiKey() {
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, String> leaf : stringLeaves(config(), "")) {
            if (leaf.getKey().endsWith("apiKey") && !leaf.getValue().isBlank()) {
                findings.add(leaf.getKey());
            }
        }

        assertThat(findings).as("an apiKey committed here is a leaked credential the moment the bundle is copied; the field stays present and empty so its place is documented").isEmpty();
    }
}
