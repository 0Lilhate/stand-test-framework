# Agent & Skill Reference for stand-test-framework

Этот файл — справочник по агентам, скиллам и командам харнесса `.opencode/`. Используй его, когда нужно решить, какой агент/скилл/команду задействовать.

**Инструкции по самому репозиторию живут в [`CLAUDE.md`](../CLAUDE.md)** — читай их до любой правки кода. Корневой [`AGENTS.md`](../AGENTS.md) — указатель на тот же файл, а не вторая копия.

`.opencode/` — зеркало `.claude/`: те же скиллы, команды, субагенты и правила, отличаются только формат frontmatter агентов (`mode`/`permission` вместо `tools`/`model`), пути `.opencode/` и агент `orchestrator`, которого в Claude Code заменяют Agent Teams.

---

## 1. Агенты (Sub-agents) — 12

Все агенты имеют **bash: deny, task: deny** — они только анализируют, не выполняют произвольные команды и не спавнят вложенных агентов (кроме `explore`).

> **Безопасность:** У всех subagent'ов есть `write: allow` и `edit: allow` (требуется для генерации отчётов/артефактов). Это осознанное решение — см. [common/security.md](rules/common/security.md#agent-permission-model-acknowledged-risk) для обоснования и мер компенсации.

### Analysis & Review

| Agent | Когда использовать | Что делает |
|---|---|---|
| **java-reviewer** | После любого изменения `.java` | Java/Spring Boot code review: layered architecture, JPA, security, concurrency. Блокирует CRITICAL/HIGH |
| **kotlin-reviewer** | После любого изменения `.kt` | Kotlin/Android/KMP review: idioms, coroutine safety, Compose, clean architecture |
| **database-reviewer** | После SQL/миграций/схем | PostgreSQL: query performance, индексы, RLS, антипаттерны |
| **architect** | Архитектурные решения, рефакторинг | System design, ADR, trade-off analysis, modularity, scalability |

### Build

| Agent | Когда использовать | Что делает |
|---|---|---|
| **java-build-resolver** | `./gradlew build` упал на Java | Фикс compilation errors, dependency conflicts, annotation processors, checkstyle |
| **kotlin-build-resolver** | `./gradlew build` упал на Kotlin | Фикс Kotlin compilation errors, detekt/ktlint violations, Gradle config |

### Planning & Docs

| Agent | Когда использовать | Что делает |
|---|---|---|
| **planner** | Сложные фичи, рефакторинг > 3 файлов | Пошаговый план с файлами, рисками, verification checklist |
| **doc-updater** | Обновление codemaps/docs | Генерирует `docs/CODEMAPS/*`, обновляет README и гайды |
| **docs-lookup** | Вопросы по API/библиотекам | Ищет документацию через Context7 MCP, а не в training data |

### Orchestration

| Agent | Когда использовать | Что делает |
|---|---|---|
| **orchestrator** | Задачи с распараллеливанием, цепочки агентов | Декомпозирует задачу, spawn'ит суб-агентов параллельно через `task`, собирает результаты. **Единственный** агент с `task: allow` |

### Infrastructure

| Agent | Когда использовать | Что делает |
|---|---|---|
| **bash-expert** | Bash-скрипты, CI/CD, ShellCheck | Strict mode, error trapping, аргументы, Bats tests |
| **harness-optimizer** | Тюнинг `.opencode/` конфига | Аудит контекста, hooks, evals, routing |

### Built-in Subagent Types

Встроенные типы OpenCode, отдельного файла агента не требуют.

| Тип | Когда использовать |
|---|---|
| **explore** | Быстрый поиск по коду, «как устроено», «где находится». Не пишет код |
| **general** | Мульти-шаговые исследовательские задачи, несколько unit of work параллельно |
| **scout** | Исследование внешних зависимостей, клонирование репозиториев в managed cache (read-only) |

---

## 2. Skills — 51

### stand-test SDK (16) — профильная линия этого репозитория

Пайплайн генерации автотеста: **case-analysis → kb-lookup → environment-mapping → scenario-design → authoring → safety-review → test-review**.

