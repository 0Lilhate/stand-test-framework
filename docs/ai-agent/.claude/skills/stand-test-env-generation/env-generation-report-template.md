# Env generation report — <envId> → <target file>, <dry-run|apply>

## Summary

<One paragraph: environment, surface (plain-yml / starter), counts, verdict.>

## Environment

| Property | Value |
|---|---|
| Environment id | <envId> |
| Surface | stand-test-environments.yml / application.yml (stand.test.*) |
| Modules included | rest,kafka,db,grpc (as requested) |

## Target file

<path; created or merged>

## Added properties

| Registry key | Value (ref/placeholder) |
|---|---|

## Updated properties

| Registry key | Old | New | Basis (KB entry) |
|---|---|---|---|

## Unchanged / unmanaged properties

<keys inside the managed subtree preserved verbatim because the KB does not derive them>

## Missing KB references

| Expected | Why missing (no binding / no entity / filtered out) |
|---|---|

## Env vars required at run time

<union of every *-ref name and every ${VAR:} placeholder variable — feeds the skip-gate and the pre-run env check>

## Security check

- No bare inline secret value (no `${}`); a `${VAR:default}` secret twin only if explicitly requested: <CLEAN/findings>
- No `*-ref` field carrying a `${...}` placeholder (double-resolution); one twin per field (starter only): <CLEAN/findings/N-A>
- No production environments: <CLEAN/findings>

## Validation result

- YAML re-parse: <PASS/FAIL>
- Alias coverage vs KB bindings: <PASS/missing list>
- Registry loads (`FileEnvironmentRegistry` / starter binding, when runnable): <PASS/FAIL/NOT-RUN>

## Files changed

<list; "none" in dry-run>
