---
description: 'Generate a validated AI-format JSON/YAML stand-test scenario from a scenario design: authoring, JSON Schema + parser gates, fixtures, safety review.'
version: 1
---

# /stand-test-yaml — scenario design → AI-format scenario

`ScenarioDesign.md` → validated AI-format JSON/YAML scenario + fixtures + runner test.

## Precondition

The design's track is **AI format** and every step was verified against the executable subset
(seven step types; equals-only on `kafka.expect`/`db.expectEventually` while `rest.*` and
`grpc.unary` take all five matchers; fixtures-only bodies; no seed/cleanup). If any
step falls outside — switch to [Workflow 3](stand-test-java.md) instead of
stretching the format.

## Input

`ScenarioDesign.md` + environment mapping report.

## Output

- `src/test/resources/ai/<scenario-id>.json`
- `src/test/resources/fixtures/**` for every `fixture:` reference
- a minimal runner test (`AiScenarioParser().parseResource(...)` + `stand.run(...)`)
- safety review report

## Steps

1. **Author** — run [`stand-test-yaml-authoring`](../skills/stand-test-yaml-authoring/SKILL.md):
   generate the document strictly from the design's step table.
2. **Generate fixtures** — run
   [`stand-test-fixture-authoring`](../skills/stand-test-fixture-authoring/SKILL.md) for every
   `fixture:` reference (same change set; no dangling references).
3. **Validate against the schema** — networknt `V202012` over
   `AiSchemaResources.scenarioSchemaJson()`; the validation message set must be EMPTY.
   Then the **parse gate**: `new AiScenarioParser().parse(document)` must not throw, and
   `new DefaultScenarioValidator().validate(scenario, registry).throwIfInvalid()` must pass
   (registry overload — the one-arg `validate(scenario)` is structural-only).
   A parser rejection ("not executable yet", "only executable for REST assertions") means the
   document left the executable subset — fix or fall back to Workflow 3.
4. **Safety review** — run
   [`stand-test-safety-review`](../skills/stand-test-safety-review/SKILL.md) over document +
   fixtures + runner test, in the `stand-test-safety-reviewer` SUBAGENT. Any BLOCK finding →
   regenerate (never hand-patch around a rail). Then record the verdict from THIS context:
   `node <bundle>/hooks/stand-guard.mjs record-gate --gate safety-review --verdict PASS <files>` —
   the scenario document and every fixture are executable artifacts, and the session cannot end
   while one of them is uncovered.
5. **Finalize** — emit the runner test, apply
   [`before-committing-checklist.md`](../skills/stand-test-test-review/before-committing-checklist.md),
   then hand off to [Workflow 4](stand-test-validate.md).

## Mandatory checks

- [ ] Schema validation empty; parse gate green; guardrail self-check green.
- [ ] Every fixture referenced exists; paths relative, no `..`.
- [ ] Step ids unique (schema does not check this — verify manually).
- [ ] Safety review verdict PASS / PASS-WITH-NOTES.

## Human approval points (blocking)

- Adding the JSON-Schema validator dependency (`com.networknt:json-schema-validator` +
  `jackson-databind`) to the consumer build, if not already present.
- Final artifact approval before commit (via Workflow 4's report).