| Skill | Описание |
|---|---|
| **stand-test-case-analysis** | Извлечение структуры будущего автотеста из plain-text бизнес-кейса. Первый шаг пайплайна |
| **stand-test-kb-lookup** | Резолв кейса против knowledge base в детерминированный `KnowledgeBaseLookupResult` |
| **stand-test-environment-mapping** | Маппинг систем из кейса на logical aliases реестра окружений |
| **stand-test-scenario-design** | Технический дизайн сценария: шаги, captures, assertions, awaits, cleanup, выбор трека |
| **stand-test-java-dsl-authoring** | Генерация JUnit 5 теста на lazy Java DSL. **Трек по умолчанию** |
| **stand-test-yaml-authoring** | Генерация AI-format (steps/type) сценария под `stand-test-ai-schema` |
| **stand-test-fixture-authoring** | Безопасные classpath-фикстуры с `${testRunId}`/`${correlationId}`, без секретов и PII |
| **stand-test-env-generation** | Рендер конфигурации окружений в реальные форматы SDK (`/stand-test-generate-env`) |
| **stand-test-kb-update** | Обновление KB из OpenAPI/AsyncAPI/proto/SQL/markdown. Dry-run по умолчанию |
| **stand-test-spec-ingestion** | Приём неструктурированной спецификации (PDF/DOCX/ФС/ТЗ) в KB-**кандидаты** |
| **stand-test-spec-extraction** | Правила интерпретации при извлечении: provenance, confidence, `UNRESOLVED` вместо галлюцинации |
| **stand-test-kb-candidate-review** | Ревью staged-кандидатов одного документа под решение человека. Curated KB не пишет |
| **stand-test-kb-candidate-apply** | Промоушен одобренных кандидатов в curated KB через единственного детерминированного писателя |
| **stand-test-safety-review** | Adversarial guardrail review. **Обязательный гейт**: любой BLOCK останавливает пайплайн |
| **stand-test-test-review** | Quality review: покрытие кейса, корректность матчеров, неflaky awaits, correlation, cleanup |
| **stand-test-debugging** | Диагностика упавшего автотеста: `StandTestAssertionError` vs `StandTestException`, сигнатуры отказов |

### Java / Spring Boot (8)

| Skill | Описание |
|---|---|
| **springboot-patterns** | REST API, layered services, data access, caching, async, logging |
| **springboot-tdd** | JUnit 5 + Mockito + MockMvc + Testcontainers + JaCoCo |
| **springboot-security** | authn/authz, валидация, CSRF, секреты, заголовки, rate limiting |
| **springboot-verification** | Build + static analysis + tests + security scan + diff review перед PR |
| **spring-boot-engineer** | Роль senior Spring Boot 3.x engineer: Data JPA, Security 6, WebFlux, Cloud |
| **jpa-patterns** | Entity design, relationships, N+1, транзакции, аудит, пагинация, пулы |
| **java-coding-standards** | Naming, immutability, Optional, стримы, исключения, дженерики, layout |
| **java** | Ловушки языка: null, equality, конкурентность |

### Архитектура и дизайн (6)

| Skill | Описание |
|---|---|
| **architect-review** | Ревью архитектуры на уровне master software architect |
| **backend-architect** | Масштабируемые API, микросервисы, распределённые системы |
| **database-architect** | Проектирование слоя данных с нуля: выбор технологии, схема |
| **architecture-decision-records** | Захват ADR: context → decision → alternatives → consequences |
| **design-patterns** | Factory, Builder, Strategy, Observer, Decorator с примерами на Java |
| **c4-architecture-c4-architecture** | Генерация C4-документации bottom-up по существующему коду |

### Тестирование и качество (6)

| Skill | Описание |
|---|---|
| **tdd-workflow** | RED/GREEN/IMPROVE с покрытием 80%+ |
| **requesting-code-review** | Когда и как диспатчить code-review суб-агентов на границах задач |
| **code-quality** | Clean code, API-контракты, null safety, обработка исключений, производительность |
| **safety-guard** | Предотвращение деструктивных операций на проде и в автономных прогонах |
| **browser-qa** | Визуальное тестирование и проверка UI-взаимодействий через браузерную автоматизацию |
| **santa-method** | Adversarial verification: два независимых ревьюера должны сойтись |

### Git (2)

| Skill | Описание |
|---|---|
| **git-workflow** | Branching strategies, conventional commits, merge vs rebase, разрешение конфликтов |
| **git-essentials** | Справочник команд: ветки, merge, stash |

### Инфраструктура и данные (5)

| Skill | Описание |
|---|---|
| **database-migrations** | Schema changes, data migrations, откаты, zero-downtime |
| **postgres-patterns** | Оптимизация запросов, схема, индексы, безопасность |
| **k8s-security-policies** | NetworkPolicy, PodSecurityPolicy, RBAC |
| **mcp-server-patterns** | Сборка MCP-серверов: Node/TypeScript SDK, Zod, stdio vs Streamable HTTP |
| **logging-patterns** | SLF4J, структурные JSON-логи, MDC для трассировки запросов |

### Мета-харнесс (8)

| Skill | Описание |
|---|---|
| **context-budget** | Аудит потребления контекста агентами/скиллами/MCP/правилами |
| **skill-comply** | Проверка, следуют ли агенты скиллам, правилам и определениям на практике |
| **skill-creator** | Создание, доработка и бенчмаркинг скиллов |
| **skill-stocktake** | Аудит качества скиллов и команд: Quick Scan и Full Stocktake |
| **rules-distill** | Дистилляция cross-cutting принципов из скиллов в правила |
| **iterative-retrieval** | Паттерн прогрессивного уточнения контекста для суб-агентов |
| **repo-scan** | Cross-stack аудит исходников с HTML-отчётами |
| **documentation-lookup** | Актуальные доки библиотек через Context7 вместо training data |

