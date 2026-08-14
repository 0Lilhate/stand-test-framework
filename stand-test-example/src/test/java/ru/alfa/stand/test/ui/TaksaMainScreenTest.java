package ru.alfa.stand.test.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.alfa.stand.test.core.StandClient;
import ru.alfa.stand.test.core.result.ScenarioResult;
import ru.alfa.stand.test.core.scenario.Scenario;
import ru.alfa.stand.test.junit.StandEnv;
import ru.alfa.stand.test.junit.StandScenarioId;
import ru.alfa.stand.test.junit.StandTest;

/**
 * Первый UI-автотест против живого стенда: вход технической учётной записью роли {@code admin} в
 * приложение «Такса» и главный экран с витриной разделов.
 *
 * <p>Исходный запрос звучал «авторизация и открытие списка заявок». Раздела с таким названием на
 * главном экране не оказалось — прогон 2026-08-10 показал восемь плиток (продуктовый каталог,
 * подписки и тарифы, продуктовый профиль клиента, справочники, администрирование, аудит, дизайнер,
 * операционные справочники), и путь {@code /requests} отвечает 404. Поэтому тест проверяет то, что
 * на экране действительно есть; шаг к списку заявок добавляется, когда назван раздел, в котором он
 * живёт.
 *
 * <p><strong>Этому тесту нужен настоящий стенд</strong> — доступный {@code taksa}, установленный
 * Chromium и доверие к внутреннему удостоверяющему центру, — тогда как остальные примеры модуля
 * работают на внутрипроцессных двойниках и зелены офлайн. Условие запуска с него снято по решению
 * владельца линии, поэтому он идёт в обычном прогоне и адрес со учётными данными берёт из
 * {@code application.yml}:
 *
 * <pre>
 * ./gradlew :stand-test-example:test --tests '*TaksaMainScreenTest'
 * </pre>
 *
 * <p><strong>Цена этого решения:</strong> {@code ./gradlew build} на машине без доступа к стенду или
 * без доверия к внутреннему удостоверяющему центру теперь краснеет — офлайн-инвариант модуля примеров
 * на этот тест больше не распространяется.
 *
 * <p>Другой стенд и другая учётная запись подставляются переменными {@code TAKSA_IFT_URL},
 * {@code web_username} и {@code web_password} — они перекрывают значения из файла, поэтому
 * конфигурацию для этого править не нужно.
 *
 * <p>Посмотреть прогон глазами: {@code -Dstand.test.ui.headless=false}. По умолчанию браузер
 * запускается без окна — он и сейчас настоящий, просто невидимый.
 *
 * <p>Ни логина, ни пароля в исходниках нет ни на одном уровне: шаг входа получает учётную запись по
 * РОЛИ из пула, а пул хранит только имена переменных. Вход требует доверия к внутреннему
 * удостоверяющему центру (сертификат {@code idp-test.alfaintra.net} выдан {@code TCA-SUB-ROOT}):
 * без него шаг падает с {@code ERR_CERT_AUTHORITY_INVALID}, и это настройка машины, а не теста —
 * опции игнорировать ошибки TLS у SDK нет.
 *
 * <p>Сценарий ничего не создаёт и не меняет — только читает, поэтому за собой не убирает и
 * остаточных данных не оставляет.
 */
@StandTest(env = MainPage.ENVIRONMENT)
@StandScenarioId("taksa-admin-opens-main-screen")
class TaksaMainScreenTest {

    @Test
    @DisplayName("администратор входит в систему и видит главный экран с разделами")
    void adminSignsInAndSeesTheMainScreen(StandClient stand, @StandScenarioId String id, @StandEnv String env) {
        Scenario scenario = Scenario.builder(id)
                .environment(env)
                .title("Авторизация администратора и главный экран «Такса»")
                .step(UiStep.login(MainPage.APP)
                        .id("login")
                        .role("admin")
                        .build())
                .step(MainPage.open())
                .step(MainPage.awaitLoaded())
                .step(MainPage.expectAdministratorRole())
                .build();

        ScenarioResult result = stand.run(scenario);

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.stepResults()).hasSize(4);
    }
}
