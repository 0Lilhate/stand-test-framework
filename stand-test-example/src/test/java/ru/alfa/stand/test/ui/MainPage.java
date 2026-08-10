package ru.alfa.stand.test.ui;

import ru.alfa.stand.test.core.scenario.ScenarioStep;

/**
 * Главный экран приложения «Такса» — витрина разделов, на которую попадает вошедший пользователь.
 *
 * <p>Page Object: все локаторы экрана объявлены здесь и больше нигде — тело теста их не видит. Когда
 * разметка поедет, правится один файл, а не каждый тест, который на неё смотрел.
 *
 * <p>Все локаторы ниже сняты с живого экрана прогоном 2026-08-10 (скриншот упавшего шага), а не
 * предположены. Ни один из них не {@code data-testid} — их на экране нет, поэтому по метрике
 * хрупкости все три считаются хрупкими; это состояние экрана, а не выбор автора.
 */
final class MainPage {

    /** Алиас приложения в реестре окружений — единственный способ адресовать стенд. */
    static final String APP = "taksa";

    /** Окружение, в котором объявлен алиас. */
    static final String ENVIRONMENT = "ift";

    /** Путь главного экрана: корень приложения перенаправляет сюда. */
    private static final String PATH = "/main/";

    /** Роль вошедшего пользователя — подпись под именем в левой колонке. */
    private static final UiLocator ROLE_BADGE = UiLocator.text("Администратор");

    /** Плитка раздела продуктового каталога — первая на витрине. */
    private static final UiLocator PRODUCT_CATALOGUE_TILE = UiLocator.text("Продуктовый каталог");

    private MainPage() {
    }

    /**
     * Открывает главный экран.
     *
     * @return шаг сценария
     */
    static ScenarioStep open() {
        return UiStep.open(APP, PATH)
                .id("open-main")
                .build();
    }

    /**
     * Ждёт, пока витрина разделов отрисуется.
     *
     * <p>Именно {@code expectEventually} с явной границей, а не {@code expect}: экран приезжает
     * запросом после цепочки редиректов OIDC, и проверка без ожидания — самая частая причина флейка.
     *
     * @return шаг сценария
     */
    static ScenarioStep awaitLoaded() {
        return UiStep.expectEventually(APP, PRODUCT_CATALOGUE_TILE)
                .id("await-main-screen")
                .assertVisible()
                .withinSeconds(20)
                .build();
    }

    /**
     * Проверяет, что вход выполнен под учётной записью административной роли.
     *
     * @return шаг сценария
     */
    static ScenarioStep expectAdministratorRole() {
        return UiStep.expect(APP, ROLE_BADGE)
                .id("expect-administrator-role")
                .assertVisible()
                .build();
    }
}
