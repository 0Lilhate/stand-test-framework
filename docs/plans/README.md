# docs/plans — планы доработки

Детальные планы того, что осталось построить. Каждый файл самодостаточен: контекст, скоуп,
зависимости, файлы, ключевые решения, фазы, тесты, верификация, риски. Источник истины по контрактам —
`docs/arch/stand-test-sdk-implementation-plan.md`; текущее состояние guardrail-слоёв —
`docs/arch/stand-test-ai-schema-remediation-plan.md`.

Две части, независимые друг от друга: **модули SDK** и **кит для AI-агента**.

| План | Область | Статус | Кратко |
|------|---------|--------|--------|
| [ai-agent-kit-implementation.md](ai-agent-kit-implementation.md) | кит `docs/ai-agent/` | этапы плана закрыты; в списке «дальше» два пункта, и оба ждут данных | периметр записи, слой энфорсмента, субагенты в обеих копиях бандла и холодный старт KB сделаны; цикл обучения сигнатурам ждёт накопленного журнала прогонов, субагент `failure-analyst` — его. Первое по отдаче лежит вне списка: прогнать batch по датасету у потребителя и получить базовый замер |
| [stand-test-ai-schema-followups.md](stand-test-ai-schema-followups.md) | `stand-test-ai-schema` | реализован (MVP+remediation) | закрыть schema↔runtime расхождения; часть зависит от grpc |
| [stand-test-grpc-implementation.md](stand-test-grpc-implementation.md) | `stand-test-grpc` | реализован (MVP: `grpc.unary` через server reflection + `DynamicMessage`) | новый gRPC-адаптер по образцу kafka; unary + metadata + deadlines |
| [stand-test-spring-boot-starter-implementation.md](stand-test-spring-boot-starter-implementation.md) | `stand-test-spring-boot-starter` | реализован (Boot-3 auto-configuration, адаптеры — `compileOnly`-опционалы, включая gRPC) | auto-configuration: `@Autowired StandClient` + биндинг окружений |
| [stand-test-config-implementation.md](stand-test-config-implementation.md) | `stand-test-config` | реализован (SPI-провайдер `FileEnvironmentRegistry`) | файловый загрузчик `stand-test-environments.yml`; закрывает known-gap пустого реестра |
| [stand-test-grpc-integration.md](stand-test-grpc-integration.md) | `stand-test-grpc` | реализован | интеграция адаптера: регистрация executor'а, реестр gRPC-таргетов |
| [alfalab-configurer-adoption.md](alfalab-configurer-adoption.md) | сборка (Gradle) | черновик, ждёт решений D1–D9 | переход на плагин-конфигурер `ru.alfalab.*` по образцу `card-info-service`: для библиотек это `library-configurer`, а не `microservice-configurer`; 17 конфликтов с текущей сборкой (Gradle 9.6.1, Spring BOM в `api`, владелец checkstyle-конфига, публикация) |

**Порядок (только модули SDK).** ai-schema-followups (часть) → grpc → затем финальные пункты ai-schema
(`grpc.unary` execution) и starter (регистрация `GrpcStepExecutor`). starter может делаться параллельно
grpc (условная регистрация бинов). План по киту от этого порядка не зависит и идёт параллельно: он
касается `docs/ai-agent/` и тестов `stand-test-ai-schema`, а не самих адаптеров.

**Сквозной риск (§14 мастер-плана) — РЕШЁН.** Сборка компилируется с `--release 17` (catalog
`javaRelease`, применяется в root `subprojects` `JavaCompile`): байткод таргетирует Java 17, артефакты
загружаются на JDK 17/21/24. Ранее описанный риск (toolchain `java=24` без `--release` → байткод Java 24)
больше не актуален.
