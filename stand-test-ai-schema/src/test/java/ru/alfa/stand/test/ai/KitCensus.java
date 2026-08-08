package ru.alfa.stand.test.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one reader of the kit's gate census, shared by every test that holds a document against it.
 *
 * <p>Three tests now compare something to {@code detectors.json} and to the checklist's <em>Machine
 * coverage</em> table: {@link UiHumanGateCensusTest}, {@link UiMachineGateCensusTest} and
 * {@link UiReadinessCensusTest}. Each of them originally carried its own copy of the parse. That is the
 * very shape those tests exist to forbid — a second place that goes stale when the table's format
 * changes — and the third copy is what made it obvious: two parsers with the same name had already
 * drifted apart in whether they dropped {@code U4}.
 *
 * <p>So the parse lives here once, and the tests differ only in what they assert. Nothing in this class
 * decides anything: it reads files and returns what they say.
 */
final class KitCensus {

    /** The bundle copies the kit ships; every census must hold for both. */
    static final String[] BUNDLES = {".claude", ".opencode"};

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A detector id: the kit spells every one as a backticked SCREAMING_SNAKE token. */
    private static final Pattern DETECTOR_ID = Pattern.compile("`([A-Z][A-Z0-9_]{4,})`");

    /** A gate identifier wherever it is named in a table cell. */
    private static final Pattern GATE = Pattern.compile("\\bU(\\d+[ab]?)\\b");

    private KitCensus() {
    }

    /** The repository root, found by walking up to the directory that holds {@code docs/ai-agent}. */
    static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (Files.isDirectory(current.resolve(Paths.get("docs", "ai-agent")))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found upwards from " + Paths.get("").toAbsolutePath());
    }

    /** Reads a file as UTF-8, turning the checked failure into an unchecked one for test readability. */
    static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }

    /** The guard rule of one bundle copy. */
    static String guardRule(String bundle) {
        return read(repositoryRoot().resolve("docs/ai-agent/" + bundle + "/rules/stand-test-ui-guardrails.md"));
    }

    /** The UI safety checklist of one bundle copy. */
    static String checklist(String bundle) {
        return read(repositoryRoot()
                .resolve("docs/ai-agent/" + bundle + "/skills/stand-test-ui-safety-review/ui-safety-checklist.md"));
    }

    /** Every detector declared in one bundle copy's {@code detectors.json}, as parsed nodes. */
    static JsonNode detectors(String bundle) {
        Path table = repositoryRoot().resolve("docs/ai-agent/" + bundle + "/hooks/detectors.json");
        try {
            return MAPPER.readTree(read(table)).path("detectors");
        } catch (IOException e) {
            throw new UncheckedIOException("could not parse " + table, e);
        }
    }

    /** Every {@code ruleId} declared in one bundle copy's {@code detectors.json}, in source order. */
    static Set<String> ruleIds(String bundle) {
        Set<String> ids = new LinkedHashSet<>();
        detectors(bundle).forEach(detector -> ids.add(detector.path("ruleId").asText()));
        return ids;
    }

    /**
     * Detectors a java artefact can trip: {@code appliesTo} names the artefact kind, and {@code any}
     * includes java. Derived rather than counted by hand, so a detector added for prose only does not
     * silently change a number stated about java.
     */
    static int javaApplicableCount(String bundle) {
        int count = 0;
        for (JsonNode detector : detectors(bundle)) {
            String appliesTo = detector.path("appliesTo").asText();
            if ("any".equals(appliesTo) || "java".equals(appliesTo)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The rows of the checklist's <em>Machine coverage</em> table that claim coverage.
     *
     * <p>A row claims coverage when its verdict column answers yes or partly. The eye-only row answers
     * {@code **no detector in this kit**} and is what must NOT be collected.
     */
    static Set<String> coverageRows(String bundle) {
        Set<String> rows = new LinkedHashSet<>();
        for (String line : checklist(bundle).split("\\R")) {
            String row = line.trim();
            if (!row.startsWith("|") || !row.contains("U")) {
                continue;
            }
            if (row.contains("| yes") || row.contains("| partly")) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The gates the coverage table claims a machine checks.
     *
     * <p>{@code U4} is included: the table covers its secret half and leaves its semantic half to the
     * eye, and both censuses say so. A caller comparing against an eye-only list must drop {@code U4}
     * itself — the split gate is the one place where "covered" and "eye only" are both true.
     */
    static Set<String> coveredGates(String bundle) {
        Set<String> gates = new TreeSet<>();
        for (String row : coverageRows(bundle)) {
            // Only the gate column names gates; the verdict column names detectors, and a detector id
            // never matches the gate shape.
            String gateColumn = row.split("\\|")[1];
            Matcher matcher = GATE.matcher(gateColumn);
            while (matcher.find()) {
                gates.add("U" + matcher.group(1));
            }
        }
        return gates;
    }

    /** The detector ids the coverage table names — the kit's own answer to "which rules serve a UI gate". */
    static Set<String> namedDetectors(String bundle) {
        Set<String> ids = new TreeSet<>();
        for (String row : coverageRows(bundle)) {
            Matcher matcher = DETECTOR_ID.matcher(row);
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    /** Every gate named anywhere in the given text fragment, e.g. one table row. */
    static Set<String> gatesIn(String text) {
        Set<String> gates = new TreeSet<>();
        Matcher matcher = GATE.matcher(text);
        while (matcher.find()) {
            gates.add("U" + matcher.group(1));
        }
        return gates;
    }
}
