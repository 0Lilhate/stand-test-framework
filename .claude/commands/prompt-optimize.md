---
description: Analyze a draft prompt and output an optimized version that explicitly chains the right agents, skills, and commands of this project. Does NOT execute the task — advisory output only.
---

# /prompt-optimize

Take the user's draft prompt below and produce an optimized version that wires it to this project's agents/skills/commands. **Output only analysis + optimized prompt — do not execute the underlying task.**

## Pipeline (run all 6 phases in order)

### Phase 0 — Project signal

Quickly read available signals:
- `.claude/agents/` — list of subagents (java-reviewer, kotlin-reviewer, planner, architect, etc.)
- `.claude/rules/{common,java,kotlin}/` — coding standards, security, testing rules
- Any `pom.xml` / `build.gradle*` near the affected files to detect Maven vs Gradle, Java version, Spring Boot version
- For SQL touches: presence of `ita-objects-sql/` SQL DDL files

State the detected stack in one line.

### Phase 1 — Intent classification

Classify the user's request into ONE primary intent:

| Intent | Signal words |
|---|---|
| **new-feature** | "add", "implement", "build", "create" |
| **bug-fix** | "fix", "broken", "doesn't work", error trace |
| **refactor** | "refactor", "clean up", "reorganise", "extract" |
| **review** | "review", "audit", "check", "looks ok?" |
| **testing** | "write tests", "TDD", "coverage", "Kotest", "JUnit" |
| **build-fix** | "build fails", compiler error, "Unresolved reference", "cannot find symbol" |
| **docs** | "document", "explain", "README", "codemap" |
| **research** | "how does X work", "compare", "investigate" |
| **infra** | "Dockerfile", "deploy", "k8s", "CI", "gitlab-ci" |
| **design** | "architecture", "should we", "trade-offs" |

If multiple intents, pick the dominant one and note secondary intents.

### Phase 2 — Scope assessment

Evaluate complexity:

| Level | Signal | Recommended model |
|---|---|---|
| **TRIVIAL** | one-line / single-file edit | haiku |
| **LOW** | a few files, well-defined | haiku/sonnet |
| **MEDIUM** | one feature, multi-file, requires planning | sonnet |
| **HIGH** | cross-module, design decisions, ambiguous | opus or split |
| **EPIC** | architectural change, multiple subprojects | **must split** into multiple prompts |

If HIGH/EPIC, recommend splitting and produce a phase-1 prompt only.

### Phase 3 — Component matching

Map the intent to specific local components:

**Agents (`.claude/agents/`):**
- `planner` — start of new-feature / refactor / EPIC
- `architect` — design / cross-module / system-wide questions
- `java-reviewer` / `kotlin-reviewer` — review-intent or post-implementation gate
- `java-build-resolver` / `kotlin-build-resolver` — build-fix intent
- `database-reviewer` — anything touching SQL DDL or JPA entities/queries
- `docs-lookup` — research-intent on a specific library/framework
- `doc-updater` — docs intent (codemaps, READMEs)
- `bash-expert` — shell scripts, CI yml
- `harness-optimizer` — `.claude/` config tuning

**Skills (`.claude/skills/`):**
- `springboot-tdd` — Java TDD with JUnit 5 / Mockito / MockMvc / Testcontainers
- `springboot-patterns` — REST API design, layered services, async, caching
- `springboot-security` — Spring Security authn/z, validation, CSRF, secrets
- `springboot-verification` — full verify loop (build + static + tests + security scan)
- `jpa-patterns` — entity design, queries, transactions, indexing, pagination
- `java-coding-standards` — naming, immutability, Optional, streams, exceptions
- `java` — null traps, equality bugs, concurrency pitfalls
- `tdd-workflow` — generic TDD with 80%+ coverage (Java + Kotlin)
- `logging-patterns` — SLF4J, structured JSON logging, MDC
- `design-patterns` — Factory/Builder/Strategy/Observer with Java examples
- `code-quality` — clean code review for Java
- `git-workflow` — branching, commits, conflict resolution
- `documentation-lookup` — Context7-backed docs

