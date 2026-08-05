# AGENTS.md

**The instructions for this repository live in [`CLAUDE.md`](CLAUDE.md). Read that file.**

This is a pointer, not a second copy. `AGENTS.md` used to be a full 179-line duplicate of
`CLAUDE.md`, and the two had already drifted: an `# opencode.md` header, a duplicated paragraph,
and a rules section restating the harness layout in its own words.

The harness itself exists twice at this root: `.claude/` (tracked) and `.opencode/` (untracked,
mirrored from `.claude/`), the latter indexed by `.opencode/AGENTS.md`. Repository guidance is
not part of that mirror — it lives in `CLAUDE.md` alone.

Keeping one copy is the fix. This repository has been bitten repeatedly by parallel copies falling
out of sync, most visibly in the `docs/ai-agent/` bundle, where the gRPC matcher set is still
stale in one copy while current in the other.

Nothing else belongs in this file. Add repository guidance to `CLAUDE.md`.
