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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Claude half of the bundle's permission policy, and the guarantee that the two halves agree.
 *
 * <p>The kit ships for two hosts and, until this file existed, protected only one of them. An
 * opencode consumer got {@code opencode.json}: a bash allow-list, a deny-list over the quality gates
 * and the secret files, and MCP servers vetted by {@code OpencodeConfigSafetyTest}. A Claude Code
 * consumer got nothing — no {@code settings.json} at all, so every tool call was governed by whatever
 * that consumer happened to have configured for themselves.
 *
 * <p><strong>Why the parity check is here and not in {@code BundleParityTest}.</strong> That test
 * compares files, and these two files cannot be compared: {@code opencode.json} is in its
 * {@code OPENCODE_ONLY} set and {@code settings.json} in the Claude-only prefixes, so file parity is
 * silent about both by construction. What must not diverge is the POLICY, and policy is what this
 * test compares — every path {@code opencode.json} refuses to edit must be refused on the Claude side
 * too. Divergence would otherwise be invisible in exactly the way that matters: both bundles present,
 * both tests green, one host allowed to edit {@code checkstyle.xml}.
 *
 * <p>The equivalence is semantic rather than textual. The two hosts spell a rule differently —
 * {@code "**}{@code /checkstyle.xml"} against {@code "Edit(./**}{@code /checkstyle.xml)"} — and one of
 * them, {@code .env}, is deliberately WIDER on the Claude side: {@code opencode.json} enumerates three
 * spellings while this settings file refuses the whole family, because a fourth suffix is the same
 * secret in the same place. So the check is "is this path refused", not "is this string present".
 */
class ClaudeSettingsSafetyTest {

    private static final String SETTINGS = "docs/ai-agent/.claude/settings.json";

    private static final String OPENCODE_CONFIG = "docs/ai-agent/.opencode/opencode.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Values that would name one machine rather than any consumer's.
     *
     * <p>Not hypothetical: the file this settings file replaces held {@code /Users/…/IdeaProjects/…}
     * and a hostname of a DEV stand, and the install instruction copied it into consumer repositories.
     */
    private static final List<Pattern> MACHINE_SPECIFIC = List.of(
            Pattern.compile("(?i)/(?:Users|home)/[A-Za-z0-9._-]+/"),
            Pattern.compile("(?i)[A-Za-z]:\\\\\\\\"),
            Pattern.compile("(?i)\\b[a-z0-9-]+\\.(?:alfaintra|alfabank)\\.net\\b"));

    /** A credential carried inline — the shape {@code curl -u user:pass} leaves in an allow-list. */
    private static final Pattern INLINE_CREDENTIAL =
            Pattern.compile("(?i)(?:-u|--user)\\s+\\S+:\\S+|://[^/\\s\"]+:[^@/\\s\"]+@");

    /**
     * Rules the Claude side must carry whatever opencode says, because opencode has no equivalent.
     *
     * <p>The first four are the bundle's own assets: a run that may edit its rules, its hooks, its
     * subagents or its own permissions can widen its perimeter and then pass every gate. The rest are
     * destructive or outward-facing actions the bundle never needs to take unattended.
     */
    private static final List<String> REQUIRED_DENY_SUBJECTS = List.of(
            "settings.json", "rules", "hooks", "agents", "rm -rf", "git push");

    /**
     * Tool wrappers the host does not apply to a path rule, and therefore may not appear in one.
     *
     * <p>Both were shipped for months and both were inert. {@code MultiEdit} names a tool that no
     * longer exists at all; {@code Write(path)} names one that does, but file permissions are matched
     * on {@code Edit(path)} alone — an {@code Edit} rule already governs every file-editing tool,
     * {@code Write} included. The kit carried nine of each, so every session opened with a screenful
     * of the host saying so before its first useful line.
     *
     * <p>Nothing was unprotected by this: each dead rule sat beside an {@code Edit} rule on the same
     * path, which is why the perimeter tests stayed green and nobody looked. That is the trap worth
     * naming — an ineffective rule reads exactly like protection, so the next path added with only a
     * {@code Write} spelling would have been genuinely unguarded and equally invisible.
     *
     * <p>The host's wording is deliberately NOT asserted anywhere: it belongs to the host and changes
     * with its version. What is pinned is the spelling in our file.
     */
    private static final List<String> INEFFECTIVE_SPELLINGS = List.of("Write(", "MultiEdit(");

