# ADR-0012: Ноль новых записей в version catalog

**Статус:** Accepted · **Дата:** 2026-07-27

## Контекст

Резолюция зависимостей идёт только через внутренний Artifactory, публичного fallback **нет
намеренно** (`settings.gradle.kts:1-10`): «a silent fall-through to Maven Central would either hang
on an unreachable host or resolve artefacts the org has not vetted». Любая новая библиотека требует
заведения в зеркале — организационная процедура с неизвестным сроком.

Репозиторий также придерживается осознанных ограничений: Jackson не в main-графе (json-path
использует json-smart), SnakeYAML вместо Jackson для YAML, `spring-webflux` с JDK-коннектором без
reactor-netty, Testcontainers отсутствует намеренно.

## Решение

Модули агента реализуются **без единой новой записи** в `gradle/libs.versions.toml`.

| Потребность | Чем закрывается | Статус в каталоге |
|---|---|---|
| Логирование | `slf4j-api` 2.0.16 | ✅ есть, используется SDK |
| JSON (trace, tool I/O, LLM) | `jackson-databind` 2.18.2 | ✅ есть (пока `testImplementation` в ai-schema) |
| Валидация JSON Schema | `networknt-json-schema-validator` 1.5.6 | ✅ есть |
| YAML (KB, skills, project memory) | `snakeyaml` 2.3 | ✅ есть, используется `config` и `scenario-yaml` |
| HTTP к LLM | `java.net.http.HttpClient` | JDK 17, зависимость не нужна |
| Запуск процессов | `java.lang.ProcessBuilder` | JDK |
| Разбор `TEST-*.xml` | `javax.xml.parsers` (JDK), SAX | JDK |
| Тесты | JUnit 5 + AssertJ + ArchUnit | ✅ есть |

**Существенно:** Jackson и networknt повышаются с `testImplementation` до `implementation`
**только в модулях агента**. Main-граф SDK остаётся Jackson-free — модули агента не входят в
тестовый classpath потребителей SDK (ADR-0001).

## Рассмотренные альтернативы

**А. LangChain4j / Spring AI.**
Отклонено (подробно — ADR-0003). Здесь добавляется довод про зеркало: фреймворк тянет десятки
транзитивных артефактов, каждый из которых должен пройти vetting организации.

**Б. OkHttp / Apache HttpClient вместо JDK `HttpClient`.**
Отклонено: JDK-клиент покрывает нужное (POST, JSON, таймауты, отмена). SDK уже сделал такой же
выбор — WebClient на `JdkClientHttpConnector`, чтобы не тянуть reactor-netty.

**В. Библиотека для JSONL/структурного логирования (Logstash encoder и т. п.).**
Отклонено: формат события — плоский JSON-объект; Jackson справляется. Приносить кодировщик ради
конкатенации строк неоправданно.

**Г. Testcontainers для изоляции прогонов агента.**
Отклонено: repo-wide решение «Testcontainers is explicitly not the basis». Изоляция достигается
workspace'ом (ADR-0010).

**Д. SQLite для Run Memory.**
Отклонено (подробно — ADR-0005): файлы читаются `jq`, переживают падение процесса, не требуют
драйвера.

## Последствия

**Положительные.** Реализация стартует без ожидания заведения артефактов в Artifactory. Совокупный
транзитивный граф не растёт. Единая версия JSON-стека с уже используемой в тестах ai-schema.

**Отрицательные.** Часть кода придётся написать самим: HTTP-повторы, парсер `TEST-*.xml`, чтение
`allure-results`. Оценочно — сотни строк, каждая тестируемая без окружения. Приемлемо; в обмен
получаем контроль над поведением при отказах, который у чужой библиотеки пришлось бы изучать.

Переход Jackson из test в implementation требует внимания: ArchUnit-правило должно гарантировать,
что он не просочится в main-граф SDK.

**Нейтральные.** Решение не запрещает добавление зависимости навсегда — оно требует, чтобы
добавление было отдельным осознанным шагом с обоснованием, а не побочным эффектом выбора
фреймворка.

## Как проверить, что решение соблюдается

1. Тест/CI: `git diff` по `gradle/libs.versions.toml` в MR модулей агента пуст (либо изменение
   сопровождается новым ADR).
2. ArchUnit: ни один SDK-модуль не получает Jackson в `implementation` — проверяется
   `./gradlew :stand-test-core:dependencies` и правилом на импорты.
3. ArchUnit: `java.net.http..` импортируется только в `stand-test-agent-llm`.
4. ArchUnit: `stand-test-agent-core` не зависит ни от чего, кроме `java..`, `org.slf4j..`,
   `ru.alfa.stand.test.core..`.