**Commands (`.claude/commands/`):**
- `/plan` — restate + risks + step plan, waits for confirm
- `/orchestrate <feature|bugfix|refactor|security>` — multi-agent Teams workflow
- `/code-review` — security + quality review of uncommitted changes
- `/gradle-build`, `/kotlin-build` — fix build errors
- `/kotlin-test` — Kotest TDD enforcement
- `/kotlin-review` — Kotlin-specific review
- `/quality-gate` — format/lint/typecheck on demand
- `/docs` — Context7 library docs lookup
- `/model-route` — model tier recommendation
- `/checkpoint` — workflow checkpoint
- `/context-budget` — analyse `.claude/` context overhead

### Phase 4 — Missing context detection

Check for gaps. Critical items to verify the user provided:

- For **bug-fix**: error message / stack trace, file path
- For **new-feature**: which subproject (`ita-objects` / `-async` / `-classes` / `-rest` / `-sql`), API contract, persistence target
- For **refactor**: scope boundary, behavioural-equivalence requirement
- For **review**: what specifically to review (security? performance? all?)
- For **testing**: which framework (Kotest / JUnit 5), coverage target
- For **build-fix**: full error output, build tool (Maven vs Gradle)
- For **docs**: target audience, target file path

**If 3+ critical items missing — STOP, ask the user to clarify before generating the optimized prompt.**

### Phase 5 — Output

Emit two versions:

#### Full Version (detailed)

```
## Diagnosis
- Stack: <Java NN / Spring Boot X.Y / Kotlin? / build tool>
- Intent: <intent> (+ secondary if any)
- Scope: <TRIVIAL/LOW/MEDIUM/HIGH/EPIC>
- Recommended model: <haiku/sonnet/opus>
- Recommended agents: <list>
- Recommended skills: <list>
- Recommended commands: <list>
- Missing context: <list, if any>

## Optimized Prompt
<copy-paste-ready prompt that explicitly invokes the right components>

## Suggested workflow
1. <step>
2. <step>
3. <step>
```

#### Quick Version (compact)

One paragraph + the optimized prompt only. Useful when intent and scope are unambiguous.

## Output Requirements

- Respond in the same language as the user's input.
- The optimized prompt must be complete and ready to copy-paste into a new session.
- Never wrap the optimized prompt in advice — make it self-contained.
- End with a one-line footer:
  > Adjust above and run as a new request, or ask `/prompt-optimize` to refine.

## CRITICAL — DO NOT EXECUTE

Output **only** the analysis and optimized prompt. Do not run the user's task.
If the user explicitly asks for direct execution, refuse and tell them to issue a normal request without `/prompt-optimize`.

## Examples of optimization

**Before (vague):**
> add caching

**After (full version):**
```
## Diagnosis
- Stack: Java 23, Spring Boot, Maven (detected from ita-objects/pom.xml)
- Intent: new-feature
- Scope: MEDIUM
- Recommended model: sonnet
- Recommended agents: planner → java-reviewer
- Recommended skills: springboot-patterns, jpa-patterns
- Missing context: which entities/endpoints need caching, eviction policy, cache backend (Caffeine / Redis / Ehcache)

## Optimized Prompt
Add caching to the ObjectsBOClass.findByName() method in ita-objects.

Constraints:
- Use Spring Cache abstraction (@Cacheable on the service-layer method)
- Backend: Caffeine (already on classpath via spring-boot-starter-cache)
- Eviction: TTL = 10 minutes, max 1000 entries
- Cache name: "objects-by-name"
- Add @CacheEvict("objects-by-name") to any update method on the same entity

Workflow:
1. Run /plan to break down by phase before touching code
2. Implement: add @EnableCaching config, annotate service method, register CaffeineCacheManager bean
3. Add unit test asserting second call hits cache (verify repository invocation count = 1)
4. Run java-reviewer on the diff before merge

Skills to consult: `springboot-patterns` (caching section), `jpa-patterns` (transactions)
```

**Quick version:**
> Java 23 / Spring Boot, MEDIUM scope, sonnet. Use planner → java-reviewer with skills `springboot-patterns` + `jpa-patterns`. Optimized prompt below.

---

## User Input

$ARGUMENTS
