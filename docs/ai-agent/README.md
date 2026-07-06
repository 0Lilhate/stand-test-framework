# AI-Agent Authoring Kit for stand-test-sdk

This directory ships a **deployable `.claude/` bundle** that lets an AI agent (Claude Code)
convert a plain-text business test case into a correct, safe automated test built on
`stand-test-sdk`. Nothing here is runtime code — the SDK itself is never modified by these
assets.

## Final layout (what a consumer project gets)

```
docs/ai-agent/
  README.md          ← this guide (stays in the SDK repo; not part of the bundle)
  .claude/           ← THE BUNDLE — copy its contents into the consumer repo's .claude/
    skills/          ← 9 self-contained skills, each with its templates/checklists/examples
      stand-test-case-analysis/        SKILL.md + test-case-analysis-template.md + example-text-case.md
      stand-test-scenario-design/      SKILL.md + scenario-design-template.md + before-generating-checklist.md + example-scenario-design.md
      stand-test-yaml-authoring/       SKILL.md + yaml-scenario-template.yaml + example-generated.yaml
      stand-test-java-dsl-authoring/   SKILL.md + java-test-template.java + sdk-boundary-checklist.md + example-generated.java
      stand-test-fixture-authoring/    SKILL.md + fixture-template.json
      stand-test-environment-mapping/  SKILL.md
      stand-test-safety-review/        SKILL.md + safety-checklist.md + safety-review-template.md
      stand-test-test-review/          SKILL.md + review-checklist.md + flakiness-checklist.md
                                       + generated-test-review-template.md + before-committing-checklist.md + example-review.md
      stand-test-debugging/            SKILL.md + debugging-report-template.md
    commands/        ← 5 workflows as slash commands
      stand-test-design.md      /stand-test-design   — text case → scenario design
      stand-test-yaml.md        /stand-test-yaml     — design → AI-format scenario (+ gates)
      stand-test-java.md        /stand-test-java     — design → Java DSL test (+ gates)
      stand-test-validate.md    /stand-test-validate — final readiness gate before commit
      stand-test-debug.md       /stand-test-debug    — failed test → debugging report
    rules/
      stand-test-guardrails.md  ← non-negotiable constraints (mirrors ForbiddenOperation)
```

Each skill directory is self-contained: its SKILL.md references the colocated template,
checklists and worked example by relative path, so the bundle works wherever `.claude/` lives.

## Installation into a consumer project (e.g. QA_TEST)

1. Copy the bundle contents into the consumer repo:
   `cp -R docs/ai-agent/.claude/* <consumer-repo>/.claude/`
   (merge with an existing `.claude/`; nothing here collides with generic skills).
2. Verify the consumer project has: the SDK modules on the test classpath (BOM + junit or
   starter + adapters + config/allure) and its environment registry
   (`stand-test-environments.yml` or `application.yml` `stand.test.environments.*`).
3. For the JSON track additionally approve/add `com.networknt:json-schema-validator:1.5.6`
   + `jackson-databind` as test dependencies (the SDK ships only the schema resource).
4. Start with `/stand-test-design` on a real text case.

In THIS repo the same skills are also registered at the root `.claude/skills/stand-test-*`
as thin wrappers pointing into the bundle — the bundle stays the single source of truth.

## How a text case becomes an autotest

```
Text case
  → /stand-test-design      (skills: case-analysis → environment-mapping → scenario-design)
  → /stand-test-java  OR  /stand-test-yaml   (+ fixture-authoring, safety-review)
  → /stand-test-validate    (schema/compile/run-skip-gate/safety/quality → readiness report)
  → human approval → commit
  → on failure: /stand-test-debug
```

## Preferred authoring track

| Track | When | Why |
|---|---|---|
| **Java DSL** (default) | Any real business case; anything needing `db.seed`/`db.cleanup`, `rest.put`/`rest.delete`, non-equals matchers outside REST rewording, gRPC custom metadata, negative paths | Full feature surface; validator still runs inside `stand.run(...)` |
| **AI JSON/YAML** (`steps/type`) | Simple read-only flows fully inside the executable subset below | Machine-checkable before any code exists; smallest review surface |
| **Hybrid** | Case partially fits the AI format | AI document for the declarative part, thin Java test around it |

### AI-format executable subset

The JSON Schema accepts slightly more than the runtime executes ("schema ⊇ executable"):

