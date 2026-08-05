package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pins the properties of the CI pipeline that the pipeline itself cannot report on (UITG-S025,
 * requirements BR-18 and NFR-06).
 *
 * <p>A pipeline is the one artefact that gives no feedback when it is wrong in the direction that
 * matters: a job which swallows Gradle's exit code, reuses task outputs across commits or is marked
 * {@code allow_failure} stays green forever and reports nothing. The failure mode is silence, so the
 * properties are asserted here rather than observed there.
 *
 * <p>Why this test lives in {@code stand-test-ai-schema}: this module already hosts the
 * repository-level meta-tests that read files outside their own module — {@code CLAUDE.md},
 * {@code README.md}, the kit bundle, the evaluation corpus — and it is the only module carrying a
 * YAML parser on its test classpath. Its {@code test} task declares {@code .gitlab-ci.yml} as an
 * input, so editing the pipeline re-runs this test instead of leaving it {@code UP-TO-DATE}.
 */
class CiPipelineConfigTest {

    /** Top-level keys of a GitLab configuration that are not jobs. */
    private static final Set<String> NON_JOB_KEYS =
            Set.of("workflow", "stages", "variables", "default", "include", "image", "before_script", "after_script", "cache", "services");

    /**
     * The browser suite is deliberately absent: it needs a runner image carrying Chromium, which is
     * an infrastructure gate (G-3) and a task of its own — UITG-S026. The point of asserting it is
     * that adding {@code browserTest} to the protocol pipeline looks like an improvement and would
     * turn every run red on a runner without a browser.
     */
    private static final String OUT_OF_SCOPE_TASK = "browserTest";

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts")) && Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static Path pipelineFile() {
        return repositoryRoot().resolve(".gitlab-ci.yml");
    }

    private static String pipelineText() {
        try {
            return Files.readString(pipelineFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pipelineDocument() {
        LoaderOptions options = new LoaderOptions();
        Object loaded = new Yaml(new SafeConstructor(options)).load(pipelineText());
        assertThat(loaded).as(".gitlab-ci.yml must be a YAML mapping").isInstanceOf(Map.class);
        return (Map<String, Object>) loaded;
    }

    /** Visible jobs: hidden templates (leading dot) and the reserved top-level keys are not jobs. */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> visibleJobs(Map<String, Object> document) {
        Map<String, Map<String, Object>> jobs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(".") || NON_JOB_KEYS.contains(key) || !(entry.getValue() instanceof Map)) {
                continue;
            }
            jobs.put(key, (Map<String, Object>) entry.getValue());
        }
        return jobs;
    }

    /**
     * Resolves the value a job sees for a key, following one level of {@code extends} — the only
     * inheritance this pipeline uses. Resolving it matters: every property asserted below is
     * declared on the shared template, and a test reading jobs literally would find nothing and
     * pass on an empty set.
     */
    @SuppressWarnings("unchecked")
    private static Object resolved(Map<String, Object> document, Map<String, Object> job, String key) {
        if (job.containsKey(key)) {
            return job.get(key);
        }
        Object parent = job.get("extends");
        String parentName = parent instanceof List ? String.valueOf(((List<Object>) parent).get(0)) : (parent == null ? null : String.valueOf(parent));
        if (parentName == null) {
            return null;
        }
        Object template = document.get(parentName);
        return template instanceof Map ? ((Map<String, Object>) template).get(key) : null;
    }

    @SuppressWarnings("unchecked")
    private static String gradleArguments(Map<String, Object> document, Map<String, Object> job) {
        Object variables = resolved(document, job, "variables");
        if (!(variables instanceof Map)) {
            return "";
        }
        Object args = ((Map<String, Object>) variables).get("STAND_TEST_GRADLE_ARGS");
        return args == null ? "" : String.valueOf(args);
    }

    /** True when the job's rules admit a scheduled pipeline instead of excluding it. */
    private static boolean scheduleOnly(Map<String, Object> job) {
        Object rules = job.get("rules");
        if (!(rules instanceof List)) {
            return false;
        }
        for (Object rule : (List<?>) rules) {
            if (!(rule instanceof Map)) {
                continue;
            }
            Map<?, ?> clause = (Map<?, ?>) rule;
            String condition = String.valueOf(clause.get("if"));
            if (!condition.contains("schedule")) {
                continue;
            }
            // The FIRST matching clause decides in GitLab, so a `when: never` on it excludes the run.
            return !"never".equals(String.valueOf(clause.get("when")));
        }
        return false;
    }

    /** Configuration lines with the comments removed: a rule about shell must not read prose about shell. */
    private static List<String> executableLines() {
        List<String> lines = new ArrayList<>();
        for (String line : pipelineText().split("\\R")) {
            if (line.strip().startsWith("#") || line.isBlank()) {
                continue;
            }
            lines.add(line);
        }
        return lines;
    }

    @Test
    @DisplayName("the repository carries a CI pipeline at all")
    void pipelineFileExists() {
        assertThat(pipelineFile())
                .as("UITG-S025: without a pipeline the regression runs only when somebody remembers to run it")
                .isRegularFile();
        assertThat(visibleJobs(pipelineDocument()))
                .as("a pipeline with no visible job is a file, not a check")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every verification job runs the full ./gradlew build")
    void everyJobRunsTheFullBuild() {
        Map<String, Object> document = pipelineDocument();
        Map<String, Map<String, Object>> jobs = visibleJobs(document);

        for (Map.Entry<String, Map<String, Object>> job : jobs.entrySet()) {
            assertThat(gradleArguments(document, job.getValue()))
                    .as("job '%s' must run the same aggregate task a developer runs, so the two cannot drift apart", job.getKey())
                    .startsWith("build");
        }
    }

    @Test
    @DisplayName("no job is allowed to fail without failing the pipeline")
    void noJobIsAllowedToFail() {
        Map<String, Object> document = pipelineDocument();

        for (Map.Entry<String, Map<String, Object>> job : visibleJobs(document).entrySet()) {
            assertThat(resolved(document, job.getValue(), "allow_failure"))
                    .as("job '%s': a verification job that may fail without failing the pipeline is a green light with nothing behind it", job.getKey())
                    .isNotEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("Gradle's exit code reaches the job unmodified")
    void gradleExitCodeIsNotSwallowed() {
        List<String> invocations = new ArrayList<>();
        for (String line : executableLines()) {
            if (line.contains("./gradlew")) {
                invocations.add(line.strip());
            }
        }

        assertThat(invocations).as("the pipeline must invoke the Gradle wrapper").isNotEmpty();
        for (String invocation : invocations) {
            assertThat(invocation)
                    .as("'%s': a pipe hands the job the exit code of the LAST command, so a red build reports green", invocation)
                    .doesNotContain("|");
            assertThat(invocation)
                    .as("'%s': '|| true' / '; true' hides a red build outright", invocation)
                    .doesNotContain("|| true")
                    .doesNotContain("; true")
                    .doesNotContain("|| :");
        }
        assertThat(pipelineText())
                .as("the captured Gradle status must be re-raised as the job's own exit code")
                .contains("exit $gradle_status");
    }

    @Test
    @DisplayName("caching carries downloads between runs, never task outputs")
    void cacheCarriesDependenciesOnly() {
        Map<String, Object> document = pipelineDocument();
        List<String> cachedPaths = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> job : visibleJobs(document).entrySet()) {
            Object cache = resolved(document, job.getValue(), "cache");
            if (!(cache instanceof Map)) {
                continue;
            }
            Object paths = ((Map<?, ?>) cache).get("paths");
            assertThat(paths).as("job '%s': a cache without paths caches nothing", job.getKey()).isInstanceOf(List.class);
            for (Object path : (List<?>) paths) {
                cachedPaths.add(String.valueOf(path));
            }
        }

        assertThat(cachedPaths).as("the dependency cache is what makes a closed-perimeter build affordable").isNotEmpty();
        for (String path : cachedPaths) {
            assertThat(path)
                    .as("'%s': only the Gradle home may be carried between runs", path)
                    .startsWith(".gradle-home/");
            assertThat(path)
                    .as("'%s': caching task outputs, the build cache or the configuration cache lets a job report UP-TO-DATE for something this commit changed", path)
                    .doesNotContain("build-cache")
                    .doesNotContain("configuration-cache");
        }
        assertThat(cachedPaths)
                .as("a module's build/ directory holds exactly the task outputs a fresh verdict must not inherit")
                .noneMatch(path -> path.contains("build/") && !path.contains(".gradle-home/caches"));
    }

    @Test
    @DisplayName("a scheduled job re-runs everything from cold")
    void scheduledJobRerunsTasks() {
        Map<String, Object> document = pipelineDocument();
        List<String> scheduledArguments = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> job : visibleJobs(document).entrySet()) {
            if (scheduleOnly(job.getValue())) {
                scheduledArguments.add(gradleArguments(document, job.getValue()));
            }
        }

        assertThat(scheduledArguments)
                .as("without a nightly job, an incremental-build illusion can hide a real failure indefinitely")
                .isNotEmpty();
        for (String arguments : scheduledArguments) {
            assertThat(arguments)
                    .as("'%s': the nightly run exists to answer whether the set is green by itself rather than green by inheritance", arguments)
                    .contains("--rerun-tasks")
                    .contains("--no-build-cache");
        }
    }

    @Test
    @DisplayName("the browser suite stays out of the protocol pipeline")
    void browserSuiteIsNotRunHere() {
        // Executable lines only: the comment explaining WHY the browser suite is absent names the
        // task, and a scan of the whole text would forbid the file from stating its own boundary.
        for (String line : executableLines()) {
            assertThat(line)
                    .as("'%s': %s needs an image with a browser (gate G-3) and arrives with UITG-S026, not before it", line.strip(), OUT_OF_SCOPE_TASK)
                    .doesNotContain(OUT_OF_SCOPE_TASK);
        }
    }

    @Test
    @DisplayName("no secret and no endpoint is written into the pipeline")
    void pipelineCarriesNoSecretsAndNoEndpoints() {
        for (String line : executableLines()) {
            assertThat(line)
                    .as("'%s': an endpoint belongs in a CI/CD variable — a URL in the file is one nobody can rotate", line.strip())
                    .doesNotContain("http://")
                    .doesNotContain("https://");
        }

        Map<String, Object> document = pipelineDocument();
        List<Object> variableBlocks = new ArrayList<>();
        variableBlocks.add(document.get("variables"));
        for (Object value : document.values()) {
            if (value instanceof Map) {
                variableBlocks.add(((Map<?, ?>) value).get("variables"));
            }
        }

        for (Object block : variableBlocks) {
            if (!(block instanceof Map)) {
                continue;
            }
            for (Map.Entry<?, ?> variable : ((Map<?, ?>) block).entrySet()) {
                String name = String.valueOf(variable.getKey()).toLowerCase(Locale.ROOT);
                assertThat(name)
                        .as("variable '%s' is declared with a value in the repository; a credential must come from a masked CI/CD variable", variable.getKey())
                        .doesNotContain("password")
                        .doesNotContain("secret")
                        .doesNotContain("token")
                        .doesNotContain("credential");
            }
        }
    }
}
