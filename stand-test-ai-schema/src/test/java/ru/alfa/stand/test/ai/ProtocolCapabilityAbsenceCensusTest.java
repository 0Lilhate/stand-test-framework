package ru.alfa.stand.test.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The protocol branch's twin of {@link UiCapabilityAbsenceCensusTest}: every "the SDK cannot do this"
 * the authoring kit tells stage 1, checked against the adapters that would have to do it.
 *
 * <p>The UI branch has had such a census for a while; the protocol branch had none, and its absence
 * claims are the more expensive of the two. They live in the {@code Feasibility flags} section of
 * {@code stand-test-case-analysis/SKILL.md}, under the instruction "mark a check
 * {@code NOT-AUTOMATABLE (current SDK)}" — so a claim that has gone stale does not merely misinform,
 * it makes the agent DROP a check the case asked for and hand the human a test with a hole in it,
 * labelled as a limitation of the SDK. That is the same failure this repository has now hit four
 * times in one direction: {@code grpc.unary} called equals-only in the scenario-design skill and in a
 * shipped KB example, kafka called the only equals-only adapter in {@code CLAUDE.md}, and — the
 * costliest — the Spring starter said not to auto-configure the UI executor, which told the agent to
 * STOP for the want of a bean ADR-UI-008 had already made unnecessary. Every one of the four was
 * found by reading, three of them by accident. This test is the part that does not depend on someone
 * happening to look.
 *
 * <p><strong>Two polarities, because the SDK refuses in two different ways.</strong> Some
 * capabilities are absent because the API simply is not there — there is no {@code RestStep.patch(},
 * and if one appeared the claim would be stale. Others are absent because the SDK actively REFUSES
 * them, and the refusal is a line of code: {@code AiStepNormalizer} throws on {@code expect.rowExists}
 * and on a gRPC {@code expect.status}, and the Kafka consumer is pinned to {@code auto.offset.reset
 * = latest}. For those the marker's DISAPPEARANCE is the signal, not its presence. Collapsing both
 * into one polarity was tried on paper and produces a probe that cannot fail for half the register.
 *
 * <p><strong>What this test does not claim.</strong> A marker is a name, and a name can go stale
 * without the capability changing — someone adds multi-row support spelled {@code expectAllRows(} and
 * the {@code expectRows(} probe reads "still absent". {@link #everyClaimIsProbedAndTheProbesResolve()}
 * is what keeps that from being silent: it requires every bullet of the kit's own list to be claimed
 * here, so a NEW bullet cannot arrive unprobed, and it requires every probe file to exist, so a moved
 * file fails loudly instead of reading as absence.
 */
class ProtocolCapabilityAbsenceCensusTest {

    private static final String REST = "stand-test-rest/src/main/java/ru/alfa/stand/test/rest/";

    private static final String DB = "stand-test-db/src/main/java/ru/alfa/stand/test/db/";

    private static final String KAFKA = "stand-test-kafka/src/main/java/ru/alfa/stand/test/kafka/";

    private static final String YAML = "stand-test-scenario-yaml/src/main/java/ru/alfa/stand/test/scenario/";

    private static final List<Claim> REGISTER = List.of(
            new Claim(
                    "multi-row / row-count / numeric-comparison DB assertions",
                    List.of("numeric comparison", "row counts"),
                    DB + "DbStep.java",
                    List.of("expectRows(", "expectRowCount(", "expectAtLeast(", "expectGreater"),
                    Polarity.STALE_IF_FOUND,
                    "DbStep offers expectValue(...) only — one row, one value. If a multi-row or comparison assertion has been added, the case-analysis feasibility list must stop telling stage 1 to drop such a check"),
            new Claim(
                    "a CONTAINS-style Kafka assertion",
                    List.of("contains substring", "equals-only adapters"),
                    KAFKA + "KafkaStep.java",
                    List.of("assertPathContains(", "assertPathMatches(", "matcher("),
                    Polarity.STALE_IF_FOUND,
                    "KafkaStep carries assertPath(...) only. A matcher family here would make kafka.expect a full-matcher adapter, and both this list and StepMatcherCapabilityCoverageTest's partition would have to say so"),
            new Claim(
                    "a non-equals DB assertion",
                    List.of("non-equals check on db", "equals-only adapters"),
                    DB + "DbStep.java",
                    List.of("matcher(", "expectContains(", "expectMatches("),
                    Polarity.STALE_IF_FOUND,
                    "db.expectEventually has no assertion type at all — DbValues delegates straight to AssertionMatchers.equalsMatch. A matcher on DbStep would end that, and CLAUDE.md's 'kafka and db are the equals-only adapters' would go stale with it"),
            new Claim(
                    "a DB absence assertion (`expect.rowExists`)",
                    List.of("row must not exist", "rowexists"),
                    YAML + "AiStepNormalizer.java",
                    List.of("'expect.rowExists' at "),
                    Polarity.STALE_IF_MISSING,
                    "the parser refuses expect.rowExists with 'is not executable yet'. If that refusal has gone, the AI format gained an absence assertion and the feasibility list must stop flagging it"),
            new Claim(
                    "asserting a non-OK gRPC status declaratively",
                    List.of("non-ok grpc status", "assertthatthrownby"),
                    YAML + "AiStepNormalizer.java",
                    List.of("'expect.status' at "),
                    Polarity.STALE_IF_MISSING,
                    "the parser refuses a gRPC expect.status: the status is surfaced as an exception. If that refusal has gone, the declarative track can assert it and the Java-only note is stale"),
            new Claim(
                    "observing Kafka messages published BEFORE the run",
                    List.of("published **before**", "start-from-now"),
                    KAFKA + "DefaultKafkaClientFactory.java",
                    List.of("AUTO_OFFSET_RESET_CONFIG, \"latest\""),
                    Polarity.STALE_IF_MISSING,
                    "the consumer is pinned to auto.offset.reset=latest, which is what makes kafka.expect start-from-now. If that pin has changed, the scenario can see earlier messages and the constraint on step order changes with it"),
            new Claim(
                    "capturing an HTTP response header or the status code into a variable",
                    List.of("response headers or the status code"),
                    REST + "RestStep.java",
                    List.of("captureHeader(", "captureStatus("),
                    Polarity.STALE_IF_FOUND,
                    "RestStep.capture(...) takes a JSONPath into the body and nothing else. A header or status capture would make a whole family of cases automatable that stage 1 is currently told to flag"),
            new Claim(
                    "PATCH and multipart requests",
                    List.of("patch / multipart", "patch/multipart"),
                    REST + "RestStep.java",
                    List.of("RestStep patch(", "multipart"),
                    Polarity.STALE_IF_FOUND,
                    "RestStep offers get/post/put/delete and expectEventually. A patch factory or multipart body would make those cases automatable"),
            // Not a Feasibility-flags bullet — this claim lives in the yaml-authoring skill and in the
            // kit README ("Not in the AI format at all: db.query/db.seed/db.cleanup, rest.put/rest.delete,
            // gRPC custom metadata"). It is registered here because its consequence is the same one the
            // list above has: the agent is told to leave the declarative track for the Java one.
            //
            // It was only HALF pinned before. StepMatcherCapabilityCoverageTest derives the schema's
            // step types from the defs that reach an assertion list, so a `rest.put` arriving WITH
            // assertions would fail there — while `db.seed`, which carries none, would not, and neither
            // would an assertion-free `rest.delete`. The probe below asks the schema directly.
            new Claim(
                    "db.seed / db.cleanup / db.query / rest.put / rest.delete in the AI format",
                    List.of("not in the ai format", "do not exist here"),
                    "stand-test-ai-schema/src/main/resources/schema/stand-test-scenario.schema.json",
                    List.of("\"db.seed\"", "\"db.cleanup\"", "\"db.query\"", "\"rest.put\"", "\"rest.delete\""),
                    Polarity.STALE_IF_FOUND,
                    "the schema declares seven step types and none of these. If one arrived, the declarative track gained it and both the yaml-authoring skill and the README must stop sending such a case to the Java track"));

    private static Path skill(String bundle) {
        return KitCensus.repositoryRoot().resolve("docs/ai-agent/" + bundle + "/skills/stand-test-case-analysis/SKILL.md");
    }

    /** The bullets of the kit's own feasibility list, lower-cased, one per line item. */
    private static List<String> feasibilityBullets(String bundle) {
        String text = KitCensus.read(skill(bundle));
        int from = text.indexOf("## Feasibility flags");
        assertThat(from).as("%s: the 'Feasibility flags' section must exist — this census reads it", bundle).isGreaterThanOrEqualTo(0);
        int to = text.indexOf("\n## ", from + 1);
        String section = to < 0 ? text.substring(from) : text.substring(from, to);

        List<String> bullets = new ArrayList<>();
        StringBuilder current = null;
        for (String line : section.split("\n")) {
            if (line.startsWith("- ")) {
                if (current != null) {
                    bullets.add(current.toString().toLowerCase(Locale.ROOT));
                }
                current = new StringBuilder(line.substring(2).trim());
            } else if (current != null && line.startsWith("  ") && !line.isBlank()) {
                current.append(' ').append(line.trim());
            } else if (current != null) {
                bullets.add(current.toString().toLowerCase(Locale.ROOT));
                current = null;
            }
        }
        if (current != null) {
            bullets.add(current.toString().toLowerCase(Locale.ROOT));
        }
        return bullets;
    }

    @Test
    @DisplayName("no NOT-AUTOMATABLE claim the kit makes names something the adapters can actually do")
    void noFeasibilityClaimIsStale() {
        List<String> stale = new ArrayList<>();
        for (Claim claim : REGISTER) {
            boolean found = claim.anyMarkerFound();
            boolean isStale = claim.polarity() == Polarity.STALE_IF_FOUND ? found : !found;
            if (isStale) {
                stale.add(claim.name() + " — " + claim.probeFile()
                        + (claim.polarity() == Polarity.STALE_IF_FOUND
                        ? " now contains one of " + claim.markers()
                        : " no longer contains " + claim.markers())
                        + ". Truth: " + claim.truth());
            }
        }

        assertThat(stale)
                .as("The kit tells stage 1 to mark these checks NOT-AUTOMATABLE and drop them. A claim that has gone "
                        + "stale therefore costs the case a check and reports the loss as an SDK limitation. Fix "
                        + "stand-test-case-analysis/SKILL.md in BOTH bundles, and anything downstream that repeats it")
                .isEmpty();
    }

    @Test
    @DisplayName("every bullet of the kit's feasibility list is claimed here, and every probe file exists")
    void everyClaimIsProbedAndTheProbesResolve() {
        for (String bundle : List.of(".claude", ".opencode")) {
            List<String> bullets = feasibilityBullets(bundle);
            assertThat(bullets).as("%s: the feasibility list must have been parsed — an empty read would make this census vacuous", bundle).isNotEmpty();

            for (String bullet : bullets) {
                boolean claimed = REGISTER.stream()
                        .anyMatch(claim -> claim.bulletKeywords().stream().anyMatch(bullet::contains));
                assertThat(claimed)
                        .as("%s: no probe covers the feasibility bullet '%s'. A new 'the SDK cannot do this' must arrive "
                                + "with the probe that will notice when it becomes false — that is the whole point of this census", bundle, bullet)
                        .isTrue();
            }
        }

        for (Claim claim : REGISTER) {
            assertThat(KitCensus.repositoryRoot().resolve(claim.probeFile()))
                    .as("the probe file of '%s' must exist — a moved file would read as an absent capability", claim.name())
                    .exists();
        }
    }

    @Test
    @DisplayName("the register probes both ways: some claims are proven by an API that is missing, some by a refusal that is present")
    void theRegisterProbesBothWays() {
        assertThat(REGISTER).filteredOn(claim -> claim.polarity() == Polarity.STALE_IF_FOUND)
                .as("a register with no missing-API probe would not notice a new builder method").isNotEmpty();
        assertThat(REGISTER).filteredOn(claim -> claim.polarity() == Polarity.STALE_IF_MISSING)
                .as("a register with no refusal probe would not notice a refusal being lifted").isNotEmpty();

        List<Claim> refusals = REGISTER.stream().filter(claim -> claim.polarity() == Polarity.STALE_IF_MISSING).toList();
        for (Claim claim : refusals) {
            assertThat(claim.anyMarkerFound())
                    .as("the refusal marker of '%s' must be found today — if it is not, either the SDK changed (fix the kit) "
                            + "or the marker went stale (repoint it). A stale refusal marker reads 'the capability arrived' "
                            + "and this test would be crying wolf", claim.name())
                    .isTrue();
        }
    }

    /** How a probe decides whether the kit's "cannot" is still true. */
    private enum Polarity {

        /** The marker is the API that would exist if the SDK could do it. Finding it means the claim is stale. */
        STALE_IF_FOUND,

        /** The marker is the SDK's own refusal. Losing it means the refusal is gone and the claim may be stale. */
        STALE_IF_MISSING
    }

    /**
     * @param name what the kit says the SDK cannot do
     * @param bulletKeywords lowercase fragments that identify the kit's own bullet for this claim
     * @param probeFile the adapter source that would have to change for the claim to go stale
     * @param markers the names to look for in it — any one is enough
     * @param polarity how presence is to be read
     * @param truth what a maintainer should write instead, if the probe fires
     */
    private record Claim(String name, List<String> bulletKeywords, String probeFile, List<String> markers,
                         Polarity polarity, String truth) {

        boolean anyMarkerFound() {
            String source = KitCensus.read(KitCensus.repositoryRoot().resolve(probeFile));
            return markers.stream().anyMatch(source::contains);
        }
    }

}
