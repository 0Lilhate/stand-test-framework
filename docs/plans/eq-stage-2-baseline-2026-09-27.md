# Baseline before EQ stage 2

Recorded on 2026-09-27 before implementation edits.

- Branch: `target-solution`
- HEAD: `54f9f67dcef2dbc9ce8beae07e7383a8109208a2`
- Published modules in `settings.gradle.kts`: 13; `stand-test-http` and `stand-test-eq` do not exist yet.
- Baseline command: `./gradlew build --console=plain`
- Result: `BUILD SUCCESSFUL in 1m 39s`; 143 actionable tasks (48 executed, 95 up-to-date).
- Gradle reported deprecated features for Gradle 10; the build has no failing analysis or tests.
- `docs/arch/stand-test-sdk-implementation-plan.md` is absent. The current contracts are in
  `AGENTS.md`, module READMEs and source code; EQ requirements are in
  `docs/brd/eq-data-provisioning-brd.md` and `docs/plans/eq-data-provisioning-implementation-plan.md`.

Pre-existing working-tree status:

```text
 M AGENTS.md
 M CLAUDE.md
 M README.md
 M build.gradle.kts
 M docs/publishing.md
 M gradle.properties
?? .agents/
?? .codex/
?? .lavish/
?? docs/brd/eq-data-provisioning-brd.md
?? docs/plans/
```

SHA-256 before edits:

```text
9af53c77fe51eef39800d422bc0207893daf5e520a9a7443d9a4b1ae1f28adcf  AGENTS.md
9af53c77fe51eef39800d422bc0207893daf5e520a9a7443d9a4b1ae1f28adcf  CLAUDE.md
1ec4e866451333f2aa6fc1440de64c7d24639bd8888a5a7dd33504f89e01da4b  docs/plans/eq-data-provisioning-implementation-plan.md
3512840b6e5b344aa74e1f31f74e4e6cba5b95245b0ca52fd824727c13c777ea  docs/brd/eq-data-provisioning-brd.md
```