| Step type | Executable notes |
|---|---|
| `rest.get`, `rest.post` | all five matchers; body only `{"fixture": "path"}` |
| `rest.expectEventually` | GET-only; `timeout` required; needs `expect.status` and/or `assert` |
| `kafka.send` | `payload.fixture` only; `correlation: {inject: true}` |
| `kafka.expect` | `timeout` + `assert` required; **equals-only**; `correlation: {fromContext: true}` |
| `db.expectEventually` | SELECT-only; `expect.singleValue` only (`rowExists` rejected) |
| `grpc.unary` | `timeout` required; `request.fixture` only; **equals-only**; `expect.status` rejected |

Not in the AI format at all: `db.query`/`db.seed`/`db.cleanup`, `rest.put`/`rest.delete`,
gRPC custom metadata. Source of truth: `ai/stand-test-ai-generation-rules.md` +
`/schema/stand-test-scenario.schema.json` inside the published `stand-test-ai-schema` jar
(`ru.alfa.stand.test.ai.AiSchemaResources`). **On any conflict, the jar resources win.**

## SDK modules an agent touches

| Module | Role |
|---|---|
| `stand-test-core` | `Scenario.builder(...)`, failure semantics, `ForbiddenOperation`, `${var}` resolver |
| `stand-test-rest` / `-kafka` / `-db` / `-grpc` | Typed lazy step builders |
| `stand-test-await` | The only sanctioned wait engine (via `*.expectEventually`) |
| `stand-test-junit` | `@StandTest`/`@StandEnv`/`@StandScenarioId`, `StandClient` injection |
| `stand-test-spring-boot-starter` | `@SpringBootTest` + `@Autowired StandClient`, `stand.test.*` config |
| `stand-test-config` | `stand-test-environments.yml` file registry |
| `stand-test-scenario-yaml` | `AiScenarioParser` (AI format, JSON or YAML), `YamlScenarioParser` (given/then) |
| `stand-test-ai-schema` | JSON Schema + generation-rules resources |
| `stand-test-allure` | Automatic Allure reporting via SPI (consumer adds `io.qameta.allure:allure-junit5:2.29.1`) |

## Safety constraints and prohibitions

The hard rules live in the bundle: [`.claude/rules/stand-test-guardrails.md`](.claude/rules/stand-test-guardrails.md)
(mirrors the 12-code `ForbiddenOperation` enum + review-only rules), with detection patterns
in [`.claude/skills/stand-test-safety-review/safety-checklist.md`](.claude/skills/stand-test-safety-review/safety-checklist.md).
Summary: aliases only, no secrets, no sleeps, bounded timeouts, no destructive SQL,
SDK-owned `testRunId`/`correlationId`, no pipeline/validator bypass, no production envs,
no SDK modifications, human approves every merge.

## How to run validation

**AI JSON/YAML document** (before it is ever executed):

```java
// Schema gate — requires com.networknt:json-schema-validator (2020-12) + jackson (test classpath)
JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
JsonSchema schema = factory.getSchema(AiSchemaResources.scenarioSchemaJson());
Set<ValidationMessage> messages = schema.validate(new ObjectMapper().readTree(documentJson));
// must be EMPTY

// Parse gate (parser fails closed on non-executable constructs)
Scenario scenario = new AiScenarioParser().parse(documentJson);

// Guardrail self-check — REQUIRES the registry overload; the one-arg validate(Scenario)
// checks structure only and runs zero guardrails
EnvironmentRegistry registry = new FileEnvironmentRegistry();   // stand-test-config
new DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid();
```

**Java test**: `./gradlew compileTestJava checkstyleTest` in the consumer project.
`DefaultScenarioValidator` runs automatically inside `stand.run(...)`.

## How to run generated tests

Generated tests are gated to **skip** (not fail) without stand configuration:

```java
@EnabledIfEnvironmentVariable(named = "ORDER_SERVICE_URL", matches = ".+")
```

Run `./gradlew test` with the env vars named by the registry's `*-ref` fields exported.
Assertion failures surface as `StandTestAssertionError` (JUnit red), infra/config problems
as `StandTestException` (JUnit error).

## How to review the result

Run `/stand-test-validate`; it applies the safety checklist (blocking), the review and
flakiness checklists, and produces a READY/NOT-READY report. A generated test is never
merged without an explicit human decision on that report.