    /**
     * Every path the shipped policy guards, as the host actually reads it.
     *
     * <p>Pinned as a set rather than a count so that removing a protection fails loudly and states
     * which one. This is what makes "the dead spellings were removed without narrowing anything"
     * checkable rather than asserted: the set is unchanged by that removal, and any later edit that
     * does narrow it lands here.
     */
    private static final Set<String> PROTECTED_DENY_PATHS = new TreeSet<>(List.of(
            "./**/.editorconfig",
            "./**/.env*",
            "./**/.gitignore",
            "./**/checkstyle.xml",
            "./**/detekt-config.yml",
            "./**/detekt.yml",
            "./**/pmd-ruleset.xml",
            "./**/pmd.xml",
            "./**/spotbugs-exclude.xml",
            "./**/spotbugs-include.xml",
            "./**/tomcat/conf/context.xml",
            "./**/tomcat/conf/server.xml",
            "./**/tomcat/conf/web.xml",
            "./.claude/agents/**",
            "./.claude/commands/**",
            "./.claude/hooks/**",
            "./.claude/reference/**",
            "./.claude/rules/**",
            "./.claude/settings.json",
            "./.claude/skills/**",
            "./.claude/workflows/**",
            "./knowledge-base/schema/**"));

    /** The same, for the paths the policy stops to ask about. */
    private static final Set<String> PROTECTED_ASK_PATHS = new TreeSet<>(List.of(
            "./**/application*.yml",
            "./knowledge-base/**",
            "./stand-test-environments.yml"));

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

