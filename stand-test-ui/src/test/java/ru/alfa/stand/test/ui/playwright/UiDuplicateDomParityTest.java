package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UITG-S023 anti-drift gate (ADR-UI-011 §3). The browser duplicate of the evaluation corpus, once made to
 * match its six seeded discovery reports, must keep matching them: an edit that moves the served DOM away from
 * what a report observes silently falsifies that report — the exact defect the corpus's own case
 * {@code ui-stale-discovery-testid-renamed} exists to describe.
 *
* <p>This test reads the «Elements observed» table of every
 * {@code UiDiscoveryReport.md} under {@code docs/agent-evaluation/dataset/cases} (six of them), serves the
 * duplicate's real HTML over its JDK HTTP server, and asserts that each reported Chosen locator still resolves
 * on the served DOM and that a {@code data-testid} the report marks absent stays absent. It is a plain unit
 * {@code test}, not a browser test; the module's {@code test} task declares the dataset as an input so any
 * edit to a report re-runs it instead of leaving it UP-TO-DATE.
 */
class UiDuplicateDomParityTest {

    private static final String DATASET_DIR_PROPERTY = "stand.test.dataset.dir";

    private static Path casesDir() {
        // Resolved by build.gradle.kts from the repo root; falling back to a path relative to the Gradle
        // working dir would silently break when the task is not run from that directory.
        String dir = System.getProperty(DATASET_DIR_PROPERTY);
        assertThat(dir).as("build.gradle.kts must set %s to the absolute docs/agent-evaluation dir", DATASET_DIR_PROPERTY).isNotNull();
        return Path.of(dir).resolve("dataset/cases");
    }

    /**
     * How many Chosen locators the four {@code /requests/new} reports record between them, and the two
     * {@code /requests} ones. Measured from the corpus, not chosen: they are what makes an empty or
     * half-parsed run distinguishable from a real one. A deliberate change to the seeded reports updates
     * these numbers; an accidental one fails here, which is the whole point.
     */
    private static final int REQUESTS_NEW_LOCATORS = 19;

    private static final int REQUESTS_LIST_LOCATORS = 10;

    private static final Pattern CHOSEN_LOCATOR =
            Pattern.compile("^(label|testId|css)=(.+?)\\s*$|^role=(button):(.+?)\\s*$");

    private static LocalUiTestApplication application;

    private static String requestsNew;

    private static String requests;

    @BeforeAll
    static void startDouble() throws IOException {
        application = new LocalUiTestApplication();
        requestsNew = get(application.baseUrl() + "/requests/new");
        requests = get(application.baseUrl() + "/requests");
    }

    @AfterAll
    static void stopDouble() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    @DisplayName("each seeded report whose observed screen is /requests/new resolves on the served DOM")
    void requestsNewMatchesEverySeededReport() throws IOException {
        int asserted = 0;
        for (Path report : seededReports()) {
            String text = Files.readString(report, StandardCharsets.UTF_8);
            if (text.contains("/requests/new")) {
                asserted += assertEveryLocatorOf(report, text, requestsNew);
            }
        }
        // ADR-UI-011 §3 makes this parity the condition of variant A, so it must fail loudly rather than
        // quietly check nothing: `elementsTable` locates its column by header, and a template whose heading
        // changed would parse to zero rows and leave both loops asserting nothing at all.
        assertThat(asserted).as("the /requests/new parity must have compared the locators the corpus records")
                .isEqualTo(REQUESTS_NEW_LOCATORS);
    }

    @Test
    @DisplayName("each seeded report whose observed screen is /requests resolves on the served DOM")
    void requestsListMatchesEverySeededReport() throws IOException {
        int asserted = 0;
        for (Path report : seededReports()) {
            String text = Files.readString(report, StandardCharsets.UTF_8);
            // Only the two reports that visited the list screen: they name /requests but never /requests/new.
            if (text.contains("/requests") && !text.contains("/requests/new")) {
                asserted += assertEveryLocatorOf(report, text, requests);
            }
        }
        assertThat(asserted).as("the /requests parity must have compared the locators the corpus records")
                .isEqualTo(REQUESTS_LIST_LOCATORS);
    }

