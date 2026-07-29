---
name: stand-test-kb-bootstrap
description: "Cold-start the stand-test knowledge base of a consumer project from what it ALREADY has - the environment registry (aliases a human curated, high confidence by construction) and its existing SDK-based tests (endpoints/topics/tables/gRPC methods actually exercised, medium confidence, provenance file:line). Produces candidates only; the curated KB is written by the existing review→apply gate. Use via /stand-test-bootstrap-kb, on the day the kit is installed."
version: 1
---

# stand-test-kb-bootstrap — the knowledge base on day one

A consumer installs the kit and the knowledge base is empty. `stand-test-kb-lookup` is then doing
exactly what it should — every contract detail resolves to `missing`, every `missing` becomes a
blocking question — and the first case turns into an interrogation. What drives people away is not
the quality of what the kit generates; it is being asked twenty questions before it generates
anything.

This skill fills the base from what the project already has, and from nothing else.

## The two sources, and why their confidence differs

| Source | What it yields | Confidence | Why |
|---|---|---|---|
| The environment registry | Aliases: services, kafka topics, datasources, gRPC targets | `high` | A person wrote that file and the SDK does not start without it. It is curated by construction — bootstrap only moves it, it does not infer it |
| Existing SDK-based tests | Endpoints (method+path), topic aliases, tables, gRPC methods | `medium` | These were exercised against a real stand and passed, which is evidence — but of what the test happened to touch, not of the contract. A test can be green against a wrong path if nothing asserts the right one |

Everything else stays out. **No source here attests a JSON field, a column meaning, a business rule
or an endpoint nobody called** — inventing those from a plausible-looking test is exactly the failure
the KB exists to prevent, and a bootstrapped guess is worse than an empty KB because it looks
curated.

## Where the output goes

`knowledge-base/candidates/<document-id>/` — the staging area, never the curated collections. The
promotion path is the existing one: `/stand-test-review-kb-candidates` then
`/stand-test-apply-kb-candidates`, with a human between them. Bootstrap creates no new write channel
into the KB, because the reason the curated base is trusted is that a person approved every entry in
it, and a bulk import is the worst possible moment to make an exception.

Document ids are fixed: `bootstrap-registry` and `bootstrap-tests`. Re-running bootstrap re-derives
the same ids, so a second run diffs against the first instead of accumulating duplicates.

## Step 1 — read the machine's answer first

```bash
node .opencode/hooks/stand-guard.mjs kb-status --json
```

It reports the registry path, what the KB already knows, and per kind the aliases the registry
declares that the KB does not. That list is the worklist. Take it from the command rather than
reading the registry yourself and deciding: the point of this stage is that the alias came off a
file, and a list you assembled from memory is the thing the whole kit is built to avoid.

If it reports no registry, stop and say so. Without one there is nothing to bootstrap from, and the
SDK will not run either — that is the finding, not a reason to invent aliases.

## Step 2 — `--from-registry`

For each alias on the worklist, emit ONE candidate:

- `candidateType`: `service` | `kafka-topic` | `datasource` | `grpc-target`
- `candidateId` and `normalized.id`/`normalized.alias`: the alias verbatim. Never renamed, never
  prettified — the alias is the join key between the KB, the registry and the generated test.
- `confidence: high`, `status: new`
- `source.documentId: bootstrap-registry`, `documentName`: the registry file's name
- `source.quote`: **the alias line only** — `client-service:`. Not the block under it: that block
  holds `*-ref` names and the occasional value twin, and a quote is committed text.
- `extracted`: only what the registry itself states — `correlation` source/name, `write-allowed` and
  `allowed-schemas` for a datasource. The topic's real `name` (`pakt.response.ift`) is registry
  configuration, not a contract detail; carry it only where the candidate schema has a home for it.

What you must NOT do:

- **Never copy a `*-ref` VALUE anywhere.** A ref is the NAME of an environment variable and the KB
  stores names, but a quote that drags `password-ref: MAIN_DB_PASSWORD` into a candidate is a
  credential name in version control for no benefit. Quote the alias line.
- Never invent endpoints for a service, topics for a cluster, or tables for a datasource. The
  registry says these aliases exist and where they resolve; it says nothing about what they contain.
- Never bootstrap an alias that is not on the worklist. If you believe one is missing, say so — a KB
  entry with no registry alias behind it cannot be resolved by any test.

## Step 3 — `--from-tests`

Scan the project's EXISTING tests for stand-test SDK usage, and only that:

- Java DSL: `RestStep.<method>("<service-alias>", "<path>")`, `KafkaStep.expect("<topic-alias>")`,
  `DbStep...("<datasource-alias>", ...)` with its SQL, `GrpcStep.unary("<target>", "<service/method>")`.
- AI-format documents: `steps[].type` + `service`/`topic`/`datasource`/`target` + `path`/`method`.

Each yields a candidate with `confidence: medium` and `source.quote` = the matched line, with
`extractionNotes` carrying `<file>:<line>`. A path with an inlined id (`/orders/12345/status`)
is recorded with the id replaced by a placeholder segment and an `unresolved` item beside it — a
literal id in a KB path is a stale pointer that will outlive the row it points at.

**This is not a parser of foreign test frameworks.** A project whose tests are RestAssured, plain
WebClient or JDBC gets nothing from this step, and the report says so plainly instead of guessing at
their intent. That is a bounded, honest result: the registry half still works, and the rest of the KB
fills in through `/stand-test-kb-update` from real sources — OpenAPI, AsyncAPI, proto, SQL schema.

## Step 4 — report, and stop

Dry-run is the default. Print:

```markdown
## Registry        <path>, aliases: service N, kafka-topic N, datasource N, grpc-target N
## Already in KB   <count>
## Candidates      <n> from the registry (high), <n> from tests (medium)
## Skipped         <what was found and deliberately not imported, and why>
## Not covered     <what the KB still lacks that no source here can supply: fields, rules, meanings>
## Next            /stand-test-review-kb-candidates <document-id>
```

`Not covered` is the load-bearing section. After bootstrap the project has aliases and some
endpoints; it does not have a knowledge base. Saying that plainly is what keeps the next case's
`missing` items from looking like a malfunction.

Write files only when the invocation asked for it, and write them only under
`knowledge-base/candidates/`. The write hook refuses anything else, and correctly.

## After bootstrap: what a missing alias means now

`stand-test-kb-lookup` treats an alias differently depending on who attests it, and the rule is
permanent rather than a start-up mode — see that skill. In short: registry-attested alias with no KB
entry ⇒ recorded assumption + TODO; alias in neither ⇒ blocking question; contract DETAIL missing ⇒
blocking question, always, whatever the registry says.
