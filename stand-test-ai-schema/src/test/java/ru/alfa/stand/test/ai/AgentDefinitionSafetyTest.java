package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shipped subagents, checked for the one property that makes them worth having.
 *
 * <p>Stage 8 of the pipeline calls itself an adversarial review. Until the subagents existed it was
 * performed by the context that had just written the code, so "adversarial" was declared rather than
 * arranged — a context reviews the intent it remembers, not the lines on disk. A separate context is
 * the arrangement, and a reviewer that can EDIT is only half of it: one that quietly repairs what it
 * finds produces a report saying the artifact was always fine.
 *
 * <p>So the tool list is pinned here. Frontmatter is the whole enforcement — Claude Code grants a
 * subagent exactly what {@code tools:} names — which makes a single word added to that line the
 * difference between a reviewer and an author, with nothing else in the kit to notice.
 */
class AgentDefinitionSafetyTest {

    private static final String AGENTS = "docs/ai-agent/.claude/agents";

    /** Every tool that can change a file. A reviewer holding any of them stops being a reviewer. */
    private static final List<String> WRITING_TOOLS = List.of("Write", "Edit", "MultiEdit", "NotebookEdit");

    /**
     * The agents the pipeline names, and the skill each one is the separate context FOR.
     *
     * <p>Pinned as a map rather than discovered from the directory: an agent renamed on one side of
     * the wiring — the rules file, the umbrella command — leaves the pipeline pointing at a name that
     * resolves to nothing, and a host that cannot find a subagent runs the stage inline without
     * saying so.
     */
    private static final Map<String, String> AGENT_SKILLS = Map.of(
            "stand-test-safety-reviewer", "stand-test-safety-review",
            "stand-test-quality-reviewer", "stand-test-test-review",
            "stand-test-kb-resolver", "stand-test-kb-lookup");

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
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static Path agentFile(String name) {
        return repositoryRoot().resolve(AGENTS).resolve(name + ".md");
    }

    /** The declared tools of an agent, as the host reads them: a comma-separated list. */
    private static List<String> tools(String name) {
        String declared = PromptFrontmatterRequiredKeysTest.frontmatter(read(agentFile(name))).getOrDefault("tools", "");
        return Arrays.stream(declared.split(",")).map(String::strip).filter(tool -> !tool.isEmpty()).toList();
    }

    @Test
    @DisplayName("the directory holds exactly the agents the pipeline names, each file named after the agent")
    void agents_areExactlyTheOnesWired() {
        Path directory = repositoryRoot().resolve(AGENTS);
        TreeSet<String> onDisk = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.filter(Files::isRegularFile).forEach(path -> onDisk.add(path.getFileName().toString().replaceFirst("\\.md$", "")));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk " + directory, e);
        }

        assertThat(onDisk).isEqualTo(new TreeSet<>(AGENT_SKILLS.keySet()));
        AGENT_SKILLS.keySet().forEach(name -> assertThat(PromptFrontmatterRequiredKeysTest.frontmatter(read(agentFile(name))).get("name"))
                .as("%s: the host addresses an agent by its 'name', so a file whose name says otherwise is a subagent nobody can invoke", name)
                .isEqualTo(name));
    }

    @Test
    @DisplayName("no reviewer can write — the separate context is only half of it if the other half can edit the code")
    void reviewers_cannotWrite() {
        List<String> armed = new ArrayList<>();
        AGENT_SKILLS.keySet().forEach(name -> tools(name).stream()
                .filter(tool -> WRITING_TOOLS.stream().anyMatch(tool::startsWith))
                .forEach(tool -> armed.add(name + " declares " + tool)));

        assertThat(armed)
                .as("an agent that can repair what it found reports on an artifact that no longer exists; findings go back to the caller, who fixes them and comes back")
                .isEmpty();
    }

    @Test
    @DisplayName("the resolver has no Bash either — it reads the knowledge base and returns, it does not act on it")
    void resolver_onlyReads() {
        assertThat(tools("stand-test-kb-resolver"))
                .as("stages 2 and 4 answer with facts; a resolver that can run commands is one that can start creating the entries it failed to find")
                .containsExactlyInAnyOrder("Read", "Grep", "Glob");
    }

    @Test
    @DisplayName("every agent points at the skill it is the separate context for")
    void agents_nameTheirSkill() {
        List<String> orphaned = new ArrayList<>();
        AGENT_SKILLS.forEach((agent, skill) -> {
            if (!read(agentFile(agent)).contains(skill)) {
                orphaned.add(agent + " never mentions " + skill);
            }
            if (!Files.isDirectory(repositoryRoot().resolve("docs/ai-agent/.claude/skills").resolve(skill))) {
                orphaned.add(agent + " points at " + skill + ", which is not a shipped skill");
            }
        });

        assertThat(orphaned)
                .as("the checklist lives in the skill; an agent that does not load it reviews from memory, which is the failure mode the skill was written to prevent")
                .isEmpty();
    }

    @Test
    @DisplayName("the pipeline rules and the umbrella command name the agents that exist")
    void wiring_namesRealAgents() {
        List<String> wiring = List.of(
                "docs/ai-agent/.claude/rules/stand-test-pipeline.md",
                "docs/ai-agent/.claude/commands/stand-test-generate-java-test.md");
        List<String> missing = new ArrayList<>();
        for (String document : wiring) {
            String text = read(repositoryRoot().resolve(document));
            AGENT_SKILLS.keySet().stream().filter(agent -> !text.contains(agent)).forEach(agent -> missing.add(document + " never names " + agent));
        }

        assertThat(missing)
                .as("a stage whose subagent is named nowhere the model reads is a stage that quietly runs in the main context")
                .isEmpty();
    }
}
