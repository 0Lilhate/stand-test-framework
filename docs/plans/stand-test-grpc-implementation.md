# План реализации — stand-test-grpc

## Контекст

Модуль — **скелет** (`package-info.java`). Назначение (мастер-план §4): gRPC-вызовы и проверки —
typed step-модель (`GrpcStep`) + gRPC `StepExecutor`. **Входит:** unary-вызовы; metadata (в т.ч. outbound
`correlationId`); deadlines; protobuf-ассерты; capture полей. **Не входит:** собственный gRPC-стек; **генерация
контрактов сервиса**; streaming на старте. Внутренние зависимости: `stand-test-core`, `stand-test-await`.
Внешние: `grpc-java` (`io.grpc`), `protobuf-java`. Граф (§5): `grpc → core, await`; **адаптеры не зависят друг
от друга**. Не в MVP.

Адаптер обязан следовать образцу существующих (kafka/rest/db) и вставать в core `StepExecutor` SPI через
ServiceLoader — раннер диспетчеризует по `type()`, core не получает compile-ребра на адаптер.

## Ключевое решение (нужно зафиксировать до кода): как вызывать gRPC без сгенерированных стубов

Сценарии декларативны — скомпилированных клиентских стубов нет, а генерация контрактов **запрещена** (§4).
Варианты unary-инвокации:

- **A. Server reflection + `DynamicMessage` + JSON (рекомендуется).** На рантайме тянуть дескриптор сервиса
  через gRPC Server Reflection, строить запрос из JSON (`JsonFormat` → `DynamicMessage`), звать unary
  дженериком (`io.grpc.stub.ClientCalls.blockingUnaryCall` + `MethodDescriptor` c protobuf-marshaller),
  ответ `DynamicMessage` → JSON, и **переиспользовать JSONPath-ассерты/capture** как в rest/kafka. Плюс:
  единый declarative-контракт, ассерты в JSONPath (консистентно). Минус: деп `protobuf-java-util` + reflection
  должен быть включён на сервере; больше кода.
- **B. Consumer поставляет `FileDescriptorSet`** (proto-дескриптор как ресурс/алиас-конфиг) вместо reflection.
  Плюс: не зависит от reflection на сервере. Минус: конфиг тяжелее для AI/пользователя.
- **C. Raw bytes marshaller** — пользователь даёт уже сериализованный protobuf-payload (`request.fixture` =
  бинарь), ассерты по сырому ответу. Плюс: минимум депов. Минус: не JSON-friendly, слабые ассерты, плохо для AI.

**Рекомендация — A** (JSON+reflection+DynamicMessage): максимально консистентно с REST/Kafka (JSONPath) и
дружелюбно к декларативному/AI-формату. Фиксируем как решение; B — фолбэк, если reflection недоступен.

## Внешние зависимости (добавить в `gradle/libs.versions.toml`)

- `io.grpc:grpc-api`, `grpc-stub`, `grpc-protobuf` (+ `grpc-netty-shaded` для транспорта, test/runtime),
  `io.grpc:grpc-services` (reflection client), `com.google.protobuf:protobuf-java`,
  `com.google.protobuf:protobuf-java-util` (JsonFormat). Пин версий grpc-java/protobuf — новыми `[versions]`.
- **⚠️ §14 / Java 24:** grpc-java имеет min-JDK и, как весь репозиторий, страдает от отсутствия `--release`.
  Проверить совместимость выбранной версии grpc-java с toolchain; решение по `--release` — общий пред-вопрос.
- json-path (`libs.json.path`) — для ассертов/capture (как в kafka).

## Файлы (образец — kafka), `stand-test-grpc/src/main/java/ru/alfa/stand/test/grpc/`

