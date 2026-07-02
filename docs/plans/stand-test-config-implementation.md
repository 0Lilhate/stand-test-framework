# План реализации — stand-test-config (файловый загрузчик конфигурации сред)

## Контекст / цель

Дать не-Spring потребителям (чистый JUnit + Java/YAML-DSL) способ наполнить `EnvironmentRegistry` из
файла и закрыть «known gap»: сейчас `StandTestExtension.buildStandClient()` резолвит реестр через
`ServiceLoader.load(EnvironmentRegistry.class).findFirst()` и **фолбэчит на пустой**
`InMemoryEnvironmentRegistry(Map.of())`, из-за чего рантайм-резолв алиасов (service/topic/datasource/
grpc) падает. SPI-seam в `StandTestExtension` **уже существует** — не хватает самого провайдера.

**Spring-путь уже закрыт** и не трогается: `StandTestProperties` (`@ConfigurationProperties("stand.test")`)
+ `EnvironmentRegistryFactory` + `StandTestAutoConfiguration` строят populated-реестр.

## Установленные факты (из анализа кода)

- `StandTestExtension` (junit): `EnvironmentRegistry registry = ServiceLoader.load(EnvironmentRegistry.class).findFirst().orElseGet(() -> new InMemoryEnvironmentRegistry(Map.of()));` — точка подключения.
- Доки `docs/arch/stand-test-example-next-steps.md`: провайдер `EnvironmentRegistry` через
  `META-INF/services/ru.alfa.stand.test.core.environment.EnvironmentRegistry`, оборачивающий
  `InMemoryEnvironmentRegistry` — это и есть выбранный дизайн.
- `SafeYaml` (SnakeYAML `SafeConstructor` + `setMaxAliasesForCollections(10)` + `setNestingDepthLimit(50)`
  + `setAllowDuplicateKeys(false)`) есть в `stand-test-scenario-yaml` (package-private), образец для копии.
- core — JDK-only, без SnakeYAML/Jackson и без file-parsing (нельзя класть загрузчик туда).
- Все `*Ref`-поля definition-записей — **имена env-переменных**, резолвятся в рантайме адаптерами
  (`EnvironmentReferenceResolver`/`BaseUrlResolver` → `System::getenv`). Реестр хранит **ссылки, не значения**.
- Точные контракты core-записей (поля/валидации): `EnvironmentDefinition(name, services, topics,
  datasources, grpcTargets, kafkaCluster)`; `ServiceEndpointDefinition(name, baseUrlRef, correlation)`;
  `TopicDefinition(alias, name, correlation)`; `DatasourceDefinition(alias, urlRef, userRef, passwordRef,
  allowedSchemas, writeAllowed)`; `GrpcTargetDefinition(alias, targetRef, correlation)`;
  `KafkaClusterDefinition(bootstrapServersRef, securityProtocolRef, saslJaasConfigRef)`;
  `CorrelationConfig(source, name)`; `CorrelationSource {HEADER, KEY, PAYLOAD_FIELD, METADATA}`.

## Почему отдельный модуль `stand-test-config`

- core — нельзя (JDK-only, без SnakeYAML).
- junit — должен остаться без формата-конфига и без SnakeYAML.
- scenario-yaml — движок сценариев; env-config ортогонален (иначе Java-DSL-потребитель тянет YAML-движок
  сценариев ради конфигурации).
- Новый `stand-test-config` (core `api` + SnakeYAML `implementation`, как у scenario-yaml) — чистые
  границы; подхватывается по SPI без кода со стороны потребителя.

## Структура (`ru.alfa.stand.test.config`)

| Класс | Роль |
|---|---|
| `SafeYaml` | копия hardened-лоадера из scenario-yaml (SafeConstructor + лимиты алиасов/глубины), с комментарием-кросс-ссылкой |
| `EnvironmentConfig` | **канонический маппер** `Map<String,Object>` → `EnvironmentRegistry`: строит все definition-записи; fail-closed на unknown-ключах; ошибки record-ctor → `StandTestException` с путём `environments.<env>.services.<alias>: <msg>` |
| `YamlEnvironmentConfigLoader` | резолвит источник → `SafeYaml.load` → `EnvironmentConfig.toRegistry` |
| `FileEnvironmentRegistry` | no-arg `implements EnvironmentRegistry`, **лениво** делегирует загруженному реестру (кэш); зарегистрирован в `META-INF/services/ru.alfa.stand.test.core.environment.EnvironmentRegistry` |

## Формат файла (YAML; зеркалит Spring-дерево `stand.test.environments` без префикса)