---

## 3. Commands — 20

### Разработка и сборка

| Command | Что делает |
|---|---|
| `/gradle-build` | Фикс Gradle build errors |
| `/kotlin-build` | Фикс Kotlin/Gradle build errors через kotlin-build-resolver |
| `/kotlin-test` | Kotlin TDD: Kotest первым, затем реализация, покрытие 80%+ через Kover |
| `/kotlin-review` | Kotlin code review через kotlin-reviewer |
| `/quality-gate` | Format + lint + typecheck по файлу или проекту |
| `/eval` | Eval-driven development: define, check, report, list |

### Планирование и оркестрация

| Command | Что делает |
|---|---|
| `/plan` | План через planner agent. Ждёт CONFIRM перед кодом |
| `/orchestrate` | Делегирование задачи агенту `orchestrator`: план → параллельный spawn → синтез |
| `/model-route` | Рекомендация уровня модели (haiku/sonnet/opus) под задачу |
| `/prompt-optimize` | Анализ и оптимизация промпта. **Не выполняет задачу** |

### Ревью

| Command | Что делает |
|---|---|
| `/code-review` | Security + quality review незакоммиченных изменений. Блокирует CRITICAL/HIGH |
| `/checkpoint` | Create/verify/list workflow checkpoints |

### Документация и знания

| Command | Что делает |
|---|---|
| `/docs` | Поиск документации через Context7 MCP |
| `/update-codemaps` | Генерация `docs/CODEMAPS/*` |
| `/learn` | Извлечение паттернов из сессии в скиллы |
| `/learn-eval` | `/learn` + self-evaluation + выбор места сохранения (Global vs Project) |
| `/rules-distill` | Дистилляция скиллов в правила |

### Проектные

| Command | Что делает |
|---|---|
| `/next-ui-task` | Взять следующую задачу из бэклога UI-линии (`docs/ui-test-generation/planning`) и выполнить целиком |

### Мета

| Command | Что делает |
|---|---|
| `/context-budget` | Анализ потребления контекстного окна |
| `/aside` | Быстрый вопрос без потери контекста, с автоматическим возвратом к задаче |

---

## 4. MCP-серверы — 8

| Сервер | Тип | Назначение |
|---|---|---|
| **memory** | `server-memory` | Персистентное хранилище контекста |
| **sequential-thinking** | `server-sequential-thinking` | Chain-of-thought reasoning |
| **context7** | `@upstash/context7-mcp` | Актуальная документация библиотек |
| **playwright** | `@playwright/mcp` | Браузерная автоматизация (Chrome) |
| **postgres_prodcat** | Docker toolbox | БД prodcat (dev) |
| **postgres_prodprofile_u** | Docker toolbox | БД prodprofile_u (dev) |
| **postgres_designer** | Docker toolbox | БД designer (dev) |
| **jetbrains** | IntelliJ MCP | Интеграция с IDE (порт 64342) |

---

## 5. Rules Layer — 21 файл

```
rules/
├── README.md             — структура и приоритет
├── common/ (10)          — универсальные принципы (грузятся в контекст)
│   agents, code-review, coding-style, development-workflow,
│   git-workflow, hooks, patterns, performance, security, testing
├── java/ (5)             — Java/Spring Boot специфика (грузятся в контекст)
│   coding-style, hooks, patterns, security, testing
└── kotlin/ (5)           — Kotlin специфика (НЕ грузятся — проект Java-only)
    coding-style, hooks, patterns, security, testing
```

Приоритет: **kotlin/ > java/ > common/** (specific overrides general). Что именно грузится в контекст, задаёт `instructions` в [`opencode.json`](opencode.json).

`scripts/hooks/` — opt-in шаблоны хуков. Они написаны под протокол хуков Claude Code, поэтому перед подключением в `settings.local.json` их нужно адаптировать. Сейчас **ни один хук не подключён**.

---

## 6. Типовые цепочки

### Автотест из текстового кейса (профильный сценарий)
```
stand-test-case-analysis → stand-test-kb-lookup → stand-test-environment-mapping
  → stand-test-scenario-design → stand-test-java-dsl-authoring
  → stand-test-safety-review (гейт) → stand-test-test-review
```

### Новая фича в SDK
```
/plan → planner → implement → java-reviewer → /gradle-build → /quality-gate
```

### Разбор упавшего теста
```
stand-test-debugging → java-reviewer → /gradle-build
```

### Полный цикл на много файлов
```
/orchestrate refactor → orchestrator: planner + explore + java-reviewer + database-reviewer
  → синтез результатов
```

### Pre-PR
```
/code-review → /quality-gate → /checkpoint create
```
