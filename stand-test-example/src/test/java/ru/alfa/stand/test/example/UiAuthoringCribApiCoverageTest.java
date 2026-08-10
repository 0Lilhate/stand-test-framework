package ru.alfa.stand.test.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.assertion.AssertionMatcher;
import ru.alfa.stand.test.core.environment.UiAuthScheme;
import ru.alfa.stand.test.core.environment.UiLoginChallenge;
import ru.alfa.stand.test.ui.LocatorStrategy;
import ru.alfa.stand.test.ui.UiCaptureSource;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiProperty;
import ru.alfa.stand.test.ui.UiStep;

/**
 * The UI half of {@link AuthoringCribApiCoverageTest}: the kit's UI surface must be the SDK's UI surface.
 *
 * <p>The protocol crib has been pinned to the real API since the day the gRPC matcher claim went stale
 * in it. The UI branch had no such pin, and it carries MORE risk rather than less: its surface is
 * written down TWICE by hand — once as {@code ui-sdk-surface-checklist.md} (what stage 6 transcribes)
 * and once as the "Builder surface" paragraph of {@code stand-test-ui-guardrails.md} (what stage 7
 * reviews against) — so the two could disagree with each other as well as with the adapter, and
 * nothing compared any of the three.
 *
 * <p><strong>Why the protocol test could not simply be pointed at the UI files.</strong> It scans a
 * whole document for anything shaped like a call and requires each to exist. Over the UI assets that
 * produces false findings by construction, because those documents QUOTE THE APIS THEY BAN:
 * {@code .waitFor(} and {@code .xpath(} appear in the guardrails precisely as things the SDK does not
 * have and an agent must not write. A check that cannot tell a ban from a teaching would report the
 * bans as drift. So this test reads STRUCTURE instead: the named sections that enumerate the surface,
 * and the code spans inside them. Everything outside those sections — prose, bans, examples of what
 * not to do — is deliberately not scanned.
 *
 * <p>Only the {@code .claude} copy is read, as in the protocol test: {@code BundleParityTest} already
 * holds the two bundle copies byte-identical, so checking both here would pin the same bytes twice.
 */
class UiAuthoringCribApiCoverageTest {

    /** The sections of the checklist that enumerate callable API, and nothing else. */
    private static final List<String> API_SECTIONS = List.of("## Step types", "## Builder methods", "## Locators");

    /** A code span: the kit writes every API token in backticks. */
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

    /** {@code UiStep.open(...)}, {@code .asSensitive()}, {@code capture(String var)} — with or without a type and a leading dot. */
    private static final Pattern NAMED_CALL = Pattern.compile("\\.?(?:([A-Z][A-Za-z]*)\\.)?([a-z][A-Za-z0-9]*)(\\(.*\\))?");

