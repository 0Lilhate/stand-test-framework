# Checklist: unstructured spec ingestion

Binary items. Any FAIL stops the ingest with a report error — no partial writes.

## Reader (Stage 0)

- [ ] The document was read via an external converter (pandoc / PDF tool / OCR), not from memory.
- [ ] `_source/normalized.md` + `_source/manifest.json` (page/section/table/figure anchors, sha256) exist.
- [ ] The reader tool + version is recorded in the report's "Source parsed" block (reproducibility).
- [ ] The original binary is under the GITIGNORED `_source/raw/` — it is not committed.
- [ ] A missing tool / unreadable region produced a `needs-tool` / `unparseable-source` report error,
      NOT a hand-authored entry.

## Document identity

- [ ] `documentId` is a mechanical kebab of the file name/title (stable across re-runs).
- [ ] `documentVersion` / `documentDate` are set only if the document states them, else `null`.
- [ ] `sourceHash` is recorded; a pre-existing document with the same hash was reported as a re-ingest.

## Provenance & confidence (every candidate)

- [ ] Every candidate/flow/rule/scenario/unresolved/conflict item has a `source` block with
      `documentId` + `documentName` + a non-empty, redacted `quote`.
- [ ] Every item has a `confidence` tier (high/medium/low) grounded in the quote next to it.
- [ ] Diagram/OCR-derived items are forced to `low` confidence.

## Anti-hallucination

- [ ] Every missing HTTP method / path / topic / table / column / env is an `unresolved` item,
      never a guessed value.
- [ ] db-table and semantic (flow/rule) facts with no curated home carry
      `promotionBlocked: curated-collection-missing` and appear under "KB schema gaps surfaced".
- [ ] No candidate id was hand-invented; every id is a mechanical derivation, and each candidate
      carries a `naturalKey`.

## Sterility

- [ ] No candidate string carries a URL/JDBC (`noUrl`) — quotes have host placeholders, not live hosts.
- [ ] No candidate string carries an inline secret (`secretShape`) — secrets stripped before writing.
- [ ] Environment candidates carry env-var ref NAMES only, no values, no production ids.

## Validation & conflicts

- [ ] Every candidate passed its candidate schema; failures are in the report's `skipped` bucket.
- [ ] Conflicts were detected on BOTH axes (candidate vs curated KB, candidate vs candidate cross-doc).
- [ ] Conflicts are in `conflicts.candidates.yml` with `autoResolvable: false` and `whoDecides: human`.

## Output

- [ ] Extraction report written per the template with all buckets (empty lists shown, not omitted).
- [ ] `mode=dry-run` touched no files; `mode=create-candidates` wrote ONLY under `candidates/<document-id>/`.
- [ ] Nothing under the curated 8 collections was created or modified.
