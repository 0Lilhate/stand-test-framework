# Spec ingestion report — <document-name> v<version> (<document-id>) → knowledge-base/candidates/<document-id>, <dry-run|create-candidates>

<!--
Mirrors the stand-test-kb-update report skeleton (Summary / Source parsed / Entries / Conflicts /
skipped / Validation result / Files changed / Next actions) so a reviewer reads ONE report shape,
augmented for an unstructured document with a Confidence breakdown, a KB-schema-gaps section and a
non-KB semantic-content section. "Derived from" = documentId §section p.page; an empty quote/Derived-from
is a review-checklist FAIL.
-->

## Summary

<Formats read, reader tool + version, sections/tables/figures counts, candidate counts per bucket,
confidence split, conflict count, verdict.>

## Source parsed

| Property | Value |
|---|---|
| Document / documentId | <name> / <kebab> |
| Version / Date | <ver or n/a> / <date or n/a> |
| Type | docx / pdf / markdown / txt / html |
| Reader tool | <pandoc \| pdf-tool \| tesseract> + version |
| Source file (`_source/raw/`) | <file> (gitignored; hash sha256:<...>) |
| Pages / Sections / Tables / Figures | N / M / K / J |
| Parse warnings | <OCR pages, unreadable regions, or "none"> |

## Confidence breakdown

| Confidence | Count | Routing |
|---|---|---|
| high | N | category file; eligible after human review |
| medium | N | category file; needs an explicit per-item tick |
| low | N | unresolved.candidates.yml only; never auto-applied |

## Candidates added (staging)

| Candidate id | Type | Confidence | Derived from | naturalKey |
|---|---|---|---|---|
| <id> | endpoint | high | <doc> §4.1 p.12 | POST /api/... |

## Candidates matching existing KB (updated / unchanged)

| Candidate id | Field | KB value | Candidate value | Confidence | Bucket |
|---|---|---|---|---|---|

## Conflicts (human resolution required)

| conflictId | Type | KB value | Candidate value | Source rank / confidence | Why not auto-resolved |
|---|---|---|---|---|---|

## Candidates skipped (never written)

| Candidate | Reason (candidate-schema message / rule / low-confidence / partial) |
|---|---|

## Unresolved items

| id | type | question | blockingFor |
|---|---|---|---|

## KB schema gaps surfaced (no curated home — human decides)

| Extracted fact | Needed field / entity | Provenance | Note |
|---|---|---|---|
<!-- endpoint await timeout, kafkaTopic captures, dbProbe rowExists, db write/seed table, error/negative contract -->

## Non-KB semantic content (routed to scenario-design / testCaseMapping)

| Item | Type (businessFlow / businessRule / testScenario) | Provenance | Destination |
|---|---|---|---|

## Validation result

- Candidate JSON Schema over staged files: <PASS/FAIL + messages>
- Provenance completeness (every item has documentId + documentName + quote): <PASS/FAIL + offenders>
- Referential integrity (payload serviceId/datasourceId resolvable via kb-lookup): <PASS/FAIL + gaps>
- Secret/URL scan of candidates (safety-review): <CLEAN / findings>
- KB validation unaffected (candidates NOT on the pinned allowlist): <CONFIRMED>

## Files changed

<+ / ~ markers under candidates/<document-id>/; "none" in dry-run>

## Next actions

<Run /stand-test-review-kb-candidates <document-id>. db-table / semantic candidates awaiting a curated
schema extension; medium items awaiting a tick; conflicts awaiting a human; env-var refs to add.>
