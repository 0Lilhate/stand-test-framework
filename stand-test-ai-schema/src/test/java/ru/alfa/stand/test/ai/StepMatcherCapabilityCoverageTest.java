package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;

/**
 * Pins <em>which matchers each step type may use</em> across the three places that state it, so the
 * partition cannot drift the way it did before: {@code grpc.unary} gained the full matcher set while the
 * authoring bundle, the published generation rules and the schema's own description string all went on
 * calling it equals-only for a whole release.
 *
 * <p>{@link AssertionMatcherCoverageTest} already pins the matcher <em>set</em> to
 * {@link AssertionMatcher}. What was unpinned is the per-step <em>capability</em>:
 *
 * <ol>
 *   <li>the JSON Schema — every step def resolves to {@code assertionList} (full set) or
 *       {@code equalsOnlyAssertionList} (EQUALS only); this test derives the partition mechanically
 *       and pins it, so a schema edit that silently downgrades gRPC or upgrades kafka fails here;</li>
 *   <li>{@code ai/stand-test-ai-generation-rules.md} — the published prose an AI agent reads;</li>
 *   <li>{@code docs/ai-agent/.claude} and {@code .opencode} — the shippable authoring bundle.</li>
 * </ol>
 *
 * <p>For (2) and (3) the check is a denylist of the specific wrong phrasings rather than a
 * co-occurrence scan: correct text legitimately mentions a full-matcher step and "equals-only" in one
 * breath ("kafka/db are the equals-only adapters; REST and gRPC take CONTAINS/MATCHES"), so proximity
 * alone would flag the fix as the defect. Each pattern below encodes a claim that is false against the
 * schema, and the failure message says what to write instead.
 */
class StepMatcherCapabilityCoverageTest {

    /** Step types whose schema def resolves to the full {@code assertionList}. */
    private static final Set<String> EXPECTED_FULL_MATCHER_STEPS = new TreeSet<>(Set.of("rest.get", "rest.post", "rest.expectEventually", "grpc.unary"));

    /** Step types whose schema def resolves to {@code equalsOnlyAssertionList}. */
    private static final Set<String> EXPECTED_EQUALS_ONLY_STEPS = new TreeSet<>(Set.of("kafka.expect"));

    private static final Pattern ASSERTION_LIST_REF = Pattern.compile("#/\\$defs/(\\w*[Aa]ssertionList)");

    /**
     * Phrasings that contradict the schema-derived partition. Key = regex (case-insensitive, applied per
     * line); value = what the maintainer should write instead.
     */
    private static final Map<String, String> FORBIDDEN_CLAIMS = new LinkedHashMap<>();

