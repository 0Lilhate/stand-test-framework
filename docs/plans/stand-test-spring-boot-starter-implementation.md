# План реализации — stand-test-spring-boot-starter

## Контекст

Модуль — **скелет** (`package-info.java`). Назначение (мастер-план §4): удобная auto-configuration для
Spring-Boot тестовых проектов — `@Autowired StandClient` без ручной сборки. **Важно: core SDK не зависит от
Spring; связь строго в одну сторону.** Плагин `org.springframework.boot` **не** применяется (это библиотека,
не bootable-приложение). `StandClient` уже доступен без Spring через `StandTestExtension` — стартер это
post-MVP удобство. Внутренние зависимости (§5): `core, junit, allure, await, rest, kafka, db, grpc` —
**никогда наоборот**. Внешние: `spring-boot-autoconfigure`, `spring-boot`. Не в MVP.

Стартер повторяет сборку, которую сегодня делает `StandTestExtension.buildStandClient()`:
```
ServiceLoader StepExecutor[] + ReportingEventPublisher + EnvironmentRegistry
  → new DefaultScenarioRunner(executors, new DefaultScenarioValidator(), registry, publisher)
  → new DefaultStandClient(runner)
```
но через Spring-бины (условная регистрация адаптеров + биндинг окружений из конфигурации вместо ServiceLoader).

## Ключевые решения

1. **Discovery адаптеров — бины, не ServiceLoader.** Регистрировать `RestStepExecutor`/`DbStepExecutor`/
   `KafkaStepExecutor`/`GrpcStepExecutor` как бины через `@ConditionalOnClass` (адаптер на classpath →
   бин есть). Все имеют публичный no-arg ctor. `StandClient`-бин собирает `List<StepExecutor>` (autowire всех).
2. **EnvironmentRegistry из `@ConfigurationProperties`.** Байндить `stand.test.environments` → строить
   `InMemoryEnvironmentRegistry`. Дефолт (нет конфигурации) — пустой registry, как в junit. **NB:** после
   рантайм-энфорсмента (§Решение 6, strict) пустой registry отвергает любой env — стартер должен
   логировать/документировать, что окружения обязательны, либо давать явный дефолт.
3. **Refs остаются env-var-именами.** `baseUrlRef`/`urlRef`/`userRef`/`passwordRef`/`bootstrapServersRef`/
   `targetRef` — имена, резолвятся адаптерами через их `EnvironmentReferenceResolver` (`System::getenv`) на
   исполнении. Вариант (опц.): предоставить Spring-`Environment`-backed резолверы-бины, чтобы refs брались из
   Spring-конфига/`application.yml`, а не только из ОС. Рекомендация — MVP оставить дефолтные (System.getenv),
   Spring-резолверы как расширение.
4. **ReportingEventPublisher.** Если `stand-test-allure` на classpath — бин `AllureReportingEventPublisher`
   (`@ConditionalOnClass`), иначе `NoOpReportingEventPublisher`. Пользователь может переопределить своим бином
   (`@ConditionalOnMissingBean`).
5. **Плагин Spring Boot не применять** — только `implementation`/`api` на `spring-boot-autoconfigure` +
   `spring-boot`. Root `subprojects{}` (java-library/checkstyle/jacoco) применяется как ко всем.
6. **⚠️ ОБНОВЛЕНО (реализация): classpath-policy `compileOnly`, а не all-`api`.** Форсируется только
   `stand-test-core`; `await/allure/rest/kafka/db` подключены как `compileOnly` (optional) — типы нужны
   для `@ConditionalOnClass`-бинов, но транзитивно потребителю не отдаются. Потребитель добавляет только
   нужные адаптеры сам; тяжёлые транзитивы (spring-webflux, kafka-clients, allure-java-commons) не
   навязываются. `stand-test-junit` из деп-графа стартера убран (авто-конфиг его типы не использует — он
   собирает `StandClient` через Spring, а не через extension). Те же модули — на `testImplementation`,
   чтобы тесты стартера покрывали full-classpath проводку; «adapter absent» пути — через
   `FilteredClassLoader`. Это заменяет исходное «Раскомментировать все `api(...)`» ниже.

## Внешние зависимости (catalog)

- ~~Раскомментировать в `build.gradle.kts` все `api(project(...))`~~ → **см. Решение 6:** `api` только
  на `core`; `await/allure/rest/kafka/db` — `compileOnly` (optional); `junit`/`grpc` не подключаются.
- Добавить `spring-boot-autoconfigure` + `spring-boot` (версии — зарезервированы: `springBoot = "3.5.14"`);
  опц. `spring-boot-configuration-processor` (`annotationProcessor`, для metadata автодополнения свойств).
- **⚠️ §14 / Java 24:** Spring Boot 3.5 требует Java 17+; на toolchain 24 соберётся, но без `--release` байткод
  Java 24 не загрузится потребителями на 17/21 — согласовать общий `--release`-вопрос (вне модуля).