    /** An enum constant as the kit spells it. */
    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z_0-9]+");

    private static Path bundleFile(String... relative) {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(Paths.get("docs", "ai-agent", ".claude"));
            for (String part : relative) {
                candidate = candidate.resolve(part);
            }
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("kit asset " + String.join("/", relative) + " not found upwards from " + Paths.get("").toAbsolutePath());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    private static String checklist() {
        return read(bundleFile("skills", "stand-test-ui-java-authoring", "ui-sdk-surface-checklist.md"));
    }

    private static String guardRule() {
        return read(bundleFile("rules", "stand-test-ui-guardrails.md"));
    }

    /**
     * One {@code ## } section, up to the next one.
     *
     * <p>Anchored on the heading rather than on line numbers: a heading that is renamed yields an empty
     * section, which {@link #everySectionWasParsed()} turns into a failure instead of a silent pass.
     */
    private static String section(String document, String heading) {
        int start = document.indexOf(heading);
        if (start < 0) {
            return "";
        }
        int end = document.indexOf("\n## ", start + heading.length());
        return (end < 0) ? document.substring(start) : document.substring(start, end);
    }

    /** The paragraph that follows a marker, up to the blank line that ends it. */
    private static String paragraphAfter(String document, String marker) {
        int start = document.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int end = document.indexOf("\n\n", start);
        return (end < 0) ? document.substring(start) : document.substring(start, end);
    }

    /**
     * Every API call named inside a fragment, as (owning type or empty, method name).
     *
     * <p>A bare backticked identifier counts as a method here, and that is a decision rather than a
     * convenience: the guard rule's surface paragraph writes half its entries without a signature
     * ({@code `id`}, {@code `assertText`}) while the checklist writes all of them with one, so the
     * stricter reading — parentheses or a type, or it is not a call — silently dropped five methods and
     * made the two-copy comparison below weaker than it looked. The looser reading is safe because
     * these fragments enumerate API and nothing else; that was measured over all four before it was
     * relied on, not assumed. If prose in backticks ever lands in one of them, this test reports it as
     * an unknown call — loud, and the fix is to move the prose out of a section whose contract is to
     * list API.
     */
    private static Set<NamedCall> namedCalls(String fragment) {
        Set<NamedCall> calls = new LinkedHashSet<>();
        Matcher spans = CODE_SPAN.matcher(fragment);
        while (spans.find()) {
            Matcher call = NAMED_CALL.matcher(spans.group(1).trim());
            if (call.matches()) {
                calls.add(new NamedCall(call.group(1) == null ? "" : call.group(1), call.group(2)));
            }
        }
        return calls;
    }

    /** Every enum constant named inside a fragment. */
    private static Set<String> namedConstants(String fragment) {
        Set<String> constants = new TreeSet<>();
        Matcher spans = CODE_SPAN.matcher(fragment);
        while (spans.find()) {
            String span = spans.group(1).trim();
            if (CONSTANT.matcher(span).matches()) {
                constants.add(span);
            }
        }
        return constants;
    }

    private static boolean hasPublicMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> constantsOf(List<Class<? extends Enum<?>>> enums) {
        Set<String> names = new TreeSet<>();
        for (Class<? extends Enum<?>> type : enums) {
            for (Object constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
        }
        return names;
    }

    /** The API the checklist and the guard rule together enumerate. */
    private static Set<NamedCall> everyNamedCall() {
        Set<NamedCall> calls = new LinkedHashSet<>();
        String checklist = checklist();
        for (String heading : API_SECTIONS) {
            calls.addAll(namedCalls(section(checklist, heading)));
        }
        calls.addAll(namedCalls(paragraphAfter(guardRule(), "Builder surface:")));
        return calls;
    }

    @Test
    @DisplayName("every call the UI surface names exists — on its type when qualified, somewhere in the UI API when chained")
    void everyNamedCallExists() {
        Set<String> missing = new TreeSet<>();
        for (NamedCall call : everyNamedCall()) {
            boolean found = switch (call.type()) {
                case "UiStep" -> hasPublicMethod(UiStep.class, call.name());
                case "UiLocator" -> hasPublicMethod(UiLocator.class, call.name());
                case "" -> hasPublicMethod(UiStep.class, call.name()) || hasPublicMethod(UiLocator.class, call.name());
                default -> throw new IllegalStateException("the UI surface names an unmapped type: " + call.type());
            };
            if (!found) {
                missing.add(call.type().isEmpty() ? "." + call.name() + "(...)" : call.type() + "." + call.name() + "(...)");
            }
        }
        assertThat(missing)
                .as("the kit teaches UI calls the adapter does not have — a generated UI test would not compile, and the agent has no "
                        + "other source for this API: the checklist IS what stage 6 transcribes. The other way to land here is a word "
                        + "in backticks that is not API at all, inside a section whose contract is to enumerate API — move it out")
                .isEmpty();
    }

    @Test
    @DisplayName("every step factory the adapter offers is named in the checklist — a capability cannot vanish from the kit unnoticed")
    void everyStepFactoryIsNamed() {
        Set<NamedCall> named = namedCalls(section(checklist(), "## Step types"));
        Set<String> unmentioned = new TreeSet<>();
        for (Method method : UiStep.class.getMethods()) {
            boolean factory = Modifier.isStatic(method.getModifiers()) && method.getReturnType().equals(UiStep.class);
            if (factory && !named.contains(new NamedCall("UiStep", method.getName()))) {
                unmentioned.add("UiStep." + method.getName() + "(...)");
            }
        }
        assertThat(unmentioned)
                .as("step types the adapter offers and the checklist never mentions. The agent cannot use what it is not told about, and "
                        + "the surface document is explicitly the anti-invention reference — an unmentioned step is an invisible one")
                .isEmpty();
    }

    @Test
    @DisplayName("the two hand-written copies of the builder surface agree — the checklist stage 6 writes from, and the rule stage 7 reviews against")
    void theTwoCopiesOfTheBuilderSurfaceAgree() {
        Set<String> fromChecklist = new TreeSet<>();
        namedCalls(section(checklist(), "## Builder methods")).forEach(call -> fromChecklist.add(call.name()));
        Set<String> fromRule = new TreeSet<>();
        namedCalls(paragraphAfter(guardRule(), "Builder surface:")).forEach(call -> fromRule.add(call.name()));

        assertThat(fromRule)
                .as("the guard rule's Builder surface and the checklist's Builder methods have drifted apart. Whichever is right, an "
                        + "author and a reviewer are now working from different surfaces — the case where a generated test is written "
                        + "against one document and refused against the other")
                .isEqualTo(fromChecklist);
    }

    @Test
    @DisplayName("every constant of the UI enums is named where the checklist enumerates that enum")
    void everyEnumConstantIsNamed() {
        String checklist = checklist();
        Set<String> unmentioned = new TreeSet<>();
        collectUnmentioned(section(checklist, "## Locators"), constantsOf(List.of(LocatorStrategy.class)), unmentioned);
        collectUnmentioned(section(checklist, "## Properties and matchers"),
                constantsOf(List.of(UiProperty.class, UiCaptureSource.class, AssertionMatcher.class)), unmentioned);
        collectUnmentioned(section(checklist, "## Sign-in"), constantsOf(List.of(UiAuthScheme.class)), unmentioned);
        // The challenge kinds are spelled in the registry's lower case (`mfa | otp | captcha`), so the
        // comparison is case-insensitive here and only here.
        String signIn = section(checklist, "## Sign-in").toLowerCase(Locale.ROOT);
        for (UiLoginChallenge challenge : UiLoginChallenge.values()) {
            if (!signIn.contains(challenge.name().toLowerCase(Locale.ROOT))) {
                unmentioned.add("UiLoginChallenge." + challenge.name());
            }
        }
        assertThat(unmentioned)
                .as("enum constants the SDK offers that the surface document never names. A locator strategy, a property, a matcher or "
                        + "a sign-in scheme the kit does not mention is one the agent will never generate — the same invisibility as an "
                        + "unmentioned step type, one level down")
                .isEmpty();
    }

    @Test
    @DisplayName("every constant the checklist names really exists in the enum that section is about")
    void everyNamedConstantExists() {
        String checklist = checklist();
        Set<String> unknown = new TreeSet<>();
        collectUnknown(namedConstants(section(checklist, "## Locators")), constantsOf(List.of(LocatorStrategy.class)), unknown);
        collectUnknown(namedConstants(section(checklist, "## Properties and matchers")),
                constantsOf(List.of(UiProperty.class, UiCaptureSource.class, AssertionMatcher.class)), unknown);
        collectUnknown(namedConstants(section(checklist, "## Sign-in")),
                constantsOf(List.of(UiAuthScheme.class, UiLoginChallenge.class)), unknown);
        assertThat(unknown)
                .as("the checklist names constants that no longer exist in the enum its section is about — a matcher or a property "
                        + "removed from the SDK is still being taught, and a test written from it will not compile")
                .isEmpty();
    }

    @Test
    @DisplayName("every section this census reads was actually parsed — a renamed heading must fail, not quietly empty the check")
    void everySectionWasParsed() {
        String checklist = checklist();
        for (String heading : API_SECTIONS) {
            assertThat(namedCalls(section(checklist, heading)))
                    .as("the checklist section '" + heading + "' yielded no API call. Either the heading was renamed — in which case "
                            + "this census silently stopped reading the document it exists for — or the section really is empty")
                    .isNotEmpty();
        }
        assertThat(namedCalls(paragraphAfter(guardRule(), "Builder surface:")))
                .as("the guard rule's 'Builder surface:' paragraph yielded no API call — the marker was renamed and the two-copy "
                        + "comparison above quietly became a comparison of two empty sets")
                .isNotEmpty();
        assertThat(namedConstants(section(checklist, "## Properties and matchers")))
                .as("the checklist's properties/matchers section yielded no constant")
                .isNotEmpty();
        assertThat(namedConstants(section(checklist, "## Sign-in")))
                .as("the checklist's sign-in section yielded no constant")
                .isNotEmpty();
    }

    private static void collectUnmentioned(String fragment, Set<String> expected, Set<String> into) {
        Set<String> named = namedConstants(fragment);
        for (String constant : expected) {
            if (!named.contains(constant)) {
                into.add(constant);
            }
        }
    }

    private static void collectUnknown(Set<String> named, Set<String> real, Set<String> into) {
        for (String constant : named) {
            if (!real.contains(constant)) {
                into.add(constant);
            }
        }
    }

    /**
     * An API call as the kit writes it: the owning type when the kit qualified it, empty when it wrote
     * the call chained ({@code .within(...)}) and the type is therefore whichever offers it.
     *
     * @param type the owning type's simple name, or empty for a chained call
     * @param name the method name
     */
    private record NamedCall(String type, String name) {
    }
}