    private static JsonNode read(String relativePath) {
        Path file = repositoryRoot().resolve(relativePath);
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    private static List<String> rules(String section) {
        List<String> values = new ArrayList<>();
        read(SETTINGS).path("permissions").path(section).forEach(rule -> values.add(rule.asText()));
        return values;
    }

    /** The path a rule governs, with the tool wrapper and the leading {@code ./} stripped off. */
    private static String subjectOf(String rule) {
        int open = rule.indexOf('(');
        int close = rule.lastIndexOf(')');
        String inner = open > 0 && close > open ? rule.substring(open + 1, close) : rule;
        return inner.startsWith("./") ? inner.substring(2) : inner;
    }

    /**
     * The same subject as the other host spells it.
     *
     * <p>Each bundle protects ITS OWN directory, so {@code .opencode/hooks/**} and
     * {@code .claude/hooks/**} are one policy written twice — as is the host settings file, which is
     * {@code opencode.json} there and {@code settings.json} here. Comparing them literally would report
     * a divergence that does not exist, and the alternative — leaving the second bundle's own assets
     * out of its deny list — is the hole this normalisation exists to let us close.
     */
    private static String asClaudeSpells(String subject) {
        return subject.replace(".opencode/opencode.json", ".claude/settings.json").replace(".opencode/", ".claude/");
    }

    /** Whether a glob refused by opencode is also refused here — by the same path or a wider one. */
    private static boolean covers(Set<String> deniedSubjects, String opencodeGlob) {
        String stripped = opencodeGlob.startsWith("**/") ? opencodeGlob.substring(3) : opencodeGlob;
        String target = asClaudeSpells(stripped);
        for (String subject : deniedSubjects) {
            String candidate = subject.startsWith("**/") ? subject.substring(3) : subject;
            if (candidate.equals(target)) {
                return true;
            }
            // A wider rule covers a narrower one: `.env*` refuses `.env`, `.env.local` and the
            // spelling nobody has invented yet. Anything else must match exactly — a rule that
            // covered by accident would be a rule nobody meant to write.
            if (candidate.endsWith("*") && target.startsWith(candidate.substring(0, candidate.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the shipped settings file is valid JSON and declares all three permission levels")
    void settings_areWellFormed() {
        JsonNode permissions = read(SETTINGS).path("permissions");

        assertThat(permissions.isObject()).as("%s must declare a permissions object", SETTINGS).isTrue();
        assertThat(rules("deny")).as("a settings file with no deny list protects nothing").isNotEmpty();
        assertThat(rules("allow")).as("without an allow list every routine read becomes a prompt, and a bundle that asks about everything trains its user to approve everything").isNotEmpty();
        assertThat(rules("ask")).isNotEmpty();
    }

    @Test
    @DisplayName("every path opencode refuses to edit is refused on the Claude side too")
    void denyList_coversTheOpencodePolicy() {
        JsonNode edit = read(OPENCODE_CONFIG).path("permission").path("edit");
        Set<String> opencodeDenied = new TreeSet<>();
        edit.fieldNames().forEachRemaining(glob -> {
            if ("deny".equals(edit.get(glob).asText())) {
                opencodeDenied.add(glob);
            }
        });

        Set<String> claudeDenied = new LinkedHashSet<>();
        rules("deny").stream().filter(rule -> rule.startsWith("Edit(")).map(ClaudeSettingsSafetyTest::subjectOf).forEach(claudeDenied::add);

        List<String> uncovered = opencodeDenied.stream().filter(glob -> !covers(claudeDenied, glob)).toList();

        assertThat(opencodeDenied).as("opencode.json declares no edit denials; the comparison would pass by finding nothing").isNotEmpty();
        assertThat(uncovered)
                .as("the two hosts would diverge on POLICY while both bundles stay file-identical — BundleParityTest cannot see this, because opencode.json and settings.json are each excluded from it")
                .isEmpty();
    }

    @Test
    @DisplayName("the bundle's own assets are not editable by the run they govern")
    void theBundleCannotWidenItsOwnPerimeter() {
        String denials = String.join(" ", rules("deny"));

        assertThat(REQUIRED_DENY_SUBJECTS)
                .as("a run that may edit its own rules, hooks, subagents or permissions can widen its perimeter and then pass every gate that remains")
                .allSatisfy(subject -> assertThat(denials).contains(subject));
    }

    @Test
    @DisplayName("no value names one developer's machine")
    void settings_nameNoMachine() {
        String document = String.join("\n", rules("deny")) + "\n" + String.join("\n", rules("ask")) + "\n" + String.join("\n", rules("allow"));

        List<String> found = MACHINE_SPECIFIC.stream().map(Pattern::pattern)
                .filter(pattern -> Pattern.compile(pattern).matcher(document).find())
                .toList();

        assertThat(found).as("the bundle is copied into other repositories; a rule naming this machine is at best inert there and at worst a leak").isEmpty();
    }

    @Test
    @DisplayName("no permission rule is written in a spelling the host does not apply")
    void permissionRules_useOnlySpellingsTheHostApplies() {
        List<String> ineffective = new ArrayList<>();
        for (String section : List.of("deny", "ask", "allow")) {
            rules(section).stream()
                    .filter(rule -> INEFFECTIVE_SPELLINGS.stream().anyMatch(rule::startsWith))
                    .forEach(rule -> ineffective.add(section + ": " + rule));
        }

        assertThat(ineffective)
                .as("a rule the host does not apply is worse than no rule: it reads as protection, so the next path added with only this spelling is unguarded and nobody notices — use Edit(path), which governs every file-editing tool")
                .isEmpty();
    }

    @Test
    @DisplayName("the set of guarded paths is exactly the pinned one — no removal passes as a spelling cleanup")
    void guardedPaths_areExactlyThePinnedSet() {
        Set<String> denied = new TreeSet<>();
        rules("deny").stream().filter(rule -> rule.startsWith("Edit(")).forEach(rule -> denied.add(inner(rule)));
        Set<String> asked = new TreeSet<>();
        rules("ask").stream().filter(rule -> rule.startsWith("Edit(")).forEach(rule -> asked.add(inner(rule)));

        assertThat(denied).as("the deny perimeter must be parsed, not empty — a vacuous set would agree with anything").isNotEmpty();
        assertThat(denied).as("a path left the deny perimeter; removing an ineffective SPELLING must never remove a protection").isEqualTo(PROTECTED_DENY_PATHS);
        assertThat(asked).as("a path left the ask perimeter").isEqualTo(PROTECTED_ASK_PATHS);
    }

    /** The argument of a rule, with the tool wrapper stripped but the path left as written. */
    private static String inner(String rule) {
        return rule.substring(rule.indexOf('(') + 1, rule.lastIndexOf(')'));
    }

    @Test
    @DisplayName("no rule carries a credential")
    void settings_carryNoCredential() {
        String document = read(SETTINGS).toString();

        assertThat(INLINE_CREDENTIAL.matcher(document).find())
                .as("an allow-list entry spelled `curl -u user:pass` ships a live credential to every consumer that installs the kit — this is the exact shape that was found in the machine-local file this one replaces")
                .isFalse();
    }
}
