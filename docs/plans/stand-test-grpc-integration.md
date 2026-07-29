# gRPC-интеграция (F8): scenario-yaml + ai-schema parity + example

## Context

Модуль `stand-test-grpc` реализован, но декларативные сценарии его не вызывают: `AiStepNormalizer`
(`scenario-yaml`) кидает fail-closed на `grpc.unary` («adapter not implemented»). Цель — замкнуть путь
**AI-документ → AiScenarioParser → runner → GrpcStepExecutor** и добавить grpc в parity-набор
(F8 из `docs/plans/stand-test-ai-schema-followups.md`). Контракты готовы: core `StepParameterKeys` уже
содержит grpc-ключи (`TARGET/METHOD_FULL_NAME/DEADLINE_MILLIS/REQUEST/REQUEST_RESOURCE`); парсер
двухслойный (нормалайзер AI-эргономики → per-family translator → wire `GenericStep`); `scenario-yaml`
core-only (без compile-рёбер на адаптеры — grpc-ключи берутся из core).

**Исполнимое подмножество grpc** (рантайм): `request.fixture`, `equals`-ассерты, `capture`,
`correlation.inject` (METADATA), `timeout`→deadline. **Остаётся fail-closed**: `request.json` (inline),
`expect.status`, не-`equals` матчеры — консистентно со «schema ⊋ executable».

Особенности из анализа: в ai-schema у grpc `assert` **вложен в `expect`** (не top-level, как у kafka);
end-to-end прогон нельзя на in-process канале (`DefaultGrpcChannelFactory` умеет только
`forTarget(host:port)`, а package-private ctor executor'а из `example` недоступен) → исполняемый пример
использует реальный gRPC-сервер на loopback фикс-порту + env-ref (как REST-double на 18080).

## Часть 1 — scenario-yaml: снять fail-closed + маппинг (обязательно)
Файлы: `stand-test-scenario-yaml/src/main/java/ru/alfa/stand/test/scenario/`
- `AiStepNormalizer.java`: удалить исключение-ветку `grpc.unary`; добавить `grpcUnary(fields, loc)` —
  `GRPC_KNOWN={id,type,description,target,method,correlation,request,timeout,expect,capture}`;
  copy target/method/timeout/capture; `applyCorrelation(...,"inject",INJECT_CORRELATION_ID)` (reuse);
  обобщить `applyPayload` доп. параметром `resourceKey` (rest/kafka → `BODY_RESOURCE`, grpc →
  `REQUEST_RESOURCE`; `request.json` fail-closed); новый `applyGrpcExpect` — `expect.assert` (вложенный)
  → equals-map (не-equals fail-closed), `expect.status` fail-closed.
- `GrpcStepTranslator.java` (**новый**, зеркалит `KafkaStepTranslator`/`RestStepTranslator`): surface→wire —
  `TARGET`←requireString "target"; `METHOD_FULL_NAME`←"method"; `DEADLINE_MILLIS`←`durationMillis("timeout")`;
  `INJECT_CORRELATION_ID`←boolFlag; `putInlineOrResource(...,"request","requestResource",REQUEST,REQUEST_RESOURCE,false)`;
  `ASSERTIONS`←`SurfaceValues.assertions`; `CAPTURES`←`SurfaceValues.captures(...,JSON_PATH)`.
- `AiScenarioParser.java`: в `translateParams` добавить `if ("grpc.unary".equals(type)) return GrpcStepTranslator.params(...)`.
- `YamlStepKeys.java`: добавить делегаты `TARGET/METHOD_FULL_NAME/DEADLINE_MILLIS/REQUEST/REQUEST_RESOURCE` → core.
- Тест `AiScenarioParserTest.java`: заменить «grpc rejection» на маппинг-кейсы (полный grpc→wire-ключи;
  `request.json` / `expect.status` / не-equals / unknown-поле → `StandTestException`).
- Вне scope: YAML `given/then`-поверхность grpc (отдельный DSL) — follow-up.

## Часть 2 — ai-schema: доки (обязательно)
- `stand-test-ai-schema/src/main/resources/ai/stand-test-ai-generation-rules.md`, раздел «Schema vs runtime»:
  зафиксировать, что `grpc.unary` теперь исполним в подмножестве (fixture/equals/capture/correlation/deadline),
  а `request.json`/`expect.status`/не-equals — нет.

## Часть 3 — example: parity (обязательно, без новых зависимостей)
Файлы: `stand-test-example/src/test/...`
- Новый ресурс `resources/ai/grpc-flow.json` (исполнимое подмножество: target/method/request.fixture/
  timeout/correlation.inject/expect.assert-equals/capture).
- `AiSchemaParityTest.java`: +2 метода — документ проходит JSON Schema (networknt, уже есть) **и** парсится
  `AiScenarioParser` в validator-clean `Scenario` с ожидаемыми grpc wire-ключами. Зависимостей на grpc не требует.

## Часть 4 — example: исполняемый пример (рекомендуется)
- `ExampleGrpcServer` (аналог `ExampleHttpServer`): реальный gRPC-сервер `ServerBuilder.forPort(<fixed>)` +
  `HealthStatusManager().getHealthService()` + `ProtoReflectionServiceV1.newInstance()`; `AutoCloseable`.
- `ExampleStand`: `grpcRegistry()` (grpcTarget `health-grpc`, targetRef=`GRPC_TARGET` env, METADATA-correlation)
  + `grpcStand()` (`List.of(new GrpcStepExecutor())` — no-arg → env-resolver `System::getenv`).
- `GrpcExampleTest`: `GrpcStep.unary("health-grpc").method("grpc.health.v1.Health/Check")
  .requestFromResource("fixtures/health-check.json").withinSeconds(5).assertPath("$.status","SERVING")
  .capture(...).build()` → `stand.run` зелёный. Fixture `resources/fixtures/health-check.json` = `{"service":""}`.
- `build.gradle.kts`: `testImplementation(project(":stand-test-grpc"))` + `grpc-services` + `grpc-netty-shaded`
  (+ protobuf при необходимости); env `GRPC_TARGET=127.0.0.1:$exampleGrpcPort` (фикс-порт `-PexampleGrpcPort`,
  дефолт 18090; как REST 18080).

## Verification
- `./gradlew :stand-test-scenario-yaml:test :stand-test-ai-schema:test :stand-test-example:test` — зелёные.
- `./gradlew build` — весь граф ацикличен, adapter-only-границы целы, coverage-гейты ≥80%.
- Функционально: `AiScenarioParser` на grpc-документе даёт `GenericStep` с grpc wire-ключами; parity-тест
  проходит; (Часть 4) реальный grpc.unary через runner к loopback-серверу возвращает SERVING.

## Риски
- fail-closed на `request.json`/`expect.status`/не-equals — намеренно.
- Часть 4: реальный Netty-сервер на фикс-порту (collision → `-P`-override), +grpc test-deps в example
  (только test-scope); Parity (Часть 3) даёт основную ценность F8 без этого.
- YAML `given/then` grpc — вне scope.

## Объём
scenario-yaml: 1 новый класс + правки 3 файлов + тест. ai-schema: 1 док. example: parity (ресурс +
тест-методы) + Часть 4 (2-3 класса + build + fixture). core/адаптеры/grpc-модуль не трогаем. План также
сохранить в `docs/plans/stand-test-grpc-integration.md` при реализации.
