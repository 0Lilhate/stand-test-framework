# Agent Orchestration

## Available Agents

Located in `~/.claude/agents/`:

| Agent | Purpose | When to Use |
|-------|---------|-------------|
| planner | Implementation planning | Complex features, refactoring |
| architect | System design | Architectural decisions |
| java-reviewer | Java/Spring Boot code review | After writing Java code |
| kotlin-reviewer | Kotlin/Android code review | After writing Kotlin code |
| java-build-resolver | Fix Java/Maven/Gradle build errors | When Java build fails |
| kotlin-build-resolver | Fix Kotlin/Gradle build errors | When Kotlin build fails |
| database-reviewer | PostgreSQL review | SQL, migrations, schema design |
| docs-lookup | Library docs via Context7 | API/setup questions |
| doc-updater | Documentation updates | Updating codemaps/READMEs |
| bash-expert | Shell script authoring | Bash/CI scripts |
| harness-optimizer | Tune `.claude/` config | Reliability/cost/throughput |

## Immediate Agent Usage

No user prompt needed:
1. Complex feature requests - Use **planner** agent
2. Java code just written/modified - Use **java-reviewer** agent
3. Kotlin code just written/modified - Use **kotlin-reviewer** agent
4. Architectural decision - Use **architect** agent

## Parallel Task Execution

ALWAYS use parallel Task execution for independent operations:

```markdown
# GOOD: Parallel execution
Launch 3 agents in parallel:
1. Agent 1: Security analysis of auth module
2. Agent 2: Performance review of cache system
3. Agent 3: Type checking of utilities

# BAD: Sequential when unnecessary
First agent 1, then agent 2, then agent 3
```

## Multi-Perspective Analysis

For complex problems, use split role sub-agents:
- Factual reviewer
- Senior engineer
- Security expert
- Consistency reviewer
- Redundancy checker
