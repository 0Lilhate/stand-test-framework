# Checklist: before committing a generated test

Run as the LAST step, after Workflow 4 (`generated-test-validation`) produced its report.
Any unchecked box = no commit proposal.

## Gates green

- [ ] Compiles: `./gradlew compileTestJava` (+ `checkstyleTest` where wired) — output attached.
- [ ] AI-format only: schema validation EMPTY + `AiScenarioParser().parse(...)` clean +
      `DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid()` clean
      (registry overload — the one-arg form is structural-only).
- [ ] Test run with env vars ABSENT → SKIPPED (gate works; does not fail).
- [ ] Test run against a configured stand (if available) → GREEN, or the failure went through
      Workflow 5 and has a human-approved disposition.
- [ ] Safety review verdict: PASS / PASS-WITH-NOTES (report attached).
- [ ] Quality review verdict: APPROVE / APPROVE-WITH-NOTES (report attached).

## Change set clean

- [ ] Only agent-facing/consumer-test files changed: test class, scenario doc, fixtures,
      (approved) registry additions. NO SDK runtime code, NO core API, NO new adapters.
- [ ] No dependencies added beyond the sanctioned list (`allure-junit5:2.29.1`,
      JSON-Schema validator + jackson, JDBC driver) — and each addition was human-approved.
- [ ] Every fixture referenced by the scenario is IN the change set; no dangling paths.
- [ ] grep of the diff is clean:
      `Thread.sleep|Awaitility|http://|https://|jdbc:|Authorization|Bearer |Basic |password|secret|new DefaultScenarioRunner|DriverManager|KafkaConsumer|ManagedChannelBuilder`.
- [ ] No real credentials, PII, production values, real endpoints anywhere in the diff
      (fixtures and registry additions included — registry holds env-var NAMES in `*-ref`
      fields, or `${ENV_VAR:...}` placeholders in the Spring starter's endpoint value twins;
      never a resolved endpoint or secret value).

## Bookkeeping

- [ ] Test javadoc (Java track) or document `title`/`description` (AI track) links the original case (ticket/manual id) and lists assumptions +
      NOT-AUTOMATABLE items.
- [ ] Commit message follows the consumer repo's convention (`test: ...`).
- [ ] A HUMAN has explicitly approved the validation report — the agent never merges on its
      own authority.
