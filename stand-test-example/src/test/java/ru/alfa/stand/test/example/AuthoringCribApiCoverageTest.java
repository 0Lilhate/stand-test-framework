package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.db.DbStep;
import ru.alfa.stand.test.grpc.GrpcStep;
import ru.alfa.stand.test.kafka.KafkaStep;
import ru.alfa.stand.test.rest.RestStep;

/**
 * Pins the authoring bundle's Java-DSL crib to the SDK's real public API.
 *
 * <p>The crib in {@code stand-test-java-dsl-authoring/SKILL.md} is what an agent transcribes into a
 * generated test, so every method it names must exist — a rename or removal in an adapter would
 * otherwise leave the kit teaching an API that does not compile, and nothing would notice until a
 * human read the failure. That is not hypothetical: the same class of drift already shipped once,
 * when the crib went on calling gRPC equals-only after it had gained the full matcher set.
 *
 * <p>This test lives in {@code stand-test-example} because it needs the adapter classes on the
 * classpath, and this is the only module that has all four. That placement is now load-bearing rather
 * than incidental: the other kit tests lived in {@code stand-test-ai-schema} and went with it when that
 * module was removed, so this is the last machine check standing between a kit asset and the SDK it
 * describes.
 */
class AuthoringCribApiCoverageTest {

    /** Types the crib may legitimately name. A bare {@code .method(} must exist on at least one. */
    private static final List<Class<?>> SDK_TYPES = List.of(
            RestStep.class, KafkaStep.class, DbStep.class, GrpcStep.class,
            Scenario.class, Scenario.Builder.class, ScenarioResult.class, StandClient.class);

    /**
     * Tokens that look like calls but are not SDK API: JDK, AssertJ, and one SQL fragment
     * ({@code test_data.orders(id, status)}) that the regex cannot tell from a method call.
     */
    private static final Set<String> NON_SDK_TOKENS = Set.of(
            "now", "of", "randomUUID", "toString", "orders",
            "isInstanceOf", "hasMessageContaining", "assertThatThrownBy", "orElseThrow",
            "isTrue", "isFalse", "isEmpty", "as", "contains");

    private static final Pattern QUALIFIED = Pattern.compile("\\b(RestStep|KafkaStep|DbStep|GrpcStep|Scenario)\\.([a-zA-Z][a-zA-Z/]*)\\(");
    private static final Pattern BARE = Pattern.compile("(?<![a-zA-Z])\\.([a-zA-Z][a-zA-Z]*)\\(");

    private static Path cribFile() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent", ".claude", "skills", "stand-test-java-dsl-authoring", "SKILL.md"));
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("the authoring crib was not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static String cribText() {
        try {
            return Files.readString(cribFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + cribFile(), e);
        }
    }

    private static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> typeNamed(String simpleName) {
        for (Class<?> type : SDK_TYPES) {
            if (type.getSimpleName().equals(simpleName)) {
                return type;
            }
        }
        throw new IllegalStateException("unmapped SDK type in the crib: " + simpleName);
    }

    @Test
    @DisplayName("every qualified call the crib names (RestStep.get, DbStep.write, Scenario.builder, ...) exists on that SDK type")
    void qualifiedCalls_existOnTheirType() {
        List<String> missing = new ArrayList<>();
        Matcher matcher = QUALIFIED.matcher(cribText());
        while (matcher.find()) {
            Class<?> type = typeNamed(matcher.group(1));
            // The crib compresses siblings as `get/post/put/delete(` — each alternative is a real method.
            for (String name : matcher.group(2).split("/")) {
                if (!hasPublicMethod(type, name)) {
                    missing.add(type.getSimpleName() + "." + name + "(...)");
                }
            }
        }
        assertThat(new TreeSet<>(missing)).as("the crib teaches calls that no longer exist — a generated test would not compile").isEmpty();
    }

    @Test
    @DisplayName("every chained call the crib names exists on at least one SDK type it could belong to")
    void chainedCalls_existSomewhereInTheSdk() {
        Set<String> unknown = new TreeSet<>();
        Matcher matcher = BARE.matcher(cribText());
        while (matcher.find()) {
            String name = matcher.group(1);
            if (NON_SDK_TOKENS.contains(name)) {
                continue;
            }
            boolean found = false;
            for (Class<?> type : SDK_TYPES) {
                if (hasPublicMethod(type, name)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                unknown.add("." + name + "(...)");
            }
        }
        assertThat(unknown).as("the crib teaches chained calls that exist on no SDK type (rename? removal? typo?)").isEmpty();
    }

    private static boolean mentions(String crib, String type, String method) {
        Matcher matcher = QUALIFIED.matcher(crib);
        while (matcher.find()) {
            if (matcher.group(1).equals(type)) {
                for (String alternative : matcher.group(2).split("/")) {
                    if (alternative.equals(method)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("the crib still covers every step-builder entry point, so a whole capability cannot silently vanish from it")
    void cribCoversEveryStepEntryPoint() {
        String crib = cribText();
        Set<String> uncovered = new LinkedHashSet<>();
        for (Class<?> type : List.of(RestStep.class, KafkaStep.class, DbStep.class, GrpcStep.class)) {
            for (Method method : type.getMethods()) {
                boolean isEntryPoint = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getReturnType().equals(type);
                // The crib compresses siblings as `RestStep.get/post/put/delete(`, so a plain contains()
                // check misses every alternative after the first.
                if (isEntryPoint && !mentions(crib, type.getSimpleName(), method.getName())) {
                    uncovered.add(type.getSimpleName() + "." + method.getName() + "(...)");
                }
            }
        }
        assertThat(uncovered).as("step entry points the SDK offers but the crib never mentions — the agent cannot use what it is not told about (this is how db.write stayed invisible)").isEmpty();
    }
}
