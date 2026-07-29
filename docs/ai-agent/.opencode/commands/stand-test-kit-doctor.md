---
description: Check the installed kit against the manifest that travelled with it - version, files that never arrived, files edited since, hooks that are not wired, and what the project still lacks around it. Reports only; changes nothing.
version: 1
---

# /stand-test-kit-doctor — is this installation the kit, and can it run?

Every mechanism that holds this bundle together — the schema tests, the parity tests, the inventory
snapshot — lives in the SDK repository and stops existing the moment the kit is copied here. At a
consumer the kit is a directory of markdown that nothing checks: a guardrail softened by hand looks
exactly like a guardrail, and a hook lost while merging `settings.json` looks exactly like a working
kit until the moment it matters.

## Steps

1. `node <bundle>/hooks/stand-guard.mjs doctor` (add `--exit-code` in CI, `--json` to parse it).
2. Read the four answers it gives:
   - **version** — which set of prompts stands here;
   - **files** — did not arrive / edited after installation / present but not in the manifest;
   - **hooks** — all five events wired, and the perimeter still refusing edits to the kit's own
     rules and hooks;
   - **the project around the kit** — environment registry, knowledge base.
3. Act on what it found, and only on that:
   - a file that did not arrive ⇒ re-install: `node install.mjs <project> --apply`;
   - a file edited after installation ⇒ decide deliberately. A local edit to a guardrail survives
     every update and is invisible to everything else. If the change is right, it belongs upstream
     in the SDK repository, not in one consumer's copy;
   - a missing hook ⇒ `settings.json` was merged by hand; restore the block from the kit's own copy;
   - no manifest at all ⇒ the kit arrived by `cp -R`; re-install so that later checks are possible.
4. `node <bundle>/hooks/stand-guard.mjs kb-validate --exit-code` and `alias-check` when the project
   keeps a knowledge base — the doctor checks the KIT, those check the BASE.

## What it does not know

It compares an installation against the manifest that arrived with it. Someone who edits both a file
and the manifest gets the report they asked for; the doctor catches drift, which is what actually
happens. It says nothing about whether the prompts are any good, and nothing about the stand.
