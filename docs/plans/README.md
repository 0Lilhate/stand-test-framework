# docs/plans — планы доработки модулей

Детальные планы завершения трёх модулей `stand-test-sdk`. Каждый файл самодостаточен: контекст, скоуп,
зависимости, файлы, ключевые решения, фазы, тесты, верификация, риски. Источник истины по контрактам —
`docs/arch/stand-test-sdk-implementation-plan.md`; текущее состояние guardrail-слоёв —
`docs/arch/stand-test-ai-schema-remediation-plan.md`.

| План | Модуль | Статус модуля | Кратко |
|------|--------|---------------|--------|
| [stand-test-ai-schema-followups.md](stand-test-ai-schema-followups.md) | `stand-test-ai-schema` | реализован (MVP+remediation) | закрыть schema↔runtime расхождения; часть зависит от grpc |
| [stand-test-grpc-implementation.md](stand-test-grpc-implementation.md) | `stand-test-grpc` | скелет (`package-info`) | новый gRPC-адаптер по образцу kafka; unary + metadata + deadlines |
| [stand-test-spring-boot-starter-implementation.md](stand-test-spring-boot-starter-implementation.md) | `stand-test-spring-boot-starter` | скелет | auto-configuration: `@Autowired StandClient` + биндинг окружений |

**Порядок.** ai-schema-followups (часть) → grpc → затем финальные пункты ai-schema (`grpc.unary` execution) и
starter (регистрация `GrpcStepExecutor`). starter может делаться параллельно grpc (условная регистрация бинов).

**Сквозной риск (§14 мастер-плана).** toolchain `java=24` без `--release` → байткод Java 24; потребители на
JDK 17/21 не загрузят артефакт. Особенно критично для grpc (min JDK grpc-java) и starter (Spring Boot 3.5,
Java 17+). Каждый план фиксирует это как пред-решение; правку Gradle делать отдельным осознанным шагом.
