# KB update report — <source> → <kb location>, <dry-run|apply>

## Summary

<One paragraph: source type, entities detected, counts per diff bucket, verdict.>

## Source parsed

| Property | Value |
|---|---|
| Source | <path or "pasted"> |
| Detected type | openapi / asyncapi / proto / sql / markdown / application-yml |
| Entities found | <N endpoints, M topics, ...> |
| Parse warnings | <list or "none"> |

## Entries added

| Entry id | Type | File | Derived from |
|---|---|---|---|

## Entries updated

| Entry id | Field | Old | New | Basis |
|---|---|---|---|---|

## Conflicts (human resolution required)

| Entry id | Field | KB value | Source value | Why not auto-resolved |
|---|---|---|---|---|

## Entries skipped

| Candidate | Reason (schema message / rule) |
|---|---|

## Removed candidates (report only — nothing was deleted)

| Entry id | Absent from source since |
|---|---|

## Validation result

- JSON Schema over touched files: <PASS/FAIL + messages>
- Referential integrity (service rollups, environment bindings): <PASS/FAIL + gaps>
- KB validation tests (`:stand-test-ai-schema:test` or consumer equivalent): <PASS/FAIL/NOT-RUN + why>
- Secret/URL scan of the diff: <CLEAN/findings>

## Files changed

<list with + / ~ markers; "none" in dry-run>

## Next actions

<follow-ups: environment bindings to add, conflicts awaiting a human, checklist findings>