    /**
     * Asserts every Chosen locator of one report against one screen's DOM, and returns how many it compared.
     *
     * <p>The count is the point as much as the assertions are. A report whose table parses to nothing is not
     * a report with no locators — it is a parser that stopped matching the template, and the caller turns
     * that into a failure instead of a silent pass.
     */
    private static int assertEveryLocatorOf(Path report, String text, String html) {
        List<Map<String, String>> rows = elementsTable(text);
        assertThat(rows)
                .as("%s: the «Elements observed» table must parse to at least one row; an empty parse is drift "
                        + "between the report template and this test, not a report without locators", report.getFileName())
                .isNotEmpty();
        for (Map<String, String> row : rows) {
            assertLocator(row, html, report);
        }
        return rows.size();
    }

    @Test
    @DisplayName("on /requests rung 1 does not exist — no data-testid anywhere and the table has no name")
    void listScreenCarriesNoTestId() {
        assertThat(requests)
                .as("the corpus /requests screen must have no data-testid attribute anywhere")
                .doesNotContain("data-testid");
        assertThat(requests)
                .as("no caption and no aria-label is why the report keeps the table on rung 6")
                .doesNotContain("<caption")
                .doesNotContain("aria-label");
    }

    @Test
    @DisplayName("the /requests table is exactly twelve rows — the bare-class count the fragile report records")
    void requestsTableHasTwelveRows() {
        // Count rows by a fragment the page script cannot contain (it builds rows from the data, not from the
        // literal number): every real tbody row is <tr><td class="requests-table__number">RQ-…
        assertThat(count(requests, "<td class=\"requests-table__number\">RQ-"))
                .as("the /requests table must hold twelve rows")
                .isEqualTo(12);
        assertThat(count(requests, "class=\"requests-table__delete\""))
                .as("each of the twelve rows carries one Удалить button")
                .isEqualTo(12);
        assertThat(count(requests, "class=\"requests-table\""))
                .as("the table itself is one element")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the confirmation dialog is in the DOM, hidden, and readable without a click")
    void confirmationDialogIsPresentHidden() {
        assertThat(requests).contains("Обращение будет удалено безвозвратно. Удалить?");
        assertThat(count(requests, "class=\"requests-confirm\" hidden"))
                .as("the confirm dialog must be present but hidden in the DOM")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("/api/requests registers a new request and mints a RQ-… number (the async send's backend)")
    void registerMintsARqNumber() throws Exception {
        String subject = "register-" + System.nanoTime();
        HttpResponse<String> response = postForm(application.baseUrl() + "/api/requests",
                "subject=" + subject + "&description=длинное описание");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsPattern("\"number\":\"RQ-\\d{4,}\"");
    }

    @Test
    @DisplayName("the duplicate-topic refusal is the engine behind ${testRunId} — it must stay a 409")
    void duplicateTopicIsRefusedAtTheApi() throws Exception {
        String topic = "dupe-run-" + System.nanoTime();
        String base = application.baseUrl() + "/api/requests";
        postForm(base, "subject=" + topic + "&description=first");
        // A genuinely NEW topic is accepted…
        HttpResponse<String> second = postForm(base, "subject=" + topic + "two&description=second");
        assertThat(second.statusCode()).isEqualTo(200);
        // …but the SAME topic a second time is refused, so a run must make its data unique via ${testRunId}.
        HttpResponse<String> dup = postForm(base, "subject=" + topic + "&description=again");
        assertThat(dup.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("GET /api/requests exports the twelve resident rows in JSON")
    void apiListsTheTwelveRows() throws Exception {
        HttpResponse<String> response = getRaw(application.baseUrl() + "/api/requests");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(count(response.body(), "\"number\""))
                .as("the exported list must carry twelve row objects")
                .isEqualTo(12);
    }

    /** Asserts one element row of a seeded report against the served HTML of the screen it observed. */
    private static void assertLocator(Map<String, String> row, String html, Path source) {
        // The markdown table wraps locators in backticks; strip them so the dialect parses.
        String chosen = row.get("chosen").replace("`", "").trim();
        Matcher m = CHOSEN_LOCATOR.matcher(chosen);
        assertThat(m.matches())
                .as("%s: unrecognised Chosen locator '%s'", source.getFileName(), chosen)
                .isTrue();
        String dialect = m.group(1) != null ? m.group(1) : "role=" + m.group(3);
        String value = m.group(2) != null ? m.group(2).trim() : m.group(4).trim();

        String reportTestId = row.get("data-testid").trim();
        boolean reportHasTestId = !reportTestId.isEmpty() && !"—".equals(reportTestId);

        int matches;
        switch (dialect) {
            case "label" -> matches = count(html, ">" + value + "</label>");
            case "role=button" -> matches = countButton(html, value);
            case "testId" -> matches = count(html, "data-testid=\"" + value + "\"");
            case "css" -> matches = countCss(html, value);
            default -> throw new IllegalStateException("unhandled Chosen locator dialect: " + chosen);
        }

        assertThat(matches)
                .as("Chosen locator '%s' (%s) must resolve on the duplicate", chosen, source.getFileName())
                .isGreaterThanOrEqualTo(1);

        // The report's data-testid column is authoritative about rung 1: a locator carrying a test id in the
        // DOM must be rung 1 (the report says it has one), and a locator free of it must be recorded "—".
        boolean domHasTestId = "testId".equals(dialect);
        assertThat(domHasTestId)
                .as("%s: DOM data-testid=%s but the report column says '%s'",
                        chosen, domHasTestId, row.get("data-testid"))
                .isEqualTo(reportHasTestId);
    }

    private static List<Path> seededReports() throws IOException {
        Path casesDir = casesDir();
        List<Path> reports = new ArrayList<>();
        try (var stream = Files.walk(casesDir)) {
            stream.filter(p -> p.getFileName().toString().equals("UiDiscoveryReport.md"))
                    .sorted()
                    .forEach(reports::add);
        }
        assertThat(reports).as("must discover six seeded reports under %s", casesDir).hasSize(6);
        return reports;
    }

    /** Parses the «Elements observed» markdown table into rows keyed by data-testid and Chosen locator. */
    private static List<Map<String, String>> elementsTable(String reportText) {
        List<Map<String, String>> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : reportText.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                continue;
            }
            if (t.contains("Chosen locator")) {
                inTable = true;
                continue;
            }
            if (!inTable) {
                continue;
            }
            List<String> cells = splitRow(t);
            if (cells.size() < 13 || !cells.get(0).trim().matches("\\d+")) {
                continue;
            }
            String chosen = cells.get(8).trim();
            if (chosen.isEmpty()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("data-testid", cells.get(2).trim());
            row.put("chosen", chosen);
            rows.add(row);
        }
        return rows;
    }

    /** Splits a markdown table row on '|', dropping the leading and trailing empty cells. */
    private static List<String> splitRow(String line) {
        String[] all = line.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (int i = 1; i < all.length - 1; i++) {
            cells.add(all[i]);
        }
        return cells;
    }

    private static int count(String html, String needle) {
        int n = 0;
        int idx = 0;
        while (html.indexOf(needle, idx) >= 0) {
            idx = html.indexOf(needle, idx) + needle.length();
            n++;
        }
        return n;
    }

    private static int countButton(String html, String text) {
        Pattern p = Pattern.compile("<button[^>]*>(?:\\s*)" + Pattern.quote(text) + "(?:\\s*)</button>");
        Matcher m = p.matcher(html);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** css=.requests-table tbody tr:first-child .requests-table__number → count the bare last class token. */
    private static int countCss(String html, String css) {
        String[] tokens = css.trim().split("\\s+");
        String last = tokens[tokens.length - 1].trim();
        if (last.startsWith(".")) {
            return count(html, "class=\"" + last.substring(1) + "\"");
        }
        return count(html, "<" + last + " ");
    }

    private static String get(String url) throws IOException {
        return getRaw(url).body();
    }

    private static HttpResponse<String> getRaw(String url) throws IOException {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + url, e);
        }
    }

    private static HttpResponse<String> postForm(String url, String formBody) throws IOException {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted posting " + url, e);
        }
    }
}