## Файлы, `stand-test-spring-boot-starter/src/main/java/ru/alfa/stand/test/starter/`

| Файл | Роль |
|------|------|
| `StandTestAutoConfiguration.java` | `@AutoConfiguration`; бины executors (`@ConditionalOnClass`), validator, publisher, `EnvironmentRegistry`, `ScenarioRunner`, `StandClient`; `@ConditionalOnMissingBean` для переопределяемости |
| `StandTestProperties.java` | `@ConfigurationProperties("stand.test")`; nested-типы под окружения/сервисы/датасорсы/топики/grpc/correlation |
| `EnvironmentRegistryFactory.java` | `StandTestProperties` → `List<EnvironmentDefinition>` → `InMemoryEnvironmentRegistry` |
| `package-info.java` | обновить |

Ресурсы:
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` —
  строка `ru.alfa.stand.test.starter.StandTestAutoConfiguration` (Boot 3 регистрация).
- (опц.) `additional-spring-configuration-metadata.json` для свойств.

## Модель свойств (маппинг на core-records)

`stand.test.environments.<env>.{services,datasources,topics,grpcTargets,kafkaCluster}` → соответствующие
`Map<alias, *Definition>`. Records core (`ServiceEndpointDefinition(name, baseUrlRef, correlation)`,
`DatasourceDefinition(alias, urlRef, userRef, passwordRef, allowedSchemas, writeAllowed)`,
`TopicDefinition(alias, name, correlation)`, `GrpcTargetDefinition(alias, targetRef, correlation)`,
`KafkaClusterDefinition(bootstrapServersRef, securityProtocolRef, saslJaasConfigRef)`,
`CorrelationConfig(source, name)`) — records не байндятся Spring'ом напрямую с дефолт-ctor, поэтому
`StandTestProperties` держит **мутабельные POJO/nested-классы**, а `EnvironmentRegistryFactory` собирает из них
immutable core-records. Пример YAML:
```yaml
stand:
  test:
    environments:
      ift:
        services:
          client-service: { base-url-ref: CLIENT_SERVICE_URL, correlation: { source: HEADER, name: X-Correlation-Id } }
        datasources:
          main-db: { url-ref: MAIN_DB_URL, user-ref: MAIN_DB_USER, password-ref: MAIN_DB_PASSWORD, allowed-schemas: [test_data], write-allowed: true }
```

## Фазы

1. **build.gradle.kts + catalog:** внутренние `api(...)` + spring-boot деп-ы; сборка скелета.
2. **Properties:** `StandTestProperties` (мутабельные nested-POJO) + relaxed-binding имён (kebab-case).
3. **Factory:** POJO→core-records→`InMemoryEnvironmentRegistry`; валидация (пустые refs → внятная ошибка).
4. **AutoConfiguration:** бины (executors conditional-on-class, validator, publisher conditional, registry,
   runner, client); `@ConditionalOnMissingBean` везде; `AutoConfiguration.imports`.
5. **Тесты:** `ApplicationContextRunner` (spring-boot-test) — контекст поднимается, `StandClient`-бин есть;
   с REST/DB на classpath регистрируются executors; свойства байндятся в registry; переопределение бина
   пользователем работает. Offline (без реального стенда).

## Тестирование / верификация

- `ApplicationContextRunner`-тесты (`spring-boot-test` в `testImplementation`): наличие/тип бинов, биндинг
  properties→registry, conditional-on-class (симулировать отсутствие адаптера — сложнее; можно проверить
  наличие при полном classpath), пользовательское переопределение.
- Опц. e2e: поднять контекст + прогнать declarative-сценарий против in-process double (переиспользовать
  паттерн `stand-test-example`).
- `./gradlew :stand-test-spring-boot-starter:build` (checkstyle + JaCoCo ≥80%) → зелёный.
- `./gradlew build` — граф в одну сторону (стартер — sink над рантайм-модулями; на него никто не зависит).

## Не входит / отложено

Реализация адаптеров; обратная зависимость core/адаптеров на стартер; применение spring-boot-плагина;
production-профили. Весь модуль — post-MVP (после стабилизации core+адаптеров, что уже наступило).

## Риски

- **§14 `--release`/Java 24 vs Spring Boot 3.5** — согласовать байткод-таргет; иначе стартер бесполезен
  потребителям на LTS-JDK.
- **Records ≠ прямой Spring-binding** → промежуточные мутабельные POJO обязательны (заложено).
- **Strict-энфорсмент (§Решение 6): пустой registry отвергает** — стартер обязан внятно требовать конфигурацию
  окружений (док + fail-fast с понятным сообщением), иначе `@Autowired StandClient` упадёт на первом run.
- **grpc-зависимость** — `api(project(":stand-test-grpc"))` появится после реализации grpc-адаптера; до тех пор
  либо не включать grpc-executor-бин, либо `@ConditionalOnClass(GrpcStepExecutor.class)` (мягко).
