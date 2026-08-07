package ru.alfa.stand.test.ui.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.alfa.stand.test.await.AwaitPolicy;
import ru.alfa.stand.test.await.Awaiter;
import ru.alfa.stand.test.core.exception.StandTestException;
import ru.alfa.stand.test.ui.ElementSnapshot;
import ru.alfa.stand.test.ui.ResolvedUiApplication;
import ru.alfa.stand.test.ui.UiDriver;
import ru.alfa.stand.test.ui.UiLocator;
import ru.alfa.stand.test.ui.UiRunSettings;

/**
 * Browser-level acceptance of the two corpus screens the double now serves (UITG-S023, ADR-UI-011 option A):
 * «Обращение в поддержку» {@code /requests/new} and «Мои обращения» {@code /requests}. These prove the
 * behaviours the six seeded reports describe, which the DOM parity gate cannot reach with raw HTML: the
 * send-button enabling, the deferred validation message, the async register that mints an {@code RQ-…} number,
 * the duplicate-topic refusal — the only rule that forces {@code ${testRunId}} into the test data — and the
 * legacy twelve-row list whose delete control is deliberately non-unique. Tagged {@code browser}: they run in
 * {@code browserTest}, not in {@code test}.
 */
@Tag("browser")
class UiRequestsScreensBrowserTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(15);

    private LocalUiTestApplication application;

    private UiDriver driver;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startApplication() {
        this.application = new LocalUiTestApplication();
    }

    @AfterEach
    void closeBrowser() {
        if (this.driver != null) {
            this.driver.close();
            this.driver = null;
        }
        if (this.application != null) {
            this.application.close();
        }
    }

    @Test
    @DisplayName("/requests/new: «Отправить» is disabled while Тема is empty, enabled once valid — a state, not a fact")
    void sendButtonFollowsTheTopicField() {
        open();
        this.driver.navigate("/requests/new", TIMEOUT);

        assertThat(snapshot(button("Отправить")).enabled())
                .as("«Отправить» must be disabled while Тема is empty")
                .isFalse();

        this.driver.fill(UiLocator.label("Тема"), "Обрыв связи", TIMEOUT);
        this.driver.fill(UiLocator.label("Описание"), "Описание длиной больше десяти символов", TIMEOUT);

        assertThat(snapshot(button("Отправить")).enabled())
                .as("«Отправить» must become enabled once Тема is filled and Описание is valid")
                .isTrue();
    }

    @Test
    @DisplayName("/requests/new: a too-short description surfaces the validation text with a delay, then hides it (U19)")
    void shortDescriptionShowsDelayedValidationAndClears() {
        open();
        this.driver.navigate("/requests/new", TIMEOUT);
        this.driver.fill(UiLocator.label("Тема"), "тема", TIMEOUT);

        this.driver.fill(UiLocator.label("Описание"), "корот", TIMEOUT);
        // The message appears after the deferred rendering, so wait for it instead of probing once.
        awaitTrue(() -> snapshot(UiLocator.testId("description-error")).visible(),
                "the validation message to appear");
        assertThat(snapshot(UiLocator.testId("description-error")).text())
                .isEqualTo("Описание должно содержать не менее 10 символов");

        this.driver.fill(UiLocator.label("Описание"), "теперь достаточно длинное описание", TIMEOUT);
        awaitTrue(() -> !snapshot(UiLocator.testId("description-error")).present(),
                "the validation message to vanish from the DOM");
    }

    @Test
    @DisplayName("/requests: the legacy table loads twelve rows and the delete button is deliberately non-unique")
    void listLoadsTwelveRowsAndDeleteIsNotUnique() {
        open();
        this.driver.navigate("/requests", TIMEOUT);

        // The first-row number cell is the report's rung-6 locator; it resolves because the table is rendered.
        ElementSnapshot firstNumber = snapshot(UiLocator.css(
                ".requests-table tbody tr:first-child .requests-table__number"));
        assertThat(firstNumber.present()).isTrue();
        assertThat(firstNumber.text()).matches("RQ-\\d{4,}");

        // The bare delete class matches twelve elements, so a single element cannot be addressed with it — the
        // report records that exact limitation (fragile case row 5).
        assertThatThrownBy(() -> this.driver.snapshot(
                        UiLocator.css(".requests-table__delete"), Set.of(), Duration.ofMillis(500)))
                .isInstanceOf(StandTestException.class)
                .hasMessageContaining(".requests-table__delete")
                .hasMessageContaining("12 elements");
    }

    @Test
    @DisplayName("/requests: a filter+«Показать» re-renders the table after the observed pause")
    void filterShowReRendersAfterThePause() {
        open();
        this.driver.navigate("/requests", TIMEOUT);

        this.driver.fill(UiLocator.label("Статус"), "Черновик", TIMEOUT);
        this.driver.click(button("Показать"), TIMEOUT);

        // The report observes about a second before the filtered table appears; asserting once would race the
        // re-render. Wait for the first-row number cell to remain resolvable through the transition.
        awaitTrue(() -> snapshot(UiLocator.css(
                        ".requests-table tbody tr:first-child .requests-table__number")).present(),
                "the filtered table to re-render");
    }

    private UiDriver open() {
        this.driver = new PlaywrightDriverFactory().open(
                new ResolvedUiApplication("client-portal", this.application.baseUrl(), null),
                new UiRunSettings(headless(), "chromium", TIMEOUT, TIMEOUT, this.tempDir));
        return this.driver;
    }

    private ElementSnapshot snapshot(UiLocator locator) {
        return this.driver.snapshot(locator, Set.of(), TIMEOUT);
    }

    private static UiLocator button(String name) {
        return UiLocator.role("button", name);
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        Awaiter.create().await(
                AwaitPolicy.builder(what).timeout(LONG_TIMEOUT).pollInterval(Duration.ofMillis(100)).build(),
                condition::getAsBoolean,
                satisfied -> satisfied)
                .orElseThrow();
    }

    private static boolean headless() {
        return Boolean.parseBoolean(System.getProperty(UiRunSettings.HEADLESS_PROPERTY, "true"));
    }
}
