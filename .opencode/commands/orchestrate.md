---
description: Delegate a complex task to the orchestrator agent. The orchestrator plans, spawns subagents in parallel, and collects results.
---

# Orchestrate Command

Delegate a complex multi-step task to the **orchestrator** subagent. The orchestrator:
1. Analyzes the task and breaks it into independent work units
2. Spawns specialized subagents in parallel (`background: true`)
3. Chains dependent steps sequentially
4. Collects results and synthesizes the output

## Usage

```
/orchestrate [workflow-type] [task-description]
/orchestrate custom <agent-list> <description>
```

## How It Works

```
You (/orchestrate) → @orchestrator (subagent, depth 1)
                       ├── planner (plan the work)
                       ├── explore (find relevant code)        ← parallel
                       ├── java-reviewer / kotlin-reviewer     ← parallel
                       ├── database-reviewer                   ← parallel
                       ├── general (implement)                 ← after plan
                       └── java-build-resolver (fix build)     ← after implement
```

The `orchestrator` has `task: allow` for all project agents + built-in types. Other agents (planner, java-reviewer, etc.) have `task: deny` for everything except `explore` — they can search code but cannot spawn other agents.

## Workflow Types

### feature
Full feature implementation:
- orchestrator → planner → explore → general (implement) → java-reviewer → java-build-resolver

### bugfix
Investigate and fix a bug:
- orchestrator → planner → explore → java-build-resolver → general (fix) → java-reviewer

### refactor
Safe refactor across multiple files:
- orchestrator → architect → planner → explore → general (implement) → java-reviewer → database-reviewer (if SQL)

### security
Security audit and remediation:
- orchestrator → explore → java-reviewer + kotlin-reviewer (parallel) → general (fix CRITICAL)

### custom
```
/orchestrate custom "architect,java-reviewer,database-reviewer" "Redesign caching layer with new indexes"
```

## Configuration

- `subagent_depth: 3` in `opencode.json` — allows main → orchestrator → subagent chains
- `orchestrator` agent in `.opencode/agents/orchestrator.md` with `task: allow` for all project agents
- `planner`, `java-reviewer`, `kotlin-reviewer` have `task: allow` only for `explore` (can search but not spawn)

## Tips

1. **orchestrator spawns parallel by default** — independent subagents run concurrently
2. **Dependencies are explicit** — orchestrator waits for planner before spawning implementer
3. **CRITICAL findings** — orchestrator creates a new fix task and spawns a build-resolver
4. **orchestrator returns a synthesized report** — not raw subagent output