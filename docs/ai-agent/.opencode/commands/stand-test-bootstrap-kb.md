---
description: Cold-start an empty knowledge base from what the consumer project already has - the environment registry (aliases, high confidence) and its existing SDK-based tests (endpoints/topics/tables/gRPC methods, medium confidence). Writes candidates only; promotion stays behind the existing human gate. Dry-run by default.
version: 1
---

# /stand-test-bootstrap-kb — the knowledge base on the day the kit is installed

Runs [`stand-test-kb-bootstrap`](../skills/stand-test-kb-bootstrap/SKILL.md). Without it the first
case is an interrogation: an empty KB turns every contract detail into a blocking question, which is
correct behaviour and an awful first hour.

## Input

- `--from-registry` (default) — the environment registry: services, topics, datasources, gRPC
  targets. High confidence by construction: a person curated that file.
- `--from-tests [<glob>]` — existing tests that use the stand-test SDK. Medium confidence,
  provenance `file:line`. Not a parser of RestAssured/WebClient/JDBC tests, and it says so rather
  than guessing.
- Mode: `dry-run` (default) | `apply`.

## Steps

1. **`node .opencode/hooks/stand-guard.mjs kb-status --json`** — the registry path, what the KB already
   holds, and the aliases the registry declares that the KB lacks. That list is the worklist, and it
   comes off the file rather than from reading and deciding.
2. **No registry ⇒ stop and report it.** Nothing can be bootstrapped, and the SDK will not run
   either; that is the finding.
3. **Form candidates** per the skill: alias verbatim, `high` from the registry, `medium` from tests,
   `source.quote` = the alias line only — never the block beneath it, which holds `*-ref` names.
4. **Validate** every candidate against the candidate schemas (`knowledge-base/schema/`); a failure
   is skipped and reported, never written.
5. **dry-run**: print the report, write nothing.
6. **apply**: write under `knowledge-base/candidates/bootstrap-registry/` and
   `knowledge-base/candidates/bootstrap-tests/` — never into the curated collections. The write hook
   refuses anything else.
7. **Check what landed**: `node <bundle>/hooks/stand-guard.mjs kb-validate --exit-code` and
   `alias-check` — the consumer-side half of the schema tests, which live in the SDK repository and
   do not travel with the bundle.
8. **Hand over to the existing gate**: `/stand-test-review-kb-candidates`, then
   `/stand-test-apply-kb-candidates`. A human decides what enters the KB, exactly as for an ingested
   specification.

## Mandatory checks

- [ ] Every candidate traces to a line of the registry or of a real test — nothing inferred.
- [ ] No endpoint, field, table or gRPC method invented for an alias that merely exists.
- [ ] No `*-ref` VALUE copied into a quote, note or description.
- [ ] Report names what bootstrap CANNOT supply: fields, rules, meanings, untested endpoints.

## Human approval points (blocking)

- The promotion of any candidate into the curated KB (the existing review→apply gate).
- Every `medium`-confidence candidate from tests — evidence of what was exercised is not evidence of
  the contract.
