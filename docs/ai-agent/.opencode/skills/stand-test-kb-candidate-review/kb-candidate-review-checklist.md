# Checklist: KB candidate review

Applied to the staged candidates of one `document-id` before any promotion. Binary items; any FAIL
keeps the involved candidate out of the apply set. No curated KB is written during review.

## Schema & provenance

- [ ] Every `*.candidates.yml` re-validates against its candidate schema.
- [ ] Every candidate/flow/rule/scenario/unresolved/conflict item has `source` with
      `documentId` + `documentName` + a non-empty, redacted `quote` (empty quote = FAIL).
- [ ] Every candidate has a `confidence` tier and a `naturalKey`.

## Sterility (safety scan)

- [ ] No candidate string carries a URL/JDBC or an inline secret (safety-review scan CLEAN).
- [ ] Environment candidates are refs/aliases only — no values, no production ids/URLs.

## Confidence & completeness gating

- [ ] `low`-confidence candidates are shown and marked NOT apply-eligible.
- [ ] `medium` candidates are individually ticked (or left blocked) by the human.
- [ ] `high` candidates that are `partial` or `promotionBlocked` (e.g. db-table with no curated home)
      are blocked.

## Anti-invention

- [ ] Every unresolved item is shown with its `type` and `blockingFor`; none was silently dropped.
- [ ] No candidate contains a fabricated method/path/topic/table/column/method — spot-check each
      `high` candidate's value against its quote.

## Conflicts

- [ ] Every conflict is shown with BOTH sides and their provenance.
- [ ] No conflict was auto-resolved; each carries `autoResolvable: false` and a human `resolution`
      (or stays `unresolved` and blocks its ids).

## Referential integrity (promotion readiness)

- [ ] Each endpoint/probe/grpc candidate's owning service/datasource is either already curated or is in
      the approved candidate set (else the id is blocked to avoid orphan promotion).
- [ ] Facts with no curated home are listed under the report's "KB schema gaps surfaced" — a human
      decides whether to extend the schema (a separate SDK change), not this pipeline.

## Decision recorded

- [ ] Each candidate's `review` block and `status` reflect the human decision.
- [ ] The report ends with the eligible-vs-blocked split and points at
      `/stand-test-apply-kb-candidates <document-id>`.
