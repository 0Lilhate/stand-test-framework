---
description: Post-hoc review of an EXISTING generated stand-test autotest - KB alignment (no invented endpoints/topics/queries/methods), SDK pipeline usage, safety guardrails, assertion quality, compilation. Wraps the /stand-test-validate gates plus a KB-alignment gate for tests written earlier or edited by hand.
---

# /stand-test-review-generated-test — existing test → review report

Reviews a generated (possibly since hand-edited) test against the SDK boundaries AND the
knowledge base. For a freshly generated change set prefer [`/stand-test-validate`](stand-test-validate.md);
this command adds the KB-alignment gate and works when the original design artifacts are gone.

## Input

- The test class (and its fixtures, AI-format document if any).
- The consumer project's KB (`knowledge-base/`) and registry.
- The case's `testCaseMapping` entry, if present.

## Steps

1. **Reconstruct the surface** — list every alias, path, topic, SQL statement, gRPC method,
   timeout, capture and assertion the test uses (grep the class + fixtures, not memory).
2. **KB alignment gate** — every item from step 1 must resolve to a KB entry (or to an assumption
   recorded in the mapping entry): service alias → `services/`, method+path → `endpoints/`,
   topic alias → `kafka/` + environment `actualName`, SQL → a `dbProbes` entry (verbatim modulo
   whitespace), gRPC method → `grpc/`. Unresolvable item = HIGH finding ("invented contract");
   KB-vs-test divergence = finding with the KB cited.
3. **SDK pipeline usage** — injected `StandClient`/`@StandTest` wiring, single `stand.run(...)`
   execution point, no eager IO, no `new DefaultScenarioRunner/DefaultStandClient`, no validator/
   runner overrides, `VariableStore` captures via `capture`/`${var}`, SDK-owned
   `testRunId`/`correlationId`, awaits only via `*.expectEventually`, failure semantics
   (`StandTestAssertionError` vs `StandTestException`) respected.
4. **Safety review** — run [`stand-test-safety-review`](../skills/stand-test-safety-review/SKILL.md)
   in full (raw clients, URLs, secrets, destructive SQL, `Thread.sleep`, unbounded timeouts,
   fixed ids without `${testRunId}`, production envs).
5. **Quality review** — run [`stand-test-test-review`](../skills/stand-test-test-review/SKILL.md)
   (coverage vs the case, assertion correctness incl. equals-only asymmetry, flakiness,
   cleanup pairing, reporting metadata, negative paths).
6. **Compile** — `./gradlew compileTestJava` (plus `checkstyleTest` where wired) in the consumer
   project; record the result, do not "fix" the test inside this workflow.
7. **Report** — reuse the
   [`generated-test-review-template.md`](../skills/stand-test-test-review/generated-test-review-template.md)
   with an added `KB alignment` section (aligned / findings table); verdict APPROVE /
   CHANGES-REQUIRED; fixes go back through the authoring workflow, failures through
   [`/stand-test-debug`](stand-test-debug.md).

## Mandatory checks

- [ ] Zero contract details without a KB/assumption source.
- [ ] Safety checklist PASS; quality checklists applied; compile recorded.
- [ ] Findings never downgraded to reach APPROVE.

## Human approval points (blocking)

- Accepting any "invented contract" finding as a new assumption (usually it means: extend the KB
  via /stand-test-kb-update instead).
- The merge decision on the review report.