```yaml
environments:
  ift:
    services:
      client-service: { base-url-ref: CLIENT_SERVICE_URL, correlation: { source: HEADER, name: X-Correlation-Id } }
    topics:
      response-topic: { name: pakt.response.ift, correlation: { source: HEADER, name: X-Correlation-Id } }
    datasources:
      main-db: { url-ref: MAIN_DB_URL, user-ref: MAIN_DB_USER, password-ref: MAIN_DB_PASSWORD, allowed-schemas: [test_data], write-allowed: true }
    grpc-targets:
      billing-grpc: { target-ref: BILLING_GRPC_TARGET, correlation: { source: METADATA, name: x-correlation-id } }
    kafka-cluster: { bootstrap-servers-ref: KAFKA_BOOTSTRAP, security-protocol-ref: KAFKA_SECURITY_PROTOCOL }
```

Значения — **только ref-имена** (env-переменные), никаких URL/секретов. Ключи kebab-case (канон); лениво
принимаем и camelCase.

## Поведение (discovery / missing / errors)

- Источник: system property `stand.test.environments.config` (путь в ФС) → если задан, файл **обязан**
  существовать (иначе `StandTestException`); иначе classpath-ресурс `stand-test-environments.yml`/`.yaml`.
- **Нет дефолтного файла → пустой реестр** (не фатально: сохраняет сегодняшнее поведение; strict-валидатор
  всё равно отвергнет env — ожидаемо, документируется).
- **Malformed / невалидный ref / unknown-ключ → `StandTestException`** (config-класс).
- Загрузка **ленивая** (на первом `environment(name)`), чтобы `ServiceLoader.load(...)` не падал при
  discovery (иначе `ServiceConfigurationError` сломает `findFirst()` в junit).

## Безопасность

Хранятся только ссылки; резолв — существующими резолверами адаптеров (`System::getenv`) в рантайме. Loader
секреты не читает. `SafeConstructor` + лимиты алиасов/глубины (anti-YAML-bomb).

## Тесты (broker-free, ≥80% JaCoCo)

- `EnvironmentConfigTest` — полный YAML → корректные definition'ы/refs; unknown-ключ → `StandTestException`;
  blank ref → `StandTestException` с путём; парсинг `CorrelationSource` (в т.ч. регистр).
- `YamlEnvironmentConfigLoaderTest` — ресурс есть/нет; явный путь через system property (set/reset);
  отсутствующий явный путь → ошибка.
- `FileEnvironmentRegistryTest` — SPI-discovery (`ServiceLoader`), ленивость, делегирование.

## Gradle / доки

- `settings.gradle.kts` — добавить `stand-test-config`; `stand-test-bom` — добавить constraint.
- `stand-test-config/build.gradle.kts` — `api(project(":stand-test-core"))` + `implementation(libs.snakeyaml)`
  + стандартные test-деп-ы (как у scenario-yaml). Без адаптерных зависимостей.
- README модуля + правка `stand-test-junit` README и `CLAUDE.md` (закрыть «known gap»: положи
  `stand-test-config` в `testImplementation` + `stand-test-environments.yml` → реестр наполнен).

## Риск: дублирование маппинга со Spring-starter

Starter маппит из Spring-POJO, новый модуль — из Map-дерева; **два маппера → drift-риск** (та же
категория, что «wire-keys mirror»). Митигирование: единый документированный schema-контракт; возможная
будущая унификация (starter делегирует в `EnvironmentConfig`), но **не рефакторить рабочий Spring-код
сейчас** (ограничение «no large refactor»). Зафиксировано как известный tradeoff.

## Верификация

`./gradlew :stand-test-config:build` (checkstyle zero-tolerance + JaCoCo) + `./gradlew build` (граф
ацикличен). Ручная проверка: сценарий из `stand-test-example` с реестром из файла (в offline-тесте ref'ы
указывают на локальный double).

## Открытые решения (задефолчены, можно поменять)

- Формат = **YAML** (альтернатива — `.properties`, без новых зависимостей; SnakeYAML и так есть в репо).
- Missing-файл = **пустой реестр** (альтернатива — fail-fast).
- Маппер = **отдельный** в `stand-test-config` (альтернатива — унифицировать со starter через core-маппер;
  требует правки рабочего кода).

## Объём

Один новый модуль (~6 классов + ресурсы + тесты), правки в `settings.gradle.kts`, `stand-test-bom`,
README/CLAUDE.md. core/starter/адаптеры не трогаем (кроме доков).