| Файл | Роль (аналог) |
|------|----------------|
| `GrpcStepExecutor.java` | `StepExecutor` impl; `supports("grpc.")`, `prepare` (обычно no-op), `execute` (KafkaStepExecutor) |
| `GrpcStep.java` | typed builder → `GenericStep` (KafkaStep) |
| `GrpcStepParameters.java` | reader-хелперы; **wire-ключи брать/добавлять в core `StepParameterKeys`** (после hoist'а — единый источник), grpc-специфичные (`TARGET`,`METHOD`,`DEADLINE_MILLIS`,`REQUEST`/`REQUEST_RESOURCE`) добавить туда же |
| `GrpcOperation.java` | enum (UNARY) → `stepType()` = `grpc.unary` (KafkaOperation) |
| `GrpcAssertion.java`/`GrpcCapture.java` | records (Kafka*) |
| `ResponseAssertions.java` | JSONPath parse/verify/applyCaptures (kafka `MessageAssertions`) |
| `ReferenceResolver.java`/`EnvironmentReferenceResolver.java` | резолв `targetRef`→`host:port` (kafka-аналог, `System::getenv` по умолчанию) |
| `GrpcChannelFactory.java`/`DefaultGrpcChannelFactory.java` | seam создания `ManagedChannel`/unary-call (KafkaClientFactory) — тестируемость без реального сервера |
| `ResolvedGrpcTarget.java` | record после резолва (ResolvedKafkaCluster) |
| `package-info.java` | обновить (не скелет) |

ServiceLoader: `src/main/resources/META-INF/services/ru.alfa.stand.test.core.execution.StepExecutor` →
`ru.alfa.stand.test.grpc.GrpcStepExecutor`.

## Контракты (из SPI)

- `boolean supports(String stepType)` → `stepType != null && stepType.startsWith("grpc.")`.
- `StepResult execute(ScenarioStep step, StepExecutionContext ctx)`:
  1. Резолв env: `ctx.environmentRegistry().environment(ctx.scenarioContext().environment())` →
     `.grpcTarget(alias)` (Optional, throw StandTestException если нет — как rest/kafka).
  2. Резолв `targetRef`→host:port через ReferenceResolver; создать/переиспользовать `ManagedChannel`
     (регистрировать в `ctx.resourceScope()` под ключом target-алиаса — закрытие раннером; как kafka consumer).
  3. Correlation: если `injectCorrelationId` — требовать `CorrelationConfig.source()==METADATA`, положить
     `correlation.name()`→`ctx.scenarioContext().correlationId().value()` в gRPC `Metadata` (аналог
     `RestStepExecutor.injectCorrelationId`, но METADATA вместо HEADER).
  4. Запрос: `request.fixture`/`request.json` → `${...}` резолв через `ctx.resolver()` → `DynamicMessage`.
  5. Unary с deadline (`timeout`→`CallOptions.withDeadlineAfter`); ответ→JSON→JSONPath assert/capture в
     `ctx.variableStore()`.
  - Семантика ошибок: ассерт-провал → `StandTestAssertionError`; инфра/конфиг → `StandTestException`.

## Фазы

1. **build.gradle.kts + catalog:** раскомментировать `api(core)` + `implementation(await)`, добавить grpc/protobuf
   деп-ы; пин версий; проверить сборку скелета с депами.
2. **Wire-ключи в core** `StepParameterKeys` (TARGET/METHOD/DEADLINE_MILLIS/REQUEST/REQUEST_RESOURCE) — единый
   источник (hoist уже сделан для rest/kafka/db).
3. **Модель:** `GrpcStep` builder + `GrpcStepParameters` + `GrpcOperation` + assertion/capture records.
4. **Инвокация (решение A):** channel factory seam, reflection→descriptor, JSON↔DynamicMessage, unary call.
5. **Executor:** `GrpcStepExecutor` (env-резолв, correlation-metadata, deadline, assert/capture) + ServiceLoader.
6. **Тесты:** in-process gRPC сервер (`io.grpc:grpc-inprocess`/`InProcessServer`) как offline-double (аналог
   kafka MockConsumer / rest HttpServer / db H2) — unary echo-сервис + reflection; broker-free по умолчанию.
7. **Интеграция:** пример в `stand-test-example` (tagged, если требует реального сервера — как kafka
   `requires-broker`, либо in-process double offline).

## Тестирование / верификация

- Юнит: executor против in-process gRPC double (успех, ассерт-провал→AssertionError, unknown target→
  StandTestException, correlation-metadata инжектится, deadline). Покрытие ≥80% (JaCoCo-гейт).
- `./gradlew :stand-test-grpc:build` (checkstyle zero-tolerance + JaCoCo) → зелёный.
- `./gradlew build` — граф ацикличен, adapter-only.
- Checkstyle: AssertJ, immutability, records, no banned imports — как во всём репо.

## Не входит / отложено

Streaming (client/server/bidi), сложные deadline/retry-политики, генерация стубов, собственный транспорт.
После готовности — снять fail-closed на `grpc.unary` в `AiStepNormalizer` и добавить grpc в ai-schema parity
(см. [stand-test-ai-schema-followups.md](stand-test-ai-schema-followups.md) F8).

## Риски

- **Reflection недоступен на целевом сервере** → нужен фолбэк B (FileDescriptorSet из конфига). Заложить seam.
- **§14 `--release`/Java 24** + вес grpc-netty — согласовать версии; решение по `--release` вне модуля.
- **DynamicMessage/JsonFormat edge-cases** (well-known types, oneof, enum) — покрыть тестами инкрементально.
- Депы grpc/protobuf крупные — держать транспорт (`grpc-netty-shaded`) в `runtimeOnly`/`testImplementation`, а
  не в `api`, чтобы не раздувать compile-граф потребителя.