    static {
        FORBIDDEN_CLAIMS.put("equals-only\\s+outside\\s+REST(?!/gRPC)", "wrong on both counts: gRPC also takes all five matchers, and db.expectEventually is equals-only too. Name the equals-only adapters explicitly (kafka.expect / db.expectEventually).");
        FORBIDDEN_CLAIMS.put("kafka/db/grpc\\b[^\\n]{0,60}equals-only", "gRPC does not belong in that group — it is at parity with REST. Group kafka/db only.");
        FORBIDDEN_CLAIMS.put("equals-only[^\\n]{0,20}\\bon\\s+kafka/db/grpc", "gRPC does not belong in that group — it is at parity with REST. Group kafka/db only.");
        FORBIDDEN_CLAIMS.put("grpc\\.unary`?\\s*[—-]\\s*equals-only", "grpc.unary references #/$defs/assertionList — say 'all 5 matchers (at parity with REST)'.");
        FORBIDDEN_CLAIMS.put("gRPC\\s+response\\s*\\(equals-only", "gRPC responses take all five matchers — drop the equals-only marker.");
        FORBIDDEN_CLAIMS.put("regex\\s+on\\s+gRPC", "MATCHES is executable on gRPC; listing it as inexpressible makes the agent reword a valid assertion or raise a needless blocking question.");
        // `[\s`*_]+` between tokens because the prose is markdown: "`MATCHES` regex on REST only".
        FORBIDDEN_CLAIMS.put("MATCHES[\\s`*_]+regex[\\s`*_]+on[\\s`*_]+REST[\\s`*_]+only", "gRPC accepts MATCHES as well.");
        FORBIDDEN_CLAIMS.put("gRPC\\s+unary\\s*\\(equals-only", "gRPC unary takes all five matchers — drop the equals-only marker.");
        // The two spellings that got past every pattern above and shipped anyway. The list had grown by
        // adding the exact line each incident produced, so each new pattern was one keystroke away from
        // being evaded: "Kafka / gRPC / DB: equals only" carries no hyphen and does not put `grpc.unary`
        // next to the claim, and "gRPC assertions are equals-only in the SDK" separates them by three
        // words. This one asks the general question instead — gRPC, then the claim, in one line — and
        // the corrected wordings pass it because they either put the claim BEFORE gRPC ("the equals-only
        // adapters; REST and gRPC take …") or do not make it at all.
        // The trailing lookahead is what keeps this from firing on the CORRECT sentence that contrasts
        // the two — "full matcher set on REST and grpc.unary but equals-only on kafka.expect". There the
        // claim is scoped to kafka/db by the words right after it, and that scope is the whole
        // difference between naming the equals-only adapters and mislabelling gRPC as one. Without the
        // lookahead this pattern refused the yaml-authoring skill's own description, which is exactly
        // right about the partition.
        FORBIDDEN_CLAIMS.put("gRPC[^\\n]{0,20}equals[- ]only(?![^\\n]{0,25}(?:kafka|db\\.))", "gRPC is at parity with REST — grpc.unary reads the same MATCHER wire key. Name the equals-only adapters explicitly (kafka.expect / db.expectEventually), and scope the claim to them rather than onto gRPC.");
        FORBIDDEN_CLAIMS.put("only\\s+adapter[^\\n]{0,30}equals-only", "kafka is not the only one: db.expectEventually has no assertion type at all and is equals-only by construction. Say 'kafka and db are the equals-only adapters'.");
        // A third spelling, found in the yaml-authoring TEMPLATE: "non-equals matchers ... ONLY on
        // rest.* steps". It names neither `grpc.unary` nor the words "equals-only", so every pattern
        // above walked past it — in the file a generated document is copied from.
        FORBIDDEN_CLAIMS.put("(?:non-equals|contains|matches)[^\\n]{0,60}only\\s+on\\s+`?rest", "grpc.unary takes the non-equals matchers too. Write 'on rest.* AND grpc.unary', and name kafka.expect as the equals-only one.");
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root (the directory holding docs/ai-agent) not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static JsonNode schema() {
        try (InputStream in = StepMatcherCapabilityCoverageTest.class.getResourceAsStream(AiSchemaResources.SCHEMA_RESOURCE)) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + AiSchemaResources.SCHEMA_RESOURCE, e);
        }
    }

    /** Maps every step type declared in the schema to the assertion-list def its step reaches, if any. */
    private static Map<String, String> capabilityByStepType() {
        JsonNode defs = schema().path("$defs");
        Map<String, String> capability = new LinkedHashMap<>();
        defs.fieldNames().forEachRemaining(defName -> {
            if (!defName.endsWith("Step")) {
                return;
            }
            String json = defs.get(defName).toString();
            List<String> lists = new ArrayList<>();
            Matcher listMatcher = ASSERTION_LIST_REF.matcher(json);
            while (listMatcher.find()) {
                if (!lists.contains(listMatcher.group(1))) {
                    lists.add(listMatcher.group(1));
                }
            }
            assertThat(lists).as("step def %s must reference at most one assertion-list def", defName).hasSizeLessThan(2);
            if (lists.isEmpty()) {
                return;
            }
            // A step def declares its type either as a single `const` (grpc.unary) or as an `enum` of the
            // types sharing that shape (restStep covers rest.get + rest.post). Read the declaration
            // rather than scanning the def for every `const`, which would also catch the type consts of
            // the nested allOf branches that carry per-method rules.
            for (String stepType : declaredStepTypes(defs.get(defName))) {
                capability.put(stepType, lists.get(0));
            }
        });
        return capability;
    }

    private static List<String> declaredStepTypes(JsonNode stepDef) {
        JsonNode type = stepDef.path("properties").path("type");
        List<String> types = new ArrayList<>();
        if (type.hasNonNull("const")) {
            types.add(type.get("const").asText());
        } else if (type.path("enum").isArray()) {
            type.get("enum").forEach(node -> types.add(node.asText()));
        }
        assertThat(types).as("step def declaring an assertion list must declare its type via const or enum").isNotEmpty();
        return types;
    }

    /**
     * Every document this guard reads.
     *
     * <p>The corpus used to be the two bundle copies and the published rules, and both files that
     * shipped a stale claim sat just outside it: the KB's own {@code example-grpc-targets.yml} header —
     * a file a consumer copies into its repository — and {@code CLAUDE.md}, the instruction file loaded
     * into every session in THIS repository. A guard whose corpus excludes the most-read document is a
     * guard for the documents that happened to be inside it.
     *
     * <p>The knowledge base is walked as a whole rather than by file, because its entries are written
     * by {@code /stand-test-kb-update} and a new one must inherit the check rather than have to be
     * added to a list here.
     */
    private static List<Path> documentationFiles() {
        Path root = repoRoot();
        List<Path> files = new ArrayList<>();
        files.add(root.resolve(Paths.get("stand-test-ai-schema", "src", "main", "resources", "ai", "stand-test-ai-generation-rules.md")));
        files.add(root.resolve("CLAUDE.md"));
        List<Path> trees = new ArrayList<>();
        for (String bundle : List.of(".claude", ".opencode")) {
            trees.add(root.resolve(Paths.get("docs", "ai-agent", bundle)));
        }
        trees.add(root.resolve(Paths.get("docs", "ai-agent", "knowledge-base")));
        for (Path dir : trees) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".md") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".java");
                        })
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to walk " + dir, e);
            }
        }
        return files;
    }

    @Test
    @DisplayName("the corpus really covers the two places a stale claim shipped from — CLAUDE.md and the knowledge base")
    void documentationCorpus_coversTheFilesThatShippedStale() {
        Path root = repoRoot();
        List<Path> corpus = documentationFiles();

        assertThat(corpus).as("CLAUDE.md is loaded into every session and must answer to this guard")
                .contains(root.resolve("CLAUDE.md"));
        assertThat(corpus).as("the KB example a consumer copies must answer to this guard")
                .contains(root.resolve(Paths.get("docs", "ai-agent", "knowledge-base", "grpc", "example-grpc-targets.yml")));
    }

    @Test
    @DisplayName("the schema partitions step types into full-matcher and equals-only exactly as documented — a silent downgrade of grpc.unary or upgrade of kafka.expect fails here")
    void schemaPartition_isPinned() {
        Map<String, String> capability = capabilityByStepType();

        Set<String> full = new TreeSet<>();
        Set<String> equalsOnly = new TreeSet<>();
        capability.forEach((stepType, list) -> {
            if ("assertionList".equals(list)) {
                full.add(stepType);
            } else if ("equalsOnlyAssertionList".equals(list)) {
                equalsOnly.add(stepType);
            }
        });

        assertThat(full).as("step types reaching the full #/$defs/assertionList").isEqualTo(EXPECTED_FULL_MATCHER_STEPS);
        assertThat(equalsOnly).as("step types reaching #/$defs/equalsOnlyAssertionList").isEqualTo(EXPECTED_EQUALS_ONLY_STEPS);
    }

    @Test
    @DisplayName("the two assertion-list defs resolve to the core AssertionMatcher enum: the full list is every constant, the equals-only list is exactly EQUALS")
    void assertionListDefs_tieToTheRuntimeEnum() {
        JsonNode defs = schema().path("$defs");

        Set<String> fullKeys = matcherKeysOf(defs, "assertion");
        Set<String> equalsOnlyKeys = matcherKeysOf(defs, "equalsOnlyAssertion");

        Set<String> everyMatcher = new TreeSet<>();
        for (AssertionMatcher matcher : AssertionMatcher.values()) {
            everyMatcher.add(matcher.name());
        }

        assertThat(fullKeys).as("#/$defs/assertion must offer every runtime matcher").isEqualTo(everyMatcher);
        assertThat(equalsOnlyKeys).as("#/$defs/equalsOnlyAssertion must offer exactly EQUALS").containsExactly(AssertionMatcher.EQUALS.name());
    }

    private static Set<String> matcherKeysOf(JsonNode defs, String defName) {
        JsonNode properties = defs.path(defName).path("properties");
        assertThat(properties.isObject()).as("$defs.%s.properties must exist", defName).isTrue();
        Set<String> keys = new TreeSet<>();
        properties.fieldNames().forEachRemaining(name -> {
            if (!"path".equals(name)) {
                keys.add(toEnumName(name));
            }
        });
        return keys;
    }

    private static String toEnumName(String surfaceKey) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < surfaceKey.length(); i++) {
            char symbol = surfaceKey.charAt(i);
            if (Character.isUpperCase(symbol)) {
                name.append('_');
            }
            name.append(symbol);
        }
        return name.toString().toUpperCase(Locale.ROOT);
    }

    @Test
    @DisplayName("no published rule or bundle document claims a full-matcher step is equals-only")
    void documentation_carriesNoStaleEqualsOnlyClaim() {
        List<String> violations = new ArrayList<>();
        Path root = repoRoot();

        for (Path file : documentationFiles()) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + file, e);
            }
            for (int i = 0; i < lines.size(); i++) {
                for (Map.Entry<String, String> claim : FORBIDDEN_CLAIMS.entrySet()) {
                    if (Pattern.compile(claim.getKey(), Pattern.CASE_INSENSITIVE).matcher(lines.get(i)).find()) {
                        violations.add(root.relativize(file) + ":" + (i + 1) + " — " + claim.getValue() + System.lineSeparator() + "      " + lines.get(i).trim());
                    }
                }
            }
        }

        assertThat(violations).as("stale equals-only claims (grpc.unary carries the full matcher set; only kafka.expect and db.expectEventually are equals-only)").isEmpty();
    }

    @Test
    @DisplayName("the guard actually fires — the exact wording that shipped stale is rejected")
    void forbiddenClaims_detectTheWordingThatShipped() {
        List<String> shipped = List.of(
                "(seven step types; equals-only outside REST; fixtures-only bodies; no seed/cleanup). If any",
                "| Expected gRPC response | method + response fields | `grpc.unary` — equals-only, deadline mandatory |",
                "### gRPC response (equals-only!)",
                "- \"Message contains substring\" on Kafka / regex on gRPC (equals-only adapters).",
                "- Kafka/DB/gRPC assertions are equals-only — verify nobody smuggled a `matcher` key into",
                "| Assertion correctness | OK | `MATCHES` regex on REST only; kafka/db equals-only respected |",
                "  # --- optional gRPC unary (equals-only; deadline mandatory) ------------------",
                // The three that shipped anyway: the scenario-design matcher list, the KB grpc example
                // and CLAUDE.md. Each is the line that CARRIES the claim — the guard reads line by line,
                // so a claim split across two lines is caught on the half that states it.
                "   - Kafka / gRPC / DB: equals only. Numbers compare by value (`100` == `100.0`), strings never",
                "# gRPC assertions are equals-only in the SDK; a bounded deadline is mandatory per method;",
                "only adapter still equals-only — `KafkaAssertion` carries no matcher field and",
                "#   * non-equals matchers (contains/matches/exists/notNull) ONLY on rest.* steps");

        for (String line : shipped) {
            boolean caught = FORBIDDEN_CLAIMS.keySet().stream().anyMatch(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(line).find());
            assertThat(caught).as("the guard must reject the stale wording: %s", line).isTrue();
        }
    }

    @Test
    @DisplayName("the guard does not fire on the corrected wording, which mentions gRPC and equals-only in one breath")
    void forbiddenClaims_acceptTheCorrectedWording() {
        List<String> corrected = List.of(
                "(seven step types; equals-only on `kafka.expect`/`db.expectEventually` while `rest.*` and",
                "| Expected gRPC response | method + response fields | `grpc.unary` — all 5 matchers (at parity with REST), deadline mandatory |",
                "### gRPC response (all 5 matchers, at parity with REST)",
                "  equals-only adapters. REST and gRPC take CONTAINS/MATCHES/EXISTS/NOT_NULL.",
                "- Kafka/DB assertions are equals-only — verify nobody smuggled a `matcher` key into hand-built",
                "  NOT equals-only: `GrpcStepParameters` reads the `MATCHER` key, so all 5 matchers apply there",
                "inside its executable subset (7 step types, equals-only outside REST/gRPC, fixture-only bodies, no",
                "  # --- optional gRPC unary (all 5 matchers, as REST; deadline mandatory) ------",
                // The replacements for the three lines above. Each mentions gRPC and the equals-only
                // adapters in one breath, which is exactly the shape the new patterns must tolerate:
                // the claim is made ABOUT kafka/db and merely NEAR gRPC.
                "   - REST and gRPC: `EQUALS` (default), `CONTAINS`, `MATCHES` (full-string regex), `EXISTS`",
                "   - `kafka.expect` and `db.expectEventually`: equals only — those two, and only those two, are",
                "# gRPC assertions take all five matchers, at parity with REST (EQUALS/CONTAINS/MATCHES/EXISTS/",
                "# NOT_NULL) — `kafka.expect` and `db.expectEventually` are the equals-only adapters, not this one.",
                "is the `java-platform` carrying constraints. Known asymmetry: **Kafka and DB** are",
                "the equals-only adapters — `KafkaAssertion` carries no matcher field and `KafkaStepParameters` has no",
                // The line the first version of the gRPC pattern refused: a correct contrast, where
                // "equals-only" is scoped to kafka.expect by the words immediately after it.
                "subset (7 step types, full matcher set on REST and grpc.unary but equals-only on kafka.expect, fixture-only bodies");

        for (String line : corrected) {
            List<String> tripped = FORBIDDEN_CLAIMS.keySet().stream().filter(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(line).find()).toList();
            assertThat(tripped).as("the guard must not fire on correct wording: %s", line).isEmpty();
        }
    }
}